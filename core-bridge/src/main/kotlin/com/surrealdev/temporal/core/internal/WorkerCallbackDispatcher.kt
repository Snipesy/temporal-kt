package com.surrealdev.temporal.core.internal

import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
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
 * All poll callbacks use zero-copy protobuf parsing directly from native memory.
 *
 * Arena lifecycle is managed by the caller (via ManagedArena), not by this dispatcher.
 *
 * @param arena The arena for allocating the reusable stubs (typically worker's callbackArena)
 * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
 */
internal class WorkerCallbackDispatcher(
    arena: Arena,
    runtimePtr: MemorySegment,
) : BaseCallbackDispatcher(runtimePtr) {
    // Use type-erased wrapper to store different message types in the same map
    private val pendingPollCallbacks = PendingCallbacks<TemporalCoreFfmUtil.TypedCallbackWrapper<*>>()
    private val pendingWorkerCallbacks = PendingCallbacks<TemporalCoreWorker.WorkerCallback>()

    private val logger = LoggerFactory.getLogger(WorkerCallbackDispatcher::class.java)

    /**
     * Single reusable stub for poll operations (workflow activation, activity task, nexus task).
     * Dispatches to the correct callback based on context ID in user_data.
     * Uses zero-copy protobuf parsing directly from native memory.
     */
    val pollCallbackStub: MemorySegment =
        CallbackStubFactory.createPollCallbackStub(arena, pendingPollCallbacks, runtimePtr)

    /**
     * Single reusable stub for worker operations (complete, validate, shutdown).
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val workerCallbackStub: MemorySegment =
        CallbackStubFactory.createWorkerCallbackStub(arena, pendingWorkerCallbacks, runtimePtr)

    /**
     * Registers a typed poll callback with zero-copy protobuf parsing.
     * The parser is invoked directly on native memory without intermediate ByteArray copy.
     *
     * @param callback The typed callback to invoke when the poll completes
     * @param parser Function that parses the CodedInputStream into the message type
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun <T : MessageLite> registerPoll(
        callback: TemporalCoreFfmUtil.TypedCallback<T>,
        parser: (CodedInputStream) -> T,
    ): MemorySegment = pendingPollCallbacks.register(TemporalCoreFfmUtil.TypedCallbackWrapper(callback, parser))

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
     * Dumps the current state of pending callbacks for debugging.
     */
    fun dumpPendingCallbacks() {
        logger.trace("[CallbackDispatcher] Pending poll callbacks: {}", pendingPollCallbacks.keys)
        logger.trace("[CallbackDispatcher] Pending worker callbacks: {}", pendingWorkerCallbacks.keys)
    }

    /**
     * Blocks until all pending callbacks have been dispatched, or until timeout.
     *
     * This must be called BEFORE freeing the native worker handle to ensure
     * all Tokio tasks holding references to the worker have completed.
     *
     * @param timeoutSeconds Timeout in seconds (default 60s)
     * @return true if all callbacks completed, false if timeout was reached
     */
    fun awaitPendingCallbacks(timeoutSeconds: Long = 60): Boolean {
        val pollCompleted = pendingPollCallbacks.awaitEmpty(timeoutSeconds)
        val workerCompleted = pendingWorkerCallbacks.awaitEmpty(timeoutSeconds)
        return pollCompleted && workerCompleted
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
