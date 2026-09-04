package com.surrealdev.temporal.core

import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import com.surrealdev.temporal.core.internal.FactoryArenaScope
import com.surrealdev.temporal.core.internal.WorkerCallbackDispatcher
import com.surrealdev.temporal.core.internal.nativeCallbackException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.surrealdev.temporal.core.internal.TemporalCoreWorker as InternalWorker

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
    val slotSupplierBridges: List<SlotSupplierBridgeEntry> = emptyList(),
) : AutoCloseable {
    @Volatile
    private var closed = false

    @Volatile
    private var shutdownInitiated = false

    /**
     * True once [awaitShutdown] has run: Core has taken the native worker out of the handle, so
     * data-path calls (heartbeats) have nothing to act on. Checked before calling into the
     * bridge so a late heartbeat fails with an exception instead of reaching a finalized worker.
     */
    @Volatile
    private var shutdownFinalized = false

    /**
     * Serialises synchronous data-path downcalls (heartbeats, shutdown initiation) against
     * [close] freeing the native handle. A zombie activity thread that ignores interruption can
     * still heartbeat after the worker was closed; without this it could race `freeWorker` and
     * touch freed memory. Readers never block each other; close() takes the write side.
     */
    private val handleLock = ReentrantReadWriteLock()

    private val logger = LoggerFactory.getLogger(TemporalWorker::class.java)

    companion object {
        /**
         * Error text the C bridge returns for calls made after `finalize_shutdown` took the Core
         * worker. Must stay in sync with `WORKER_SHUT_DOWN` in sdk-core-c-bridge `worker.rs`
         * (temporal-kt fork patch).
         */
        internal const val WORKER_SHUT_DOWN = "Worker already shut down"

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

            return FactoryArenaScope.create(runtime.handle, ::WorkerCallbackDispatcher).createResource {
                val result =
                    InternalWorker.createWorker(
                        clientPtr = client.handle,
                        arena = resourceArena,
                        namespace = namespace,
                        taskQueue = taskQueue,
                        config = config,
                    )
                TemporalWorker(
                    handle = result.workerPtr,
                    arena = resourceArena,
                    callbackArena = callbackArena,
                    dispatcher = dispatcher,
                    taskQueue = taskQueue,
                    namespace = namespace,
                    slotSupplierBridges = result.slotSupplierBridges,
                )
            }
        }
    }

    /**
     * Whether a poll failure means "polling is over" rather than an error: Core's own shutdown
     * signal, or the bridge refusing a call because the worker was already finalized
     * ([WORKER_SHUT_DOWN], returned by every fork-patched entry point after finalize_shutdown).
     * Poll loops treat these as a normal end (null) so they never spin on the exception.
     */
    private fun isShutdownError(e: Throwable): Boolean {
        if (shutdownFinalized) return true
        val message = e.message ?: return false
        return message.contains("shutdown", ignoreCase = true) ||
            message.contains(WORKER_SHUT_DOWN, ignoreCase = true)
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
     * Checks if [awaitShutdown] has completed and the native worker has been finalized.
     */
    fun isShutdownFinalized(): Boolean = shutdownFinalized

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
     * Validates this worker against the Temporal server.
     * Should be called after worker creation but before polling starts.
     *
     * @throws TemporalCoreException if validation fails
     */
    suspend fun validate() {
        ensureOpen()
        suspendCancellableCoroutine { continuation ->
            val callback =
                InternalWorker.WorkerCallback { error ->
                    if (error != null) {
                        continuation.resumeWithException(nativeCallbackException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            InternalWorker.validate(handle, dispatcher, callback)
            // Note: We intentionally do NOT cancel on coroutine cancellation.
            // The Rust callback will always fire, and we must wait for it to complete.
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
                            error != null -> continuation.resumeWithException(nativeCallbackException(error))
                            else -> continuation.resume(data)
                        }
                    }
                InternalWorker.pollWorkflowActivation(handle, dispatcher, callback, parser)
                // Note: We intentionally do NOT cancel on coroutine cancellation.
                // The Rust callback will always fire (even on shutdown), and awaitPendingCallbacks()
                // must wait for it to ensure Arc references are released before finalize_shutdown.
            }
        } catch (e: TemporalCoreException) {
            // Treat shutdown errors as normal completion
            if (isShutdownError(e)) null else throw e
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
                            error != null -> continuation.resumeWithException(nativeCallbackException(error))
                            else -> continuation.resume(data)
                        }
                    }
                InternalWorker.pollActivityTask(handle, dispatcher, callback, parser)
                // Note: We intentionally do NOT cancel on coroutine cancellation.
                // The Rust callback will always fire (even on shutdown), and awaitPendingCallbacks()
                // must wait for it to ensure Arc references are released before finalize_shutdown.
            }
        } catch (e: TemporalCoreException) {
            // Treat shutdown errors as normal completion
            if (isShutdownError(e)) null else throw e
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
        dispatcher.withManagedArena { arena, continuation ->
            val callback =
                InternalWorker.WorkerCallback { error ->
                    with(dispatcher) { continuation.resumeWorkerResult(error) }
                }
            InternalWorker.completeWorkflowActivation(
                handle,
                arena,
                dispatcher,
                completion,
                callback,
            )
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
        dispatcher.withManagedArena { arena, continuation ->
            val callback =
                InternalWorker.WorkerCallback { error ->
                    with(dispatcher) { continuation.resumeWorkerResult(error) }
                }
            InternalWorker.completeActivityTask(
                handle,
                arena,
                dispatcher,
                completion,
                callback,
            )
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
        handleLock.read {
            // Checked under the lock: close() flips `closed` and frees the handle under the write lock
            ensureOpen()
            if (shutdownFinalized) {
                throw TemporalCoreException("Failed to record activity heartbeat: worker already shut down")
            }
            Arena.ofConfined().use { arena ->
                val error = InternalWorker.recordActivityHeartbeat(handle, arena, heartbeat)
                if (error != null) {
                    throw TemporalCoreException("Failed to record activity heartbeat: $error")
                }
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
        handleLock.read {
            synchronized(this) {
                if (shutdownInitiated || closed) return
                shutdownInitiated = true
                InternalWorker.initiateShutdown(handle)
            }
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
        try {
            suspendCancellableCoroutine { continuation ->
                val callback =
                    InternalWorker.WorkerCallback { error ->
                        if (error != null) {
                            continuation.resumeWithException(nativeCallbackException(error))
                        } else {
                            continuation.resume(Unit)
                        }
                    }
                InternalWorker.finalizeShutdown(handle, dispatcher, callback)
                // Note: We intentionally do NOT cancel on coroutine cancellation.
                // The Rust callback will always fire, and we must wait for it to complete.
            }
        } finally {
            // finalize_shutdown takes the native worker whether or not it then succeeds
            shutdownFinalized = true
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

            // MUST await BEFORE freeing - Tokio tasks hold &Worker references to this Box
            val completed = dispatcher.awaitPendingCallbacks(timeoutSeconds = 60)
            if (!completed) {
                logger.warn(
                    "[TemporalWorker] Timeout waiting for pending callbacks during close(). " +
                        "Proceeding with cleanup anyway. This may indicate a Rust panic or stuck poll.",
                )
            }

            // Exclude in-flight synchronous downcalls (heartbeats from a zombie thread) while the
            // handle is freed; anything arriving afterwards sees `closed` and never reaches native.
            handleLock.write {
                closed = true
                // NOW safe to free - no more callbacks will reference the Worker (or as safe as we can make it)
                InternalWorker.freeWorker(handle)
            }

            dispatcher.close()
            arena.close()
            callbackArena.close()
        }
    }
}
