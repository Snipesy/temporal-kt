package com.surrealdev.temporal.core.internal

import org.slf4j.LoggerFactory
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe container for pending callbacks awaiting dispatch from native code.
 *
 * Uses the zero-allocation pattern where the context ID is passed directly as the
 * user_data pointer value (via MemorySegment.ofAddress), rather than allocating
 * memory to store it.
 *
 * Arena lifecycle is NOT managed here - callers are responsible for closing arenas
 * after the suspend completes (via ManagedArena or similar patterns). This avoids
 * FFM "session acquired" errors that occur when closing arenas during upcalls.
 *
 * Note: Callbacks are NOT cancellable. For worker poll operations, the Rust Core SDK
 * guarantees callbacks fire (sending null on shutdown via PollError::ShutDown). For
 * RPC calls, callbacks fire when the gRPC call completes, but may not fire if the
 * spawned Tokio task panics or the RPC hangs indefinitely. The awaitEmpty() method
 * uses a timeout to prevent infinite hangs in such cases.
 *
 * Thread Safety:
 * - Registration uses AtomicLong for ID generation and ConcurrentHashMap for storage
 * - Dispatch uses ConcurrentHashMap.remove() for atomic get-and-remove
 *
 * @param T The callback type
 */
internal class PendingCallbacks<T> {
    private val nextContextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, T & Any>()

    @Volatile
    private var completionLatch: CountDownLatch? = null
    private val latchLock = Any()

    private val logger = LoggerFactory.getLogger(PendingCallbacks::class.java)

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
     * Returns null if the callback was already dispatched.
     *
     * @param contextId The context ID (from userDataPtr.address())
     * @return The callback, or null if not found
     */
    fun remove(contextId: Long): T? {
        val callback = pending.remove(contextId) ?: return null
        checkAndSignalEmpty()
        return callback
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
     * Blocks until all pending callbacks have been dispatched, or until timeout.
     *
     * This is used during shutdown to ensure all native callbacks complete
     * before freeing the native handle they reference.
     *
     * While worker poll operations are guaranteed to receive null callbacks
     * on shutdown (via PollError::ShutDown), RPC calls have no such guarantee. If a
     * spawned Tokio task panics or an RPC has no timeout and the server is unresponsive,
     * the callback may never fire. This timeout prevents infinite hangs in such cases.
     *
     * @param timeoutSeconds Timeout in seconds (default 60s, longer than typical gRPC timeouts)
     * @return true if all callbacks completed, false if timeout was reached
     */
    fun awaitEmpty(timeoutSeconds: Long = 60): Boolean {
        if (pending.isEmpty()) return true
        synchronized(latchLock) {
            if (pending.isEmpty()) return true
            completionLatch = CountDownLatch(1)
        }
        try {
            val completed = completionLatch!!.await(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                logger.warn(
                    "[PendingCallbacks] Timeout after {}s waiting for {} callbacks. " +
                        "This may indicate a Rust panic or stuck gRPC call.",
                    timeoutSeconds,
                    pending.size,
                )
            }
            return completed
        } finally {
            synchronized(latchLock) { completionLatch = null }
        }
    }

    /**
     * Signals the completion latch if the pending map is empty.
     * Called after removing entries from the map.
     */
    private fun checkAndSignalEmpty() {
        if (pending.isEmpty()) {
            synchronized(latchLock) {
                completionLatch?.countDown()
            }
        }
    }

    /**
     * Clears all pending callbacks.
     */
    fun clear() {
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
