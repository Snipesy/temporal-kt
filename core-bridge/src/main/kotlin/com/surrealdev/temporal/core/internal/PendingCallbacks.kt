package com.surrealdev.temporal.core.internal

import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe container for pending callbacks awaiting dispatch from native code.
 *
 * Uses the zero-allocation pattern where the context ID is passed directly as the
 * user_data pointer value (via MemorySegment.ofAddress), rather than allocating
 * memory to store it.
 *
 * Thread Safety:
 * - Registration uses AtomicLong for ID generation and ConcurrentHashMap for storage
 * - Dispatch uses ConcurrentHashMap.remove() for atomic get-and-remove
 * - Cancel vs dispatch races are safe: exactly one wins, the other gets null
 *
 * @param T The callback type
 */
internal class PendingCallbacks<T> {
    private val nextContextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, T & Any>()

    /**
     * Registers a callback and returns a context pointer to pass as user_data.
     *
     * The returned MemorySegment's address IS the context ID (zero allocation).
     *
     * @param callback The callback to register
     * @return A MemorySegment to pass as user_data to FFI
     */
    fun register(callback: T): MemorySegment {
        val contextId = nextContextId.getAndIncrement()
        pending[contextId] = callback!!
        return MemorySegment.ofAddress(contextId)
    }

    /**
     * Removes and returns the callback for the given context ID.
     *
     * This is called from the native callback stub to dispatch to the correct callback.
     * Returns null if the callback was already dispatched or cancelled.
     *
     * @param contextId The context ID (from userDataPtr.address())
     * @return The callback, or null if not found
     */
    fun remove(contextId: Long): T? = pending.remove(contextId)

    /**
     * Cancels a pending callback.
     *
     * @param contextId The context ID
     * @return true if the callback was found and removed, false if already dispatched/cancelled
     */
    fun cancel(contextId: Long): Boolean = pending.remove(contextId) != null

    /**
     * Returns the number of pending callbacks.
     */
    val size: Int get() = pending.size

    /**
     * Returns the set of pending context IDs (for debugging).
     */
    val keys: Set<Long> get() = pending.keys

    /**
     * Returns true if there are pending callbacks.
     */
    fun isNotEmpty(): Boolean = pending.isNotEmpty()

    /**
     * Clears all pending callbacks.
     */
    fun clear() = pending.clear()

    companion object {
        /**
         * Extracts the context ID from a user_data pointer.
         *
         * @param userDataPtr The user_data pointer from the native callback
         * @return The context ID
         */
        fun getContextId(userDataPtr: MemorySegment): Long = userDataPtr.address()
    }
}
