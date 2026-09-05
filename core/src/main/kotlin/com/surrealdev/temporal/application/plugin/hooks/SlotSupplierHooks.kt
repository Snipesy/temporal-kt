package com.surrealdev.temporal.application.plugin.hooks

import com.surrealdev.temporal.application.plugin.Hook

/**
 * Hook called periodically by JvmResourceBased slot suppliers with resource metrics.
 *
 * Fired on the runtime sampler thread (typically every 50ms) with current
 * JVM resource measurements and PID controller outputs. Use this hook to emit
 * observability metrics (e.g., OpenTelemetry gauges).
 *
 * This is a **blocking** (non-suspend) hook because it fires from the grant loop's
 * scheduled executor thread.
 */
object SlotSupplierMetricsSampled : Hook<(SlotSupplierMetricsContext) -> Unit> {
    override val name = "SlotSupplierMetricsSampled"
}

/**
 * Context provided to [SlotSupplierMetricsSampled] hook handlers.
 *
 * @property taskQueue The task queue this slot supplier belongs to
 * @property slotType The slot type (e.g., "workflow", "activity", "local_activity", "nexus")
 * @property memoryUsage Current JVM memory usage ratio (0.0–1.0)
 * @property cpuLoad Current per-process CPU load (0.0–1.0)
 * @property memoryPidOutput PID controller output for memory
 * @property cpuPidOutput PID controller output for CPU
 * @property activeSlots Number of currently reserved slots
 * @property pendingReserves Number of pending async slot reservations
 */
data class SlotSupplierMetricsContext(
    val taskQueue: String,
    val slotType: String,
    val memoryUsage: Double,
    val cpuLoad: Double,
    val memoryPidOutput: Double,
    val cpuPidOutput: Double,
    val activeSlots: Int,
    val pendingReserves: Int,
)
