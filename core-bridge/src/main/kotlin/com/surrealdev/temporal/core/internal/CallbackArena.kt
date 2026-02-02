package com.surrealdev.temporal.core.internal

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.foreign.Arena

/**
 * Utilities for managing FFM arenas with suspend callback wrappers.
 */
internal object CallbackArena {
    /**
     * Executes an async native operation with automatic arena lifecycle management.
     *
     * Creates a per-call arena and suspends until the callback fires. The block MUST
     * register the arena with PendingCallbacks so it gets closed when the callback
     * completes or is canceled.
     *
     * Arena lifecycle:
     * - Normal completion: PendingCallbacks.remove() closes the arena
     * - Cancellation: cancel function calls PendingCallbacks.cancel() which closes the arena
     * - Exception in block: arena is closed immediately before re-throwing
     *
     * @param block Function that registers the callback (with arena), makes the native call,
     *              and returns a cancel function to be invoked if the coroutine is cancelled
     * @return The result from the callback
     */
    suspend inline fun <T> withManagedArena(
        crossinline block: (arena: Arena, continuation: CancellableContinuation<T>) -> (() -> Unit),
    ): T =
        suspendCancellableCoroutine { continuation ->
            val arena = Arena.ofShared()
            try {
                val cancel = block(arena, continuation)
                continuation.invokeOnCancellation { cancel() }
            } catch (e: Throwable) {
                // If block throws before registering callback, close arena to prevent leak
                arena.close()
                throw e
            }
        }
}
