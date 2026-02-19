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
 *     enableClientSpans = true
 *     enableContextPropagation = true
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
     * Enable workflow interceptor spans.
     *
     * When true, creates per-operation spans for workflow inbound and outbound
     * interceptors (RunWorkflow, HandleSignal, StartActivity, etc.).
     * Default: true
     */
    var enableWorkflowSpans: Boolean = true

    /**
     * Enable activity interceptor spans.
     *
     * When true, creates spans for activity execution (RunActivity).
     * Default: true
     */
    var enableActivitySpans: Boolean = true

    /**
     * Enable client interceptor spans.
     *
     * When true, creates spans for client operations (StartWorkflow,
     * SignalWorkflow, QueryWorkflow, etc.).
     * Default: true
     */
    var enableClientSpans: Boolean = true

    /**
     * Enable trace context propagation via Temporal headers.
     *
     * When true, injects/extracts W3C trace context into Temporal message
     * headers using the [headerKey]. This enables parent-child span
     * relationships across client → workflow → activity boundaries.
     *
     * Context propagation works even when span creation is disabled,
     * allowing downstream services to continue the trace.
     * Default: true
     */
    var enableContextPropagation: Boolean = true

    /**
     * Temporal header key used for trace context propagation.
     *
     * Matches the convention used by official Temporal SDKs.
     * Default: "_tracer-data"
     */
    var headerKey: String = HeaderPropagator.TRACE_HEADER_KEY

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
     * When true, adds trace_id, span_id, and trace_flags to SLF4J MDC
     * during span execution. This allows logs to be correlated with traces
     * using logback patterns like:
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

    /**
     * Automatically close the OpenTelemetry instance on application shutdown.
     *
     * Only applies when the provided [openTelemetry] instance implements [java.io.Closeable]
     * (e.g., `OpenTelemetrySdk`). Instances obtained via `GlobalOpenTelemetry.get()` do not
     * implement `Closeable` and are unaffected.
     *
     * Default: true
     */
    var manageSdkLifecycle: Boolean = true

    /**
     * Automatically install the OpenTelemetry Logback Appender during application setup.
     *
     * When true, the plugin calls `OpenTelemetryAppender.install(openTelemetry)` via
     * reflection if the logback appender library is on the classpath
     * (`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`).
     * If the library is not present, this is silently skipped.
     *
     * Default: true
     */
    var installLogbackAppender: Boolean = true
}
