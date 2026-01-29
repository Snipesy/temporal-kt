package com.surrealdev.temporal.opentelemetry

import io.opentelemetry.api.trace.Span
import org.slf4j.MDC

/**
 * Utility for augmenting SLF4J MDC with OpenTelemetry trace context.
 *
 * This integrates with the existing `kotlinx.coroutines.slf4j.MDCContext` pattern
 * used throughout the temporal-kt codebase (see ManagedWorker, WorkflowExecutor).
 *
 * MDC keys follow the OpenTelemetry Java instrumentation conventions:
 * - `trace_id` - 32-character hex trace ID
 * - `span_id` - 16-character hex span ID
 * - `trace_flags` - W3C trace flags (e.g., "01" for sampled)
 *
 * Example logback pattern:
 * ```xml
 * <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} trace_id=%X{trace_id} span_id=%X{span_id} - %msg%n</pattern>
 * ```
 */
object TracingMdc {
    const val TRACE_ID_KEY = "trace_id"
    const val SPAN_ID_KEY = "span_id"
    const val TRACE_FLAGS_KEY = "trace_flags"

    /**
     * Puts trace context from the given span into MDC.
     *
     * Call this when a span becomes current. If the span context is invalid,
     * no MDC entries are added.
     *
     * @param span The span whose context should be added to MDC
     */
    fun put(span: Span) {
        val ctx = span.spanContext
        if (ctx.isValid) {
            MDC.put(TRACE_ID_KEY, ctx.traceId)
            MDC.put(SPAN_ID_KEY, ctx.spanId)
            MDC.put(TRACE_FLAGS_KEY, ctx.traceFlags.asHex())
        }
    }

    /**
     * Removes trace context from MDC.
     *
     * Call this when a span ends to clean up MDC entries.
     */
    fun remove() {
        MDC.remove(TRACE_ID_KEY)
        MDC.remove(SPAN_ID_KEY)
        MDC.remove(TRACE_FLAGS_KEY)
    }
}
