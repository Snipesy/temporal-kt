package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreWorkerCallback
import io.temporal.sdkbridge.TemporalCoreWorkerPollCallback
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Manages reusable callback stubs for FFM worker operations.
 *
 * Instead of creating a new upcall stub for each poll/complete operation,
 * this dispatcher creates two reusable stubs (one for poll callbacks, one for
 * worker callbacks) and uses the user_data pointer to dispatch to the correct
 * Kotlin callback.
 *
 * @param arena The arena for allocating the reusable stubs (typically worker's callbackArena)
 * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
 */
internal class WorkerCallbackDispatcher(
    arena: Arena,
    private val runtimePtr: MemorySegment,
) : AutoCloseable {
    private val pendingPollCallbacks = PendingCallbacks<TemporalCoreWorker.PollCallback>()
    private val pendingWorkerCallbacks = PendingCallbacks<TemporalCoreWorker.WorkerCallback>()

    private val logger = LoggerFactory.getLogger(WorkerCallbackDispatcher::class.java)

    /**
     * Single reusable stub for poll operations (workflow activation, activity task, nexus task).
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val pollCallbackStub: MemorySegment =
        TemporalCoreWorkerPollCallback.allocate(
            { userDataPtr, successPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pendingPollCallbacks.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, successPtr)
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failPtr)
                    return@allocate
                }

                val data = TemporalCoreFfmUtil.readAndFreeByteArrayAsBytes(runtimePtr, successPtr)
                val error = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, failPtr)
                callback.onComplete(data, error)
            },
            arena,
        )

    /**
     * Single reusable stub for worker operations (complete, validate, shutdown).
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val workerCallbackStub: MemorySegment =
        TemporalCoreWorkerCallback.allocate(
            { userDataPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pendingWorkerCallbacks.remove(contextId)
                val remaining = pendingWorkerCallbacks.size
                logger.trace(
                    "[CallbackDispatcher] Worker callback invoked: contextId={}, found={}, remaining={}",
                    contextId,
                    callback != null,
                    remaining,
                )

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failPtr)
                    return@allocate
                }

                val error = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, failPtr)
                callback.onComplete(error)
            },
            arena,
        )

    /**
     * Registers a poll callback and returns a context pointer to pass as user_data.
     *
     * @param callback The callback to invoke when the poll completes
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun registerPoll(callback: TemporalCoreWorker.PollCallback): MemorySegment = pendingPollCallbacks.register(callback)

    /**
     * Registers a worker callback and returns a context pointer to pass as user_data.
     *
     * @param callback The callback to invoke when the operation completes
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun registerWorker(callback: TemporalCoreWorker.WorkerCallback): MemorySegment {
        val contextPtr = pendingWorkerCallbacks.register(callback)
        logger.trace(
            "[CallbackDispatcher] Worker callback registered: contextId={}, total={}",
            PendingCallbacks.getContextId(contextPtr),
            pendingWorkerCallbacks.size,
        )
        return contextPtr
    }

    /**
     * Extracts the context ID from a context pointer.
     *
     * @param contextPtr The context pointer returned from registerPoll/registerWorker
     * @return The context ID
     */
    fun getContextId(contextPtr: MemorySegment): Long = PendingCallbacks.getContextId(contextPtr)

    /**
     * Cancels a pending poll callback. Used when a coroutine is cancelled.
     *
     * @param contextId The context ID from getContextId()
     * @return true if the callback was found and removed, false if already dispatched/canceled
     */
    fun cancelPoll(contextId: Long): Boolean = pendingPollCallbacks.cancel(contextId)

    /**
     * Cancels a pending worker callback. Used when a coroutine is cancelled.
     *
     * @param contextId The context ID from getContextId()
     * @return true if the callback was found and removed, false if already dispatched/canceled
     */
    fun cancelWorker(contextId: Long): Boolean = pendingWorkerCallbacks.cancel(contextId)

    /**
     * Dumps the current state of pending callbacks for debugging.
     */
    fun dumpPendingCallbacks() {
        logger.trace("[CallbackDispatcher] Pending poll callbacks: {}", pendingPollCallbacks.keys)
        logger.trace("[CallbackDispatcher] Pending worker callbacks: {}", pendingWorkerCallbacks.keys)
    }

    override fun close() {
        // Dump state before clearing
        if (pendingPollCallbacks.isNotEmpty() || pendingWorkerCallbacks.isNotEmpty()) {
            logger.trace("[CallbackDispatcher] WARNING: Closing with pending callbacks!")
            dumpPendingCallbacks()
        }
        // Clear any pending callbacks (they will never be invoked)
        pendingPollCallbacks.clear()
        pendingWorkerCallbacks.clear()
        // Note: The main arena (holding the stubs) is managed externally by TemporalWorker
    }
}
