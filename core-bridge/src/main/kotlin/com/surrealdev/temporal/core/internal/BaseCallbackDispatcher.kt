package com.surrealdev.temporal.core.internal

import com.surrealdev.temporal.core.TemporalCoreException
import kotlinx.coroutines.CancellableContinuation
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Base class for callback dispatchers that manage FFM native callbacks.
 *
 * Provides shared functionality for:
 * - Arena lifecycle management with suspend helpers
 * - Context ID extraction
 * - Common result handling patterns
 *
 * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
 */
internal abstract class BaseCallbackDispatcher(
    protected val runtimePtr: MemorySegment,
) : AutoCloseable {
    /**
     * Closes this dispatcher and clears any pending callbacks.
     * Subclasses must implement this to clear their specific pending callback maps.
     */
    abstract override fun close()

    /**
     * Extracts the context ID from a context pointer.
     */
    fun getContextId(contextPtr: MemorySegment): Long = PendingCallbacks.getContextId(contextPtr)

    /**
     * Executes an async native operation with automatic arena lifecycle management.
     *
     * Creates a per-call arena and suspends until the callback fires. The arena is
     * automatically closed by the dispatcher when the callback completes.
     *
     * Note: Rust always invokes callbacks (even on shutdown), so this function
     * does not support cancellation of the native operation. We simply wait for
     * the callback to fire naturally.
     *
     * @param block Function that registers the callback and makes the native call
     * @return The result from the callback
     */
    suspend inline fun <T> withManagedArena(
        crossinline block: (arena: Arena, continuation: CancellableContinuation<T>) -> Unit,
    ): T = CallbackArena.withManagedArena(block)

    // ============================================================
    // Continuation Resume Helpers
    // ============================================================

    /**
     * Resumes with RPC-style result (success/error based on status code).
     */
    fun <T> CancellableContinuation<T>.resumeRpcResult(
        response: T?,
        statusCode: Int,
        failureMessage: String?,
    ) {
        try {
            when {
                statusCode == 0 && response != null -> {
                    resume(response)
                }

                statusCode != 0 -> {
                    resumeWithException(
                        TemporalCoreException(failureMessage ?: "RPC call failed with status $statusCode"),
                    )
                }

                else -> {
                    resumeWithException(
                        TemporalCoreException("RPC call returned null response"),
                    )
                }
            }
        } catch (_: IllegalStateException) {
            // Continuation already resumed, ignore
        }
    }

    /**
     * Resumes with worker-style result (null = success, non-null = error).
     */
    fun CancellableContinuation<Unit>.resumeWorkerResult(error: String?) {
        try {
            if (error != null) {
                resumeWithException(TemporalCoreException(error))
            } else {
                resume(Unit)
            }
        } catch (_: IllegalStateException) {
            // Continuation already resumed, ignore
        }
    }

    /**
     * Resumes with simple success/error result.
     */
    fun <T> CancellableContinuation<T>.resumeResult(
        result: T?,
        error: String?,
    ) {
        try {
            when {
                error != null -> resumeWithException(TemporalCoreException(error))
                result != null -> resume(result)
                else -> resumeWithException(TemporalCoreException("Operation returned null without error"))
            }
        } catch (_: IllegalStateException) {
            // Continuation already resumed, ignore
        }
    }
}
