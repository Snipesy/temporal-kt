package com.surrealdev.temporal.core

import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import com.surrealdev.temporal.core.internal.WorkerCallbackDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.surrealdev.temporal.core.internal.TemporalCoreWorker as InternalWorker

/**
 * Identifies a worker deployment version for the core bridge.
 *
 * @property deploymentName Name of the deployment (e.g., "llm_srv", "payment-service")
 * @property buildId Build ID within the deployment (e.g., "1.0", "v2.3.5")
 */
data class CoreWorkerDeploymentVersion(
    val deploymentName: String,
    val buildId: String,
)

/**
 * Deployment options for worker versioning in the core bridge.
 *
 * @property version The deployment version identifying this worker
 * @property useWorkerVersioning If true, worker participates in versioned task routing
 * @property defaultVersioningBehavior Default versioning behavior value (0=UNSPECIFIED, 1=PINNED, 2=AUTO_UPGRADE)
 */
data class CoreWorkerDeploymentOptions(
    val version: CoreWorkerDeploymentVersion,
    val useWorkerVersioning: Boolean = true,
    val defaultVersioningBehavior: Int = 0,
)

/**
 * Configuration options for a Temporal worker.
 */
data class WorkerConfig(
    val maxCachedWorkflows: Int = 1000,
    val enableWorkflows: Boolean = true,
    val enableActivities: Boolean = true,
    val enableNexus: Boolean = false,
    val deploymentOptions: CoreWorkerDeploymentOptions? = null,
    /**
     * Maximum number of concurrent workflow task executions.
     * Controls the Core SDK's workflow slot supplier.
     */
    val maxConcurrentWorkflowTasks: Int = 100,
    /**
     * Maximum number of concurrent activity executions.
     * Controls the Core SDK's activity slot supplier.
     */
    val maxConcurrentActivities: Int = 100,
    /**
     * Maximum interval for throttling activity heartbeats in milliseconds.
     * Heartbeats will be throttled to at most this interval.
     */
    val maxHeartbeatThrottleIntervalMs: Long = 60_000L,
    /**
     * Default interval for throttling activity heartbeats in milliseconds.
     * Used when no heartbeat timeout is set. When a heartbeat timeout is configured,
     * throttling uses 80% of that timeout instead.
     */
    val defaultHeartbeatThrottleIntervalMs: Long = 30_000L,
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
    private val arena: Arena,
    private val callbackArena: Arena,
    private val dispatcher: WorkerCallbackDispatcher,
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
            val dispatcher = WorkerCallbackDispatcher(callbackArena, runtime.handle)

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
                        deploymentOptions = config.deploymentOptions,
                        maxConcurrentWorkflowTasks = config.maxConcurrentWorkflowTasks,
                        maxConcurrentActivities = config.maxConcurrentActivities,
                        maxHeartbeatThrottleIntervalMs = config.maxHeartbeatThrottleIntervalMs,
                        defaultHeartbeatThrottleIntervalMs = config.defaultHeartbeatThrottleIntervalMs,
                    )
                TemporalWorker(
                    handle = workerPtr,
                    arena = arena,
                    callbackArena = callbackArena,
                    dispatcher = dispatcher,
                    taskQueue = taskQueue,
                    namespace = namespace,
                )
            } catch (e: Exception) {
                dispatcher.close()
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
     * Polls for a workflow activation with zero-copy protobuf parsing.
     *
     * This method suspends until a workflow activation is available or shutdown is complete.
     * The protobuf message is parsed directly from native memory without intermediate ByteArray copy.
     *
     * @param parser Function that parses the CodedInputStream into the message type
     * @return The parsed workflow activation, or null if shutdown is complete
     * @throws TemporalCoreException if polling fails
     */
    suspend fun <T : MessageLite> pollWorkflowActivation(parser: (CodedInputStream) -> T): T? {
        ensureOpen()
        return try {
            suspendCancellableCoroutine { continuation ->
                val callback =
                    com.surrealdev.temporal.core.internal.TemporalCoreFfmUtil.TypedCallback<T> { data, error ->
                        when {
                            error != null -> continuation.resumeWithException(TemporalCoreException(error))
                            else -> continuation.resume(data)
                        }
                    }
                val contextPtr = InternalWorker.pollWorkflowActivation(handle, dispatcher, callback, parser)
                val contextId = dispatcher.getContextId(contextPtr)
                continuation.invokeOnCancellation {
                    dispatcher.cancelPoll(contextId)
                }
            }
        } catch (e: TemporalCoreException) {
            // Treat shutdown errors as normal completion
            if (e.message?.contains("shutdown", ignoreCase = true) == true) null else throw e
        }
    }

    /**
     * Polls for an activity task with zero-copy protobuf parsing.
     *
     * This method suspends until an activity task is available or shutdown is complete.
     * The protobuf message is parsed directly from native memory without intermediate ByteArray copy.
     *
     * @param parser Function that parses the CodedInputStream into the message type
     * @return The parsed activity task, or null if shutdown is complete
     * @throws TemporalCoreException if polling fails
     */
    suspend fun <T : MessageLite> pollActivityTask(parser: (CodedInputStream) -> T): T? {
        ensureOpen()
        return try {
            suspendCancellableCoroutine { continuation ->
                val callback =
                    com.surrealdev.temporal.core.internal.TemporalCoreFfmUtil.TypedCallback<T> { data, error ->
                        when {
                            error != null -> continuation.resumeWithException(TemporalCoreException(error))
                            else -> continuation.resume(data)
                        }
                    }
                val contextPtr = InternalWorker.pollActivityTask(handle, dispatcher, callback, parser)
                val contextId = dispatcher.getContextId(contextPtr)
                continuation.invokeOnCancellation {
                    dispatcher.cancelPoll(contextId)
                }
            }
        } catch (e: TemporalCoreException) {
            // Treat shutdown errors as normal completion
            if (e.message?.contains("shutdown", ignoreCase = true) == true) null else throw e
        }
    }

    /**
     * Completes a workflow activation.
     *
     * Uses zero-copy serialization: the protobuf message is serialized directly
     * to native memory without intermediate ByteArray allocation.
     *
     * @param completion The completion protobuf message
     * @throws TemporalCoreException if completion fails
     */
    suspend fun <T : MessageLite> completeWorkflowActivation(completion: T) {
        ensureOpen()
        // Per-call arena for data - closed by dispatcher when callback fires or is cancelled
        val dataArena = Arena.ofShared()
        suspendCancellableCoroutine { continuation ->
            val callback =
                InternalWorker.WorkerCallback { error ->
                    if (error != null) {
                        continuation.resumeWithException(TemporalCoreException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            val contextPtr =
                InternalWorker.completeWorkflowActivation(
                    handle,
                    dataArena,
                    dispatcher,
                    completion,
                    callback,
                )
            val contextId = dispatcher.getContextId(contextPtr)
            continuation.invokeOnCancellation {
                dispatcher.cancelWorker(contextId)
            }
        }
    }

    /**
     * Completes an activity task.
     *
     * Uses zero-copy serialization: the protobuf message is serialized directly
     * to native memory without intermediate ByteArray allocation.
     *
     * @param completion The completion protobuf message
     * @throws TemporalCoreException if completion fails
     */
    suspend fun <T : MessageLite> completeActivityTask(completion: T) {
        ensureOpen()
        // Per-call arena for data - closed by dispatcher when callback fires or is cancelled
        val dataArena = Arena.ofShared()
        suspendCancellableCoroutine { continuation ->
            val callback =
                InternalWorker.WorkerCallback { error ->
                    if (error != null) {
                        continuation.resumeWithException(TemporalCoreException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            val contextPtr =
                InternalWorker.completeActivityTask(
                    handle,
                    dataArena,
                    dispatcher,
                    completion,
                    callback,
                )
            val contextId = dispatcher.getContextId(contextPtr)
            continuation.invokeOnCancellation {
                dispatcher.cancelWorker(contextId)
            }
        }
    }

    /**
     * Records an activity heartbeat.
     *
     * This is a synchronous operation because the Core SDK handles heartbeat
     * batching internally. The heartbeat is queued and sent to the server
     * asynchronously by the Core SDK.
     *
     * Uses zero-copy serialization: the protobuf message is serialized directly
     * to native memory without intermediate ByteArray allocation.
     *
     * If cancellation is requested, the Core SDK will send a Cancel task
     * through the normal [pollActivityTask] mechanism.
     *
     * @param heartbeat The heartbeat protobuf message (ActivityHeartbeat)
     * @throws TemporalCoreException if recording fails
     */
    fun <T : MessageLite> recordActivityHeartbeat(heartbeat: T) {
        ensureOpen()
        Arena.ofConfined().use { arena ->
            val error = InternalWorker.recordActivityHeartbeat(handle, arena, heartbeat)
            if (error != null) {
                throw TemporalCoreException("Failed to record activity heartbeat: $error")
            }
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
     * Uses reusable callback stubs for better performance.
     *
     * This should be called after [initiateShutdown] and after all poll
     * methods have returned null.
     *
     * @throws TemporalCoreException if shutdown fails
     */
    suspend fun awaitShutdown() {
        suspendCancellableCoroutine { continuation ->
            val callback =
                InternalWorker.WorkerCallback { error ->
                    if (error != null) {
                        continuation.resumeWithException(TemporalCoreException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            val contextPtr = InternalWorker.finalizeShutdown(handle, dispatcher, callback)
            val contextId = dispatcher.getContextId(contextPtr)
            continuation.invokeOnCancellation {
                dispatcher.cancelWorker(contextId)
            }
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
            dispatcher.close()
            arena.close()
            callbackArena.close()
        }
    }
}
