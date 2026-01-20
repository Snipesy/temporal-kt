package com.surrealdev.temporal.core.internal

import com.surrealdev.temporal.core.TemporalCoreException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.foreign.Arena
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Utilities for managing FFM arenas with suspend callback wrappers.
 */
internal object CallbackArena {
    /**
     * Executes an async operation with a callback that returns a nullable result.
     *
     * The arena is GC-managed (Arena.ofAuto), so no explicit cleanup is needed.
     * This is suitable for operations that don't transfer arena ownership.
     *
     * WARNING: Not suitable for long-lived async operations where Rust spawns tasks
     * that may complete much later. Use [withExternalArenaResult] for those.
     *
     * @param register Function that registers the callback with the native code.
     *                 The callback receives (result: T?, error: String?).
     * @return The result from the callback
     * @throws TemporalCoreException if the callback reports an error
     */
    suspend inline fun <T> withResult(crossinline register: (Arena, callback: (T?, String?) -> Unit) -> Unit): T? =
        suspendCancellableCoroutine { continuation ->
            val arena = Arena.ofAuto()
            register(arena) { result, error ->
                try {
                    when {
                        error != null -> continuation.resumeWithException(TemporalCoreException(error))
                        else -> continuation.resume(result)
                    }
                } catch (_: IllegalStateException) {
                    // Continuation already resumed, ignore
                }
            }
        }

    /**
     * Executes an async operation with a callback that returns a non-null result.
     *
     * Not suitable for long-lived async operations. Use [withExternalArenaNonNullResult] for those.
     *
     * @param register Function that registers the callback with the native code.
     * @return The non-null result from the callback
     * @throws TemporalCoreException if the callback reports an error or returns null
     */
    suspend inline fun <T : Any> withNonNullResult(
        crossinline register: (Arena, callback: (T?, String?) -> Unit) -> Unit,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val arena = Arena.ofAuto()
            register(arena) { result, error ->
                try {
                    when {
                        error != null -> {
                            continuation.resumeWithException(TemporalCoreException(error))
                        }

                        result != null -> {
                            continuation.resume(result)
                        }

                        else -> {
                            continuation.resumeWithException(
                                TemporalCoreException("Operation returned null without error"),
                            )
                        }
                    }
                } catch (_: IllegalStateException) {
                    // Continuation already resumed, ignore
                }
            }
        }

    /**
     * Executes an async operation with an externally-managed callback arena.
     *
     * This method does NOT close the arena after the callback
     * completes. The caller is responsible for managing the arena lifecycle, typically by
     * closing it when the owning object (Worker, Client) is closed.
     *
     * @param callbackArena An externally-managed arena (typically Arena.ofShared())
     * @param register Function that registers the callback with the native code.
     *                 The callback receives (result: T?, error: String?).
     * @return The result from the callback
     * @throws TemporalCoreException if the callback reports an error
     */
    suspend inline fun <T> withExternalArenaResult(
        callbackArena: Arena,
        crossinline register: (Arena, callback: (T?, String?) -> Unit) -> Unit,
    ): T? =
        suspendCancellableCoroutine { continuation ->
            register(callbackArena) { result, error ->
                try {
                    when {
                        error != null -> continuation.resumeWithException(TemporalCoreException(error))
                        else -> continuation.resume(result)
                    }
                } catch (_: IllegalStateException) {
                    // Continuation already resumed, ignore
                }
                // Arena is NOT closed here - it's managed by the owner
            }
        }

    /**
     * Executes an async operation with an externally-managed callback arena that returns a non-null result.
     *
     * This method does NOT close the arena after the callback
     * completes. The caller is responsible for managing the arena lifecycle, typically by
     * closing it when the owning object (Worker, Client) is closed.
     *
     *
     * @param callbackArena An externally-managed arena (typically Arena.ofShared())
     * @param register Function that registers the callback with the native code.
     * @return The non-null result from the callback
     * @throws TemporalCoreException if the callback reports an error or returns null
     */
    suspend inline fun <T : Any> withExternalArenaNonNullResult(
        callbackArena: Arena,
        crossinline register: (Arena, callback: (T?, String?) -> Unit) -> Unit,
    ): T =
        suspendCancellableCoroutine { continuation ->
            register(callbackArena) { result, error ->
                try {
                    when {
                        error != null -> {
                            continuation.resumeWithException(TemporalCoreException(error))
                        }

                        result != null -> {
                            continuation.resume(result)
                        }

                        else -> {
                            continuation.resumeWithException(
                                TemporalCoreException("Operation returned null without error"),
                            )
                        }
                    }
                } catch (_: IllegalStateException) {
                    // Continuation already resumed, ignore
                }
                // Arena is NOT closed here - it's managed by the owner
            }
        }

    /**
     * Executes an async operation with a callback that only reports success/failure.
     *
     * @param register Function that registers the callback with the native code.
     *                 The callback receives (error: String?).
     * @throws TemporalCoreException if the callback reports an error
     */
    suspend inline fun withCompletion(crossinline register: (Arena, callback: (String?) -> Unit) -> Unit): Unit =
        suspendCancellableCoroutine { continuation ->
            val arena = Arena.ofAuto()
            register(arena) { error ->
                try {
                    if (error != null) {
                        continuation.resumeWithException(TemporalCoreException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                } catch (_: IllegalStateException) {
                    // Continuation already resumed, ignore
                }
            }
        }

    /**
     * Executes an async operation that transfers arena ownership on success.
     *
     * Use this for operations like `connect` where the arena should be owned
     * by the returned object on success, but cleaned up on failure.
     *
     * @param register Function that registers the callback. Returns a pair of
     *                 (arena, callback). The arena is passed so the caller can
     *                 transfer ownership on success.
     * @return The result from the callback
     * @throws TemporalCoreException if the callback reports an error
     */
    suspend inline fun <T : Any> withOwnershipTransfer(
        crossinline register: (Arena, callback: (T?, String?) -> Unit) -> Unit,
    ): Pair<Arena, T> {
        val arena = Arena.ofShared()
        var ownershipTransferred = false

        return try {
            val result =
                suspendCancellableCoroutine { continuation ->
                    register(arena) { result, error ->
                        try {
                            when {
                                error != null -> {
                                    continuation.resumeWithException(TemporalCoreException(error))
                                }

                                result != null -> {
                                    ownershipTransferred = true
                                    continuation.resume(result)
                                }

                                else -> {
                                    continuation.resumeWithException(
                                        TemporalCoreException("Operation returned null without error"),
                                    )
                                }
                            }
                        } catch (_: IllegalStateException) {
                            // Continuation already resumed, ignore
                        }
                    }
                }
            arena to result
        } catch (e: Exception) {
            if (!ownershipTransferred) {
                arena.close()
            }
            throw e
        }
    }
}
