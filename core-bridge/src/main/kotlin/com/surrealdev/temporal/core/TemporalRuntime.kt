package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.CoreMetricsBridge
import com.surrealdev.temporal.core.internal.TemporalCoreRuntime
import io.opentelemetry.api.metrics.Meter
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
    private val metricsBridge: CoreMetricsBridge?,
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
        fun create(): TemporalRuntime = create(coreMetricsMeter = null)

        /**
         * Creates a new Temporal runtime, optionally bridging Core SDK metrics to OTel.
         *
         * When [coreMetricsMeter] is non-null, it must be an `io.opentelemetry.api.metrics.Meter`
         * instance. Core SDK internal metrics (schedule-to-start latency, sticky cache hit rates,
         * worker slot usage, etc.) will be forwarded through the OTel pipeline.
         *
         * The parameter is typed as [Any] to avoid coupling callers to the OTel API. The cast
         * is performed internally.
         *
         * @param coreMetricsMeter An OTel Meter instance (or null for no Core metrics)
         * @return A new TemporalRuntime instance
         * @throws TemporalCoreException if runtime creation fails
         */
        fun create(coreMetricsMeter: Any?): TemporalRuntime {
            val bridge =
                if (coreMetricsMeter != null) {
                    CoreMetricsBridge(coreMetricsMeter as Meter)
                } else {
                    null
                }

            val arena = Arena.ofShared()
            return try {
                val handle =
                    if (bridge != null) {
                        val telemetryOptions = bridge.buildTelemetryOptions()
                        TemporalCoreRuntime.createRuntime(arena, telemetryOptions)
                    } else {
                        TemporalCoreRuntime.createRuntime(arena)
                    }
                TemporalRuntime(handle, arena, bridge)
            } catch (e: Exception) {
                bridge?.close()
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
     *
     * Close ordering is critical: freeRuntime first (Core may call metric
     * callbacks during shutdown), then close the metrics bridge (invalidates
     * upcall stubs), then close the arena.
     */
    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            // 1. Free the Core runtime — may still call metric callbacks during shutdown
            TemporalCoreRuntime.freeRuntime(handle)
            // 2. Close bridge — invalidates upcall stubs (safe now that Core is freed)
            metricsBridge?.close()
            // 3. Close arena — frees all native allocations
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
