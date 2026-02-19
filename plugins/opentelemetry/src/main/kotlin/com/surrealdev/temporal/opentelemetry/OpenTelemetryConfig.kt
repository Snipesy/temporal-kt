package com.surrealdev.temporal.opentelemetry

import io.opentelemetry.api.OpenTelemetry

/**
 * Configuration for the OpenTelemetry plugin.
 *
 * Example usage:
 * ```kotlin
 * install(OpenTelemetryPlugin) {
 *     openTelemetry = myConfiguredOpenTelemetry
 *     tracerName = "my-service"
 *     enableWorkflowSpans = true
 *     enableActivitySpans = true
 *     enableMdcIntegration = true
 *     enableMetrics = true
 * }
 * ```
 */
class OpenTelemetryConfig {
    /**
     * OpenTelemetry instance to use.
     *
     * If not set, uses [io.opentelemetry.api.GlobalOpenTelemetry].
     */
    var openTelemetry: OpenTelemetry? = null

    /**
     * Enable workflow task spans.
     *
     * When true, creates spans for workflow task processing.
     * Default: true
     */
    var enableWorkflowSpans: Boolean = true

    /**
     * Enable activity task spans.
     *
     * When true, creates spans for activity task execution.
     * Default: true
     */
    var enableActivitySpans: Boolean = true

    /**
     * Tracer name for this plugin.
     *
     * This identifies the instrumentation in telemetry backends.
     * Default: "temporal-kt"
     */
    var tracerName: String = "temporal-kt"

    /**
     * Tracer version.
     *
     * Optional version string for the tracer instrumentation.
     */
    var tracerVersion: String? = null

    /**
     * Enable MDC integration for log correlation.
     *
     * When true, adds trace_id, span_id, and trace_flags to SLF4J MDC.
     * This allows logs to be correlated with traces using logback patterns like:
     * ```
     * %d{HH:mm:ss.SSS} trace_id=%X{trace_id} span_id=%X{span_id} - %msg%n
     * ```
     *
     * Default: true
     */
    var enableMdcIntegration: Boolean = true

    /**
     * Enable metrics collection.
     *
     * When true, records counters and histograms for workflow/activity tasks:
     * - `temporal.workflow.task.total` - Counter for workflow tasks
     * - `temporal.activity.task.total` - Counter for activity tasks
     * - `temporal.workflow.task.duration` - Histogram for workflow task duration
     * - `temporal.activity.task.duration` - Histogram for activity task duration
     *
     * Default: true
     */
    var enableMetrics: Boolean = true

    /**
     * Enable Core SDK metrics bridge.
     *
     * When true, Core SDK internal metrics (schedule-to-start latency, sticky cache
     * hit/eviction rates, worker slot usage, etc.) are forwarded through the OTel
     * pipeline. These metrics are emitted by the Rust Core SDK and cannot be measured
     * from Kotlin alone.
     *
     * Requires [enableMetrics] to also be true. The Meter instance is passed to
     * the Core runtime at startup.
     *
     * Default: true
     */
    var enableCoreMetrics: Boolean = true
}
