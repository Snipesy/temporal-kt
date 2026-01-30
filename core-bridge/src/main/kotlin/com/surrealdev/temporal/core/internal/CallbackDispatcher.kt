package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreWorkerCallback
import io.temporal.sdkbridge.TemporalCoreWorkerPollCallback
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

/**
 * Manages reusable callback stubs for FFM worker operations.
 *
 * Instead of creating a new upcall stub for each poll/complete operation,
 * this dispatcher creates two reusable stubs (one for poll callbacks, one for
 * worker callbacks) and uses the user_data pointer to dispatch to the correct
 * Kotlin callback.
 *
 * Thread Safety:
 * - Registration uses AtomicLong for ID generation and ConcurrentHashMap for storage
 * - Dispatch uses ConcurrentHashMap.remove() for atomic get-and-remove
 * - Cancel vs dispatch races are safe: exactly one wins, the other gets null
 *
 * @param arena The arena for allocating the reusable stubs (typically worker's callbackArena)
 * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
 */
internal class CallbackDispatcher(
    arena: Arena,
    private val runtimePtr: MemorySegment,
) : AutoCloseable {
    private val nextContextId = AtomicLong(1)
    private val pendingPollCallbacks = ConcurrentHashMap<Long, TemporalCoreWorker.PollCallback>()
    private val pendingWorkerCallbacks = ConcurrentHashMap<Long, TemporalCoreWorker.WorkerCallback>()

    // Arena for allocating context pointers (8 bytes each)
    private val contextArena = Arena.ofShared()

    private val logger = LoggerFactory.getLogger(CallbackDispatcher::class.java)

    /**
     * Single reusable stub for poll operations (workflow activation, activity task, nexus task).
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val pollCallbackStub: MemorySegment =
        TemporalCoreWorkerPollCallback.allocate(
            { userDataPtr, successPtr, failPtr ->
                val contextId = userDataPtr.get(ValueLayout.JAVA_LONG, 0)
                val callback = pendingPollCallbacks.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    freeByteArrayIfNotNull(successPtr)
                    freeByteArrayIfNotNull(failPtr)
                    return@allocate
                }

                val data =
                    if (successPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArrayAsBytes(successPtr).also {
                            CoreBridge.temporal_core_byte_array_free(runtimePtr, successPtr)
                        }
                    } else {
                        null
                    }

                val error =
                    if (failPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArray(failPtr).also {
                            CoreBridge.temporal_core_byte_array_free(runtimePtr, failPtr)
                        }
                    } else {
                        null
                    }

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
                val contextId = userDataPtr.get(ValueLayout.JAVA_LONG, 0)
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
                    freeByteArrayIfNotNull(failPtr)
                    return@allocate
                }

                val error =
                    if (failPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArray(failPtr).also {
                            CoreBridge.temporal_core_byte_array_free(runtimePtr, failPtr)
                        }
                    } else {
                        null
                    }

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
    fun registerPoll(callback: TemporalCoreWorker.PollCallback): MemorySegment {
        val contextId = nextContextId.getAndIncrement()
        pendingPollCallbacks[contextId] = callback
        return allocateContextPointer(contextId)
    }

    /**
     * Registers a worker callback and returns a context pointer to pass as user_data.
     *
     * @param callback The callback to invoke when the operation completes
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun registerWorker(callback: TemporalCoreWorker.WorkerCallback): MemorySegment {
        val contextId = nextContextId.getAndIncrement()
        pendingWorkerCallbacks[contextId] = callback
        val total = pendingWorkerCallbacks.size
        logger.trace("[CallbackDispatcher] Worker callback registered: contextId={}, total={}", contextId, total)
        return allocateContextPointer(contextId)
    }

    /**
     * Extracts the context ID from a context pointer.
     *
     * @param contextPtr The context pointer returned from registerPoll/registerWorker
     * @return The context ID
     */
    fun getContextId(contextPtr: MemorySegment): Long = contextPtr.get(ValueLayout.JAVA_LONG, 0)

    /**
     * Cancels a pending poll callback. Used when a coroutine is cancelled.
     *
     * @param contextId The context ID from getContextId()
     * @return true if the callback was found and removed, false if already dispatched/canceled
     */
    fun cancelPoll(contextId: Long): Boolean = pendingPollCallbacks.remove(contextId) != null

    /**
     * Cancels a pending worker callback. Used when a coroutine is cancelled.
     *
     * @param contextId The context ID from getContextId()
     * @return true if the callback was found and removed, false if already dispatched/canceled
     */
    fun cancelWorker(contextId: Long): Boolean = pendingWorkerCallbacks.remove(contextId) != null

    private fun allocateContextPointer(contextId: Long): MemorySegment {
        val segment = contextArena.allocate(ValueLayout.JAVA_LONG)
        segment.set(ValueLayout.JAVA_LONG, 0, contextId)
        return segment
    }

    private fun freeByteArrayIfNotNull(ptr: MemorySegment) {
        if (ptr != MemorySegment.NULL) {
            CoreBridge.temporal_core_byte_array_free(runtimePtr, ptr)
        }
    }

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
        contextArena.close()
        // Note: The main arena (holding the stubs) is managed externally by TemporalWorker
    }
}
