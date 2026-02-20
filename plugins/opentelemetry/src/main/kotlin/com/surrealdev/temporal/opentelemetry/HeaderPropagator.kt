package com.surrealdev.temporal.opentelemetry

import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context

/**
 * Handles injection and extraction of OpenTelemetry trace context
 * to/from Temporal headers using W3C TraceContext propagation.
 *
 * The trace context is serialized as a `Map<String, String>` carrier
 * (containing `traceparent` and optionally `tracestate`) and stored
 * under the [TRACE_HEADER_KEY] in Temporal message headers.
 */
internal class HeaderPropagator(
    private val serializer: PayloadSerializer,
    private val headerKey: String = TRACE_HEADER_KEY,
) {
    private val propagator = W3CTraceContextPropagator.getInstance()

    /**
     * Injects the current OTel context's trace information into the given
     * mutable headers map.
     *
     * Creates a W3C traceparent/tracestate carrier, serializes it as a
     * [TemporalPayload], and puts it into [headers] under [headerKey].
     *
     * @param headers Mutable Temporal headers to inject into
     * @param context The OTel context to inject. Callers should resolve this from
     *   the coroutine context (via [kotlinx.coroutines.currentCoroutineContext]) rather than relying on [Context.current],
     *   which may be stale in undispatched coroutines (e.g., Ktor Netty).
     */
    fun inject(
        headers: MutableMap<String, TemporalPayload>,
        context: Context,
    ) {
        val carrier = mutableMapOf<String, String>()
        propagator.inject(context, carrier, MapTextMapSetter)
        if (carrier.isNotEmpty()) {
            headers[headerKey] = serializer.serialize<Map<String, String>>(carrier)
        }
    }

    /**
     * Extracts OTel trace context from Temporal headers.
     *
     * Deserializes the carrier stored under [headerKey] and extracts
     * the W3C trace context into an OTel [Context].
     *
     * @param headers Temporal headers to extract from (may be null)
     * @return The extracted OTel context, or [Context.current] if no trace header present
     */
    fun extract(headers: Map<String, TemporalPayload>?): Context {
        if (headers == null) return Context.current()
        val payload = headers[headerKey] ?: return Context.current()
        val carrier: Map<String, String> =
            try {
                serializer.deserialize<Map<String, String>>(payload)
            } catch (_: Exception) {
                return Context.current()
            }
        return propagator.extract(Context.current(), carrier, MapTextMapGetter)
    }

    /**
     * Extracts the [SpanContext] from Temporal headers, if present.
     *
     * Useful for creating span links (e.g., linking a signal handler span
     * back to the client signal span).
     *
     * @param headers Temporal headers to extract from (may be null)
     * @return The extracted [SpanContext], or null if not present/valid
     */
    fun extractSpanContext(headers: Map<String, TemporalPayload>?): SpanContext? {
        val ctx = extract(headers)
        val spanContext = Span.fromContext(ctx).spanContext
        return if (spanContext.isValid) spanContext else null
    }

    companion object {
        /** Header key matching the official Temporal SDK convention. */
        const val TRACE_HEADER_KEY = "_tracer-data"
    }
}

/**
 * [io.opentelemetry.context.propagation.TextMapSetter] for `MutableMap<String, String>`.
 */
private object MapTextMapSetter : io.opentelemetry.context.propagation.TextMapSetter<MutableMap<String, String>> {
    override fun set(
        carrier: MutableMap<String, String>?,
        key: String,
        value: String,
    ) {
        carrier?.put(key, value)
    }
}

/**
 * [io.opentelemetry.context.propagation.TextMapGetter] for `Map<String, String>`.
 */
private object MapTextMapGetter : io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>> {
    override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys

    override fun get(
        carrier: Map<String, String>?,
        key: String,
    ): String? = carrier?.get(key)
}
