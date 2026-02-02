package com.surrealdev.temporal.core.internal

import java.lang.foreign.Arena
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
 * Supports optional arena lifecycle management: when an arena is registered with a callback,
 * it will be automatically closed when the callback is dispatched, cancelled, or cleared.
 * This enables per-call arena allocation for FFI options structs without memory leaks.
 *
 * Thread Safety:
 * - Registration uses AtomicLong for ID generation and ConcurrentHashMap for storage
 * - Dispatch uses ConcurrentHashMap.remove() for atomic get-and-remove
 * - Cancel vs dispatch races are safe: exactly one wins, the other gets null
 * - Arena close is called exactly once by whichever operation removes the entry
 *
 * @param T The callback type
 */
internal class PendingCallbacks<T> {
    /**
     * Entry holding a callback and its optional associated arena.
     */
    private data class Entry<T>(
        val callback: T,
        val arena: Arena?,
    )

    private val nextContextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Entry<T & Any>>()

    /**
     * Registers a callback and returns a context pointer to pass as user_data.
     *
     * The returned MemorySegment's address IS the context ID (zero allocation).
     *
     * @param callback The callback to register
     * @param arena Optional arena to close when this callback completes or is cancelled
     * @return A MemorySegment to pass as user_data to FFI
     */
    fun register(
        callback: T,
        arena: Arena? = null,
    ): MemorySegment {
        val contextId = nextContextId.getAndIncrement()
        pending[contextId] = Entry(callback!!, arena)
        return MemorySegment.ofAddress(contextId)
    }

    /**
     * Removes and returns the callback for the given context ID.
     *
     * This is called from the native callback stub to dispatch to the correct callback.
     * Returns null if the callback was already dispatched or cancelled.
     *
     * If an arena was registered with the callback, it will be closed before returning.
     *
     * @param contextId The context ID (from userDataPtr.address())
     * @return The callback, or null if not found
     */
    fun remove(contextId: Long): T? {
        val entry = pending.remove(contextId) ?: return null
        entry.arena?.close()
        return entry.callback
    }

    /**
     * Cancels a pending callback.
     *
     * If an arena was registered with the callback, it will be closed.
     *
     * @param contextId The context ID
     * @return true if the callback was found and removed, false if already dispatched/cancelled
     */
    fun cancel(contextId: Long): Boolean {
        val entry = pending.remove(contextId) ?: return false
        entry.arena?.close()
        return true
    }

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
     * Clears all pending callbacks and closes any associated arenas.
     */
    fun clear() {
        // Close all arenas before clearing
        pending.values.forEach { it.arena?.close() }
        pending.clear()
    }

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
