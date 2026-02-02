package com.surrealdev.temporal.core.internal

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.foreign.Arena
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class to manage arena lifecycle with atomic close-once semantics.
 */
internal class ManagedArena {
    val arena: Arena = Arena.ofShared()
    private val closed = AtomicBoolean(false)

    fun closeOnce() {
        if (closed.compareAndSet(false, true)) {
            arena.close()
        }
    }
}

/**
 * Utilities for managing FFM arenas with suspend callback wrappers.
 */
internal object CallbackArena {
    /**
     * Executes an async native operation with automatic arena lifecycle management.
     *
     * Creates a per-call arena and suspends until the callback fires. The arena is closed
     * AFTER the suspend completes (not during the callback), which avoids issues with FFM
     * holding arena references during upcalls.
     *
     * The block should NOT register the arena with PendingCallbacks. The arena
     * lifecycle is managed entirely by this function.
     *
     * Arena lifecycle:
     * - Normal completion: arena is closed after suspendCancellableCoroutine returns
     * - Cancellation: arena is closed in the cancellation handler
     * - Exception in block: arena is closed immediately before re-throwing
     *
     * @param block Function that registers the callback, makes the native call,
     *              and returns a cancel function to be invoked if the coroutine is cancelled
     * @return The result from the callback
     */
    suspend inline fun <T> withManagedArena(
        crossinline block: (arena: Arena, continuation: CancellableContinuation<T>) -> (() -> Unit),
    ): T {
        val managed = ManagedArena()

        return try {
            suspendCancellableCoroutine { continuation ->
                try {
                    val cancel = block(managed.arena, continuation)
                    continuation.invokeOnCancellation {
                        cancel()
                        managed.closeOnce()
                    }
                } catch (e: Throwable) {
                    // If block throws before registering callback, close arena to prevent leak
                    managed.closeOnce()
                    throw e
                }
            }
        } finally {
            // Close arena after suspend completes - this runs after all callback machinery
            // has fully unwound, avoiding FFM "session acquired" errors
            managed.closeOnce()
        }
    }
}
