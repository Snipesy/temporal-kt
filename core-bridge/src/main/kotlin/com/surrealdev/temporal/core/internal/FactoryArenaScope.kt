package com.surrealdev.temporal.core.internal

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Manages the lifecycle of arenas and dispatcher for factory methods.
 *
 * Encapsulates the common "2-arena + dispatcher" pattern used when creating
 * long-lived resources (workers, clients, servers). Provides:
 * - Safe initialization with rollback on failure
 * - Automatic cleanup on exception
 * - Ownership transfer to prevent double-close
 *
 * Arena roles:
 * - resourceArena: Holds data that outlives individual operations (e.g., options, configs)
 * - callbackArena: Holds reusable callback stubs for the dispatcher
 *
 * Example usage:
 * ```kotlin
 * fun create(...): TemporalWorker {
 *     return FactoryArenaScope.create(runtime.handle, ::WorkerCallbackDispatcher)
 *         .createResource {
 *             val workerPtr = InternalWorker.createWorker(resourceArena, ...)
 *             TemporalWorker(resourceArena, callbackArena, dispatcher, ...)
 *         }
 * }
 * ```
 *
 * @param D The dispatcher type (must extend BaseCallbackDispatcher)
 */
internal class FactoryArenaScope<D : BaseCallbackDispatcher> private constructor(
    val resourceArena: Arena,
    val callbackArena: Arena,
    val dispatcher: D,
) : AutoCloseable {
    @Volatile
    private var ownershipTransferred = false

    companion object {
        /**
         * Creates a FactoryArenaScope with safe initialization.
         *
         * If dispatcher creation fails, all arenas are properly closed.
         *
         * @param runtimeHandle Handle to the Temporal runtime for the dispatcher
         * @param createDispatcher Factory function that creates the dispatcher
         * @return A new FactoryArenaScope
         */
        inline fun <D : BaseCallbackDispatcher> create(
            runtimeHandle: MemorySegment,
            crossinline createDispatcher: (callbackArena: Arena, runtimeHandle: MemorySegment) -> D,
        ): FactoryArenaScope<D> {
            val resourceArena = Arena.ofShared()
            val callbackArena: Arena
            val dispatcher: D
            try {
                callbackArena = Arena.ofShared()
                dispatcher = createDispatcher(callbackArena, runtimeHandle)
            } catch (e: Exception) {
                resourceArena.close()
                throw e
            }
            return FactoryArenaScope(resourceArena, callbackArena, dispatcher)
        }
    }

    /**
     * Transfers ownership of resources to the created instance.
     *
     * After calling this, close() becomes a no-op, preventing double-close
     * when the FactoryArenaScope goes out of scope.
     */
    fun transferOwnership() {
        ownershipTransferred = true
    }

    /**
     * Closes all resources if ownership hasn't been transferred.
     * Safe to call multiple times. Handles already-closed arenas gracefully.
     */
    override fun close() {
        if (!ownershipTransferred) {
            dispatcher.close()
            try {
                callbackArena.close()
            } catch (_: IllegalStateException) {
                // Already closed
            }
            try {
                resourceArena.close()
            } catch (_: IllegalStateException) {
                // Already closed
            }
        }
    }

    /**
     * Executes a block and transfers ownership on success, closes on failure.
     *
     * This is the primary API for using FactoryArenaScope. The block receives
     * access to the arenas and dispatcher, and should create and return the
     * long-lived resource. On success, ownership is transferred; on failure,
     * all resources are cleaned up.
     *
     * @param block The factory operation that creates the long-lived resource
     * @return The created resource
     */
    inline fun <T> createResource(block: FactoryArenaScope<D>.() -> T): T =
        try {
            val result = block()
            transferOwnership()
            result
        } catch (e: Exception) {
            close()
            throw e
        }
}
