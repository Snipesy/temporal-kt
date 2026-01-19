package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.TemporalCoreRuntime
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * A Temporal Core runtime instance.
 *
 * The runtime is the entry point for all Temporal operations. It manages
 * the underlying Tokio async runtime and provides factory methods for
 * creating clients and workers.
 *
 * Runtimes are thread-safe and should be reused across the application.
 * Creating multiple runtimes is supported but generally unnecessary.
 *
 * Example usage:
 * ```kotlin
 * TemporalRuntime().use { runtime ->
 *     val client = runtime.createClient(ClientOptions(targetHost = "localhost:7233"))
 *     // Use the client...
 * }
 * ```
 *
 * @throws TemporalCoreException if runtime creation fails
 */
class TemporalRuntime private constructor(
    internal val handle: MemorySegment,
    private val arena: Arena,
) : AutoCloseable {
    @Volatile
    private var closed = false

    companion object {
        /**
         * Creates a new Temporal runtime with default options.
         *
         * @return A new TemporalRuntime instance
         * @throws TemporalCoreException if runtime creation fails
         */
        fun create(): TemporalRuntime {
            val arena = Arena.ofShared()
            return try {
                val handle = TemporalCoreRuntime.createRuntime(arena)
                TemporalRuntime(handle, arena)
            } catch (e: Exception) {
                arena.close()
                throw e
            }
        }
    }

    /**
     * Checks if this runtime has been closed.
     */
    fun isClosed(): Boolean = closed

    /**
     * Closes this runtime and releases all associated resources.
     *
     * After calling this method, the runtime can no longer be used.
     * All clients and workers created from this runtime become invalid.
     */
    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            TemporalCoreRuntime.freeRuntime(handle)
            arena.close()
        }
    }

    /**
     * Ensures the runtime is not closed before performing an operation.
     * @throws IllegalStateException if the runtime is closed
     */
    internal fun ensureOpen() {
        if (closed) {
            throw IllegalStateException("Runtime has been closed")
        }
    }
}
