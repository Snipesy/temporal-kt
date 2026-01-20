package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.CallbackArena
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import com.surrealdev.temporal.core.internal.TemporalCoreWorker as InternalWorker

/**
 * Configuration options for a Temporal worker.
 */
data class WorkerConfig(
    val maxCachedWorkflows: Int = 1000,
    val enableWorkflows: Boolean = true,
    val enableActivities: Boolean = true,
    val enableNexus: Boolean = false,
)

/**
 * A high-level wrapper for a Temporal Core worker.
 *
 * Workers poll for tasks from the Temporal server and execute workflows and activities.
 * This class wraps the low-level FFM bindings and provides a coroutine-friendly API.
 *
 * Example usage:
 * ```kotlin
 * val worker = TemporalWorker.create(runtime, client, "my-task-queue", "default")
 * try {
 *     // Poll and complete tasks in a loop
 *     while (true) {
 *         val activation = worker.pollWorkflowActivation() ?: break
 *         // Process activation...
 *         worker.completeWorkflowActivation(completion)
 *     }
 * } finally {
 *     worker.initiateShutdown()
 *     worker.awaitShutdown()
 *     worker.close()
 * }
 * ```
 */
class TemporalWorker private constructor(
    internal val handle: MemorySegment,
    private val runtimePtr: MemorySegment,
    private val arena: Arena,
    private val callbackArena: Arena,
    val taskQueue: String,
    val namespace: String,
) : AutoCloseable {
    @Volatile
    private var closed = false

    @Volatile
    private var shutdownInitiated = false

    companion object {
        /**
         * Creates a new worker.
         *
         * @param runtime The Temporal runtime to use
         * @param client The connected client to use
         * @param taskQueue The task queue to poll
         * @param namespace The namespace to use
         * @param config Additional worker configuration
         * @return A new worker instance
         * @throws TemporalCoreException if worker creation fails
         */
        fun create(
            runtime: TemporalRuntime,
            client: TemporalCoreClient,
            taskQueue: String,
            namespace: String,
            config: WorkerConfig = WorkerConfig(),
        ): TemporalWorker {
            runtime.ensureOpen()
            client.ensureOpen()

            val arena = Arena.ofShared()
            val callbackArena = Arena.ofShared()

            return try {
                val workerPtr =
                    InternalWorker.createWorker(
                        clientPtr = client.handle,
                        arena = arena,
                        namespace = namespace,
                        taskQueue = taskQueue,
                        maxCachedWorkflows = config.maxCachedWorkflows,
                        workflows = config.enableWorkflows,
                        activities = config.enableActivities,
                        nexus = config.enableNexus,
                    )
                TemporalWorker(
                    handle = workerPtr,
                    runtimePtr = runtime.handle,
                    arena = arena,
                    callbackArena = callbackArena,
                    taskQueue = taskQueue,
                    namespace = namespace,
                )
            } catch (e: Exception) {
                callbackArena.close()
                arena.close()
                when (e) {
                    is TemporalCoreException -> throw e
                    else -> throw TemporalCoreException("Worker creation failed: ${e.message}", cause = e)
                }
            }
        }
    }

    /**
     * Checks if this worker has been closed.
     */
    fun isClosed(): Boolean = closed

    /**
     * Checks if shutdown has been initiated for this worker.
     */
    fun isShutdownInitiated(): Boolean = shutdownInitiated

    /**
     * Ensures the worker is not closed before performing an operation.
     * @throws IllegalStateException if the worker is closed
     */
    internal fun ensureOpen() {
        if (closed) {
            throw IllegalStateException("Worker has been closed")
        }
    }

    /**
     * Polls for a workflow activation.
     *
     * This method suspends until a workflow activation is available or shutdown is complete.
     *
     * @return The workflow activation protobuf bytes, or null if shutdown is complete
     * @throws TemporalCoreException if polling fails
     */
    suspend fun pollWorkflowActivation(): ByteArray? {
        ensureOpen()
        return try {
            CallbackArena.withExternalArenaResult(callbackArena) { arena, callback ->
                InternalWorker.pollWorkflowActivation(handle, arena, runtimePtr, callback)
            }
        } catch (e: TemporalCoreException) {
            // Treat shutdown errors as normal completion
            if (e.message?.contains("shutdown", ignoreCase = true) == true) null else throw e
        }
    }

    /**
     * Polls for an activity task.
     *
     * This method suspends until an activity task is available or shutdown is complete.
     *
     * Uses a long-lived arena because Rust spawns async tasks that hold the callback
     * pointer, which may complete much later than when this function is called.
     *
     * @return The activity task protobuf bytes, or null if shutdown is complete
     * @throws TemporalCoreException if polling fails
     */
    suspend fun pollActivityTask(): ByteArray? {
        ensureOpen()
        return try {
            CallbackArena.withExternalArenaResult(callbackArena) { arena, callback ->
                InternalWorker.pollActivityTask(handle, arena, runtimePtr, callback)
            }
        } catch (e: TemporalCoreException) {
            // Treat shutdown errors as normal completion
            if (e.message?.contains("shutdown", ignoreCase = true) == true) null else throw e
        }
    }

    /**
     * Completes a workflow activation.
     *
     * @param completion The completion protobuf bytes
     * @throws TemporalCoreException if completion fails
     */
    suspend fun completeWorkflowActivation(completion: ByteArray) {
        ensureOpen()
        CallbackArena.withCompletion { arena, callback ->
            InternalWorker.completeWorkflowActivation(handle, arena, runtimePtr, completion, callback)
        }
    }

    /**
     * Completes an activity task.
     *
     * @param completion The completion protobuf bytes
     * @throws TemporalCoreException if completion fails
     */
    suspend fun completeActivityTask(completion: ByteArray) {
        ensureOpen()
        CallbackArena.withCompletion { arena, callback ->
            InternalWorker.completeActivityTask(handle, arena, runtimePtr, completion, callback)
        }
    }

    /**
     * Initiates graceful shutdown of the worker.
     *
     * After calling this method, poll methods will return null once all
     * pending work is complete. Call [awaitShutdown] to wait for full shutdown.
     */
    fun initiateShutdown() {
        if (shutdownInitiated || closed) return
        synchronized(this) {
            if (shutdownInitiated || closed) return
            shutdownInitiated = true
            InternalWorker.initiateShutdown(handle)
        }
    }

    /**
     * Waits for the worker to fully shut down.
     *
     * This should be called after [initiateShutdown] and after all poll
     * methods have returned null.
     *
     * @throws TemporalCoreException if shutdown fails
     */
    suspend fun awaitShutdown() {
        CallbackArena.withCompletion { arena, callback ->
            InternalWorker.finalizeShutdown(handle, arena, runtimePtr, callback)
        }
    }

    /**
     * Closes this worker and releases all associated resources.
     *
     * Note: You should call [initiateShutdown] and [awaitShutdown] before
     * calling close to ensure graceful shutdown.
     */
    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            InternalWorker.freeWorker(handle)
            arena.close()
            callbackArena.close()
        }
    }
}
