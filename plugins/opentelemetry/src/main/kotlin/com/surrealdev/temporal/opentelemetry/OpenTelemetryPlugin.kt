package com.surrealdev.temporal.opentelemetry

import com.surrealdev.temporal.application.CoreMetricsMeterKey
import com.surrealdev.temporal.application.plugin.createScopedPlugin
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskFailedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkerStartedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailedContext
import com.surrealdev.temporal.serialization.payloadSerializer
import com.surrealdev.temporal.util.AttributeKey
import com.surrealdev.temporal.workflow.ContinueAsNewException
import com.surrealdev.temporal.workflow.WorkflowContext
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.extension.kotlin.getOpenTelemetryContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * OpenTelemetry plugin for Temporal KT.
 *
 * Provides full distributed tracing aligned with the official Temporal SDK conventions:
 *
 * - **Interceptor-based spans**: Per-operation spans following the `Operation:TypeName` naming
 *   convention (e.g., `StartWorkflow:MyWorkflow`, `RunActivity:greet`).
 * - **Trace context propagation**: W3C TraceContext injected/extracted via Temporal headers,
 *   creating proper parent-child span relationships across client → workflow → activity boundaries.
 * - **Replay safety**: Span creation is skipped during workflow replay to avoid duplicate spans.
 * - **Activation-level metrics**: Hooks for task counters and duration histograms.
 * - **MDC integration**: trace_id, span_id, trace_flags in SLF4J MDC for log correlation.
 *
 * ## Span Hierarchy
 *
 * ```
 * StartWorkflow:MyWorkflow (CLIENT)
 *   └─ RunWorkflow:MyWorkflow (SERVER)
 *        ├─ HandleSignal:mySignal (SERVER, link to SignalWorkflow span)
 *        ├─ StartActivity:myActivity (CLIENT)
 *        │    └─ RunActivity:myActivity (SERVER)
 *        └─ StartChildWorkflow:ChildWf (CLIENT)
 *             └─ RunWorkflow:ChildWf (SERVER)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * // Application-level (workflows + activities + client)
 * val app = TemporalApplication {
 *     connection { target = "localhost:7233" }
 * }
 * app.install(OpenTelemetryPlugin) {
 *     openTelemetry = myConfiguredOpenTelemetry  // Optional
 *     tracerName = "my-service"
 * }
 *
 * // Standalone client
 * val client = TemporalClient.connect {
 *     target = "http://localhost:7233"
 *     install(OpenTelemetryPlugin) {
 *         tracerName = "my-client"
 *     }
 * }
 * ```
 *
 * ## Logback Configuration
 *
 * To include trace context in logs:
 * ```xml
 * <pattern>%d{HH:mm:ss.SSS} trace_id=%X{trace_id} span_id=%X{span_id} - %msg%n</pattern>
 * ```
 */
val OpenTelemetryPlugin =
    createScopedPlugin(
        name = "OpenTelemetry",
        createConfiguration = { OpenTelemetryConfig() },
    ) { config ->
        val otel = config.openTelemetry ?: GlobalOpenTelemetry.get()

        val tracer: Tracer =
            config.tracerVersion?.let { otel.getTracer(config.tracerName, it) }
                ?: otel.getTracer(config.tracerName)

        val otelMeter = if (config.enableMetrics) otel.getMeter(config.tracerName) else null
        val metrics: TemporalMetrics? = otelMeter?.let { TemporalMetrics(it) }
        val serializer = pipeline.payloadSerializer()
        val propagator = HeaderPropagator(serializer, config.headerKey)

        // SpanContextHolder is still used for activation-level hook metrics
        val spanHolder = SpanContextHolder()

        // Mark plugin as installed
        pipeline.attributes.put(OpenTelemetryPluginKey, Unit)

        // Store the Meter in application attributes for Core metrics bridge
        if (config.enableCoreMetrics && otelMeter != null) {
            pipeline.attributes.put(CoreMetricsMeterKey, otelMeter)
        }

        // Helper to get WorkflowContext from the current coroutine
        suspend fun getWorkflowContext(): WorkflowContext? = currentCoroutineContext()[WorkflowContext]

        // Resolves the current OTel context from the coroutine context first
        suspend fun currentContext(): Context {
            val fromCoroutine = currentCoroutineContext().getOpenTelemetryContext()
            return if (fromCoroutine != Context.root()) fromCoroutine else Context.current()
        }

        // Helper to create a span, make it current, run the operation, and end the span.
        // Making the span current is critical: it ensures that nested interceptors
        // (e.g., onScheduleActivity inside a RunWorkflow span) see this span via
        // Context.current() and create proper parent-child relationships.
        // When parentContext is null, resolves from coroutine context first, then ThreadLocal.
        suspend fun <T> withSpan(
            name: String,
            kind: SpanKind,
            parentContext: Context? = null,
            configure: io.opentelemetry.api.trace.SpanBuilder.() -> Unit = {},
            body: suspend (Span) -> T,
        ): T {
            val resolvedContext = parentContext ?: currentContext()
            val span =
                tracer
                    .spanBuilder(name)
                    .setSpanKind(kind)
                    .setParent(resolvedContext)
                    .apply { configure() }
                    .startSpan()

            val spanContext = resolvedContext.with(span)

            // Build the coroutine context elements:
            // 1. asContextElement() bridges OTel's ThreadLocal Context with Kotlin's
            //    CoroutineContext, ensuring Context.current() survives suspension.
            // 2. If MDC integration is enabled, merge trace fields (trace_id, span_id,
            //    trace_flags) into a new MDCContext that also inherits the parent's MDC
            //    entries (workflowType, taskQueue, runId, etc.). This is necessary because
            //    the parent MDCContext would overwrite any direct MDC.put() calls on resume.
            val contextElements =
                if (config.enableMdcIntegration && span.spanContext.isValid) {
                    val parentMdc = currentCoroutineContext()[MDCContext]?.contextMap ?: emptyMap()
                    val traceMdc =
                        mapOf(
                            TracingMdc.TRACE_ID_KEY to span.spanContext.traceId,
                            TracingMdc.SPAN_ID_KEY to span.spanContext.spanId,
                            TracingMdc.TRACE_FLAGS_KEY to span.spanContext.traceFlags.asHex(),
                        )
                    spanContext.asContextElement() + MDCContext(parentMdc + traceMdc)
                } else {
                    spanContext.asContextElement()
                }

            return withContext(contextElements) {
                try {
                    body(span)
                } catch (e: Throwable) {
                    // ContinueAsNew is a normal control-flow mechanism, not an error.
                    // Don't pollute error dashboards with false positives.
                    if (e !is ContinueAsNewException) {
                        span.recordException(e)
                        span.setStatus(StatusCode.ERROR, e.message ?: "Error")
                    }
                    throw e
                } finally {
                    span.end()
                }
            }
        }

        // ==================== Application Hooks ====================

        application {
            if (config.installLogbackAppender) {
                onSetup {
                    tryInstallLogbackAppender(otel)
                }
            }

            if (config.manageSdkLifecycle) {
                onShutdown {
                    (otel as? java.io.Closeable)?.close()
                }
            }

            if (config.enableMetrics) {
                onWorkerStarted { ctx: WorkerStartedContext ->
                    metrics?.let { m ->
                        val attrs =
                            Attributes.of(
                                TemporalAttributes.TASK_QUEUE,
                                ctx.taskQueue,
                                TemporalAttributes.NAMESPACE,
                                ctx.namespace,
                            )
                        m.workerStartedCounter.add(1, attrs)
                    }
                }
            }
        }

        // ==================== Client Interceptors ====================

        client {
            if (config.enableClientSpans || config.enableContextPropagation) {
                onStartWorkflow { input, proceed ->
                    if (config.enableClientSpans) {
                        withSpan("StartWorkflow:${input.workflowType}", SpanKind.CLIENT, configure = {
                            setAttribute(TemporalAttributes.WORKFLOW_ID, input.workflowId)
                        }) {
                            if (config.enableContextPropagation) {
                                propagator.inject(input.headers, Context.current())
                            }
                            proceed(input)
                        }
                    } else {
                        if (config.enableContextPropagation) {
                            propagator.inject(input.headers, currentContext())
                        }
                        proceed(input)
                    }
                }

                onSignalWorkflow { input, proceed ->
                    if (config.enableClientSpans) {
                        withSpan("SignalWorkflow:${input.signalName}", SpanKind.CLIENT, configure = {
                            setAttribute(TemporalAttributes.WORKFLOW_ID, input.workflowId)
                        }) {
                            if (config.enableContextPropagation) {
                                propagator.inject(input.headers, Context.current())
                            }
                            proceed(input)
                        }
                    } else {
                        if (config.enableContextPropagation) {
                            propagator.inject(input.headers, currentContext())
                        }
                        proceed(input)
                    }
                }

                onQueryWorkflow { input, proceed ->
                    if (config.enableClientSpans) {
                        withSpan("QueryWorkflow:${input.queryType}", SpanKind.CLIENT, configure = {
                            setAttribute(TemporalAttributes.WORKFLOW_ID, input.workflowId)
                        }) {
                            if (config.enableContextPropagation) {
                                propagator.inject(input.headers, Context.current())
                            }
                            proceed(input)
                        }
                    } else {
                        if (config.enableContextPropagation) {
                            propagator.inject(input.headers, currentContext())
                        }
                        proceed(input)
                    }
                }

                onStartWorkflowUpdate { input, proceed ->
                    if (config.enableClientSpans) {
                        withSpan("UpdateWorkflow:${input.updateName}", SpanKind.CLIENT, configure = {
                            setAttribute(TemporalAttributes.WORKFLOW_ID, input.workflowId)
                        }) {
                            if (config.enableContextPropagation) {
                                propagator.inject(input.headers, Context.current())
                            }
                            proceed(input)
                        }
                    } else {
                        if (config.enableContextPropagation) {
                            propagator.inject(input.headers, currentContext())
                        }
                        proceed(input)
                    }
                }
            }
        }

        // ==================== Workflow Interceptors ====================

        workflow {
            // --- Coroutine Context Hook ---
            // Contributes OTel context elements to the workflow's base coroutine context.
            // This ensures Context.current() returns the correct span in ALL child coroutines
            // (async {}, launch {}, signal/update handlers), not just the immediate withContext block.

            if (config.enableWorkflowSpans || config.enableContextPropagation) {
                onBuildCoroutineContext { event ->
                    val parentContext = propagator.extract(event.headers)

                    if (config.enableWorkflowSpans && !event.isReplaying) {
                        val span =
                            tracer
                                .spanBuilder("RunWorkflow:${event.workflowType}")
                                .setSpanKind(SpanKind.SERVER)
                                .setParent(parentContext)
                                .setAttribute(TemporalAttributes.WORKFLOW_ID, event.workflowId)
                                .setAttribute(TemporalAttributes.RUN_ID, event.runId)
                                .startSpan()

                        val spanContext = parentContext.with(span)

                        // End span when workflow coroutine completes
                        event.onCompletion { cause ->
                            if (cause != null &&
                                cause !is ContinueAsNewException &&
                                cause !is CancellationException
                            ) {
                                span.recordException(cause)
                                span.setStatus(StatusCode.ERROR, cause.message ?: "Error")
                            }
                            span.end()
                        }

                        // Contribute OTel context + optionally merge trace fields into MDC.
                        // Combined into a single contribute() call so the context is consistent.
                        if (config.enableMdcIntegration && span.spanContext.isValid) {
                            val traceMdc =
                                mapOf(
                                    TracingMdc.TRACE_ID_KEY to span.spanContext.traceId,
                                    TracingMdc.SPAN_ID_KEY to span.spanContext.spanId,
                                    TracingMdc.TRACE_FLAGS_KEY to span.spanContext.traceFlags.asHex(),
                                )
                            event.contribute(
                                spanContext.asContextElement() + MDCContext(event.mdcContextMap + traceMdc),
                            )
                        } else {
                            event.contribute(spanContext.asContextElement())
                        }
                    } else {
                        // Replay or spans disabled: still propagate extracted parent context
                        // so outbound interceptors can propagate trace context via headers.
                        event.contribute(parentContext.asContextElement())
                    }
                }
            }

            // --- Inbound Interceptors ---

            if (config.enableWorkflowSpans || config.enableContextPropagation) {
                onHandleSignal { input, proceed ->
                    val wfCtx = getWorkflowContext()

                    if (config.enableWorkflowSpans && wfCtx?.isReplaying != true) {
                        // Signal handler span is a child of the workflow context.
                        // We add a link to the client-side SignalWorkflow span if present in headers.
                        val linkSpanContext = propagator.extractSpanContext(input.headers)

                        withSpan("HandleSignal:${input.signalName}", SpanKind.SERVER, configure = {
                            if (wfCtx != null) {
                                setAttribute(TemporalAttributes.WORKFLOW_ID, wfCtx.info.workflowId)
                                setAttribute(TemporalAttributes.RUN_ID, wfCtx.info.runId)
                            }
                            if (linkSpanContext != null) {
                                addLink(linkSpanContext)
                            }
                        }) {
                            proceed(input)
                        }
                    } else {
                        proceed(input)
                    }
                }

                onHandleQuery { input, proceed ->
                    // Queries always execute (even during replay, since they're read-only)
                    // Query handler span is parented under the QueryWorkflow client span
                    val parentContext = propagator.extract(input.headers)
                    val wfCtx = getWorkflowContext()

                    if (config.enableWorkflowSpans) {
                        withSpan("HandleQuery:${input.queryType}", SpanKind.SERVER, parentContext, configure = {
                            if (wfCtx != null) {
                                setAttribute(TemporalAttributes.WORKFLOW_ID, wfCtx.info.workflowId)
                                setAttribute(TemporalAttributes.RUN_ID, wfCtx.info.runId)
                            }
                        }) {
                            proceed(input)
                        }
                    } else {
                        proceed(input)
                    }
                }

                onValidateUpdate { input, proceed ->
                    val wfCtx = getWorkflowContext()

                    if (config.enableWorkflowSpans && wfCtx?.isReplaying != true) {
                        val linkSpanContext = propagator.extractSpanContext(input.headers)

                        withSpan("ValidateUpdate:${input.updateName}", SpanKind.SERVER, configure = {
                            if (wfCtx != null) {
                                setAttribute(TemporalAttributes.WORKFLOW_ID, wfCtx.info.workflowId)
                                setAttribute(TemporalAttributes.RUN_ID, wfCtx.info.runId)
                            }
                            setAttribute(TemporalAttributes.UPDATE_ID, input.protocolInstanceId)
                            if (linkSpanContext != null) {
                                addLink(linkSpanContext)
                            }
                        }) {
                            proceed(input)
                        }
                    } else {
                        proceed(input)
                    }
                }

                onExecuteUpdate { input, proceed ->
                    val wfCtx = getWorkflowContext()

                    if (config.enableWorkflowSpans && wfCtx?.isReplaying != true) {
                        val linkSpanContext = propagator.extractSpanContext(input.headers)

                        withSpan("HandleUpdate:${input.updateName}", SpanKind.SERVER, configure = {
                            if (wfCtx != null) {
                                setAttribute(TemporalAttributes.WORKFLOW_ID, wfCtx.info.workflowId)
                                setAttribute(TemporalAttributes.RUN_ID, wfCtx.info.runId)
                            }
                            setAttribute(TemporalAttributes.UPDATE_ID, input.protocolInstanceId)
                            if (linkSpanContext != null) {
                                addLink(linkSpanContext)
                            }
                        }) {
                            proceed(input)
                        }
                    } else {
                        proceed(input)
                    }
                }
            }

            // --- Outbound Interceptors ---

            if (config.enableWorkflowSpans || config.enableContextPropagation) {
                onScheduleActivity { input, proceed ->
                    val wfCtx = getWorkflowContext()
                    val shouldCreateSpan = config.enableWorkflowSpans && wfCtx?.isReplaying != true

                    if (shouldCreateSpan) {
                        withSpan("StartActivity:${input.activityType}", SpanKind.CLIENT) {
                            if (config.enableContextPropagation) {
                                val headersWithTrace = (input.options.headers ?: emptyMap()).toMutableMap()
                                propagator.inject(headersWithTrace, Context.current())
                                proceed(input.copy(options = input.options.copy(headers = headersWithTrace)))
                            } else {
                                proceed(input)
                            }
                        }
                    } else if (config.enableContextPropagation) {
                        // Always propagate context even during replay
                        val headersWithTrace = (input.options.headers ?: emptyMap()).toMutableMap()
                        propagator.inject(headersWithTrace, Context.current())
                        proceed(input.copy(options = input.options.copy(headers = headersWithTrace)))
                    } else {
                        proceed(input)
                    }
                }

                onScheduleLocalActivity { input, proceed ->
                    val wfCtx = getWorkflowContext()
                    val shouldCreateSpan = config.enableWorkflowSpans && wfCtx?.isReplaying != true

                    if (shouldCreateSpan) {
                        withSpan("StartActivity:${input.activityType}", SpanKind.CLIENT) {
                            if (config.enableContextPropagation) {
                                propagator.inject(input.headers, Context.current())
                            }
                            proceed(input)
                        }
                    } else if (config.enableContextPropagation) {
                        propagator.inject(input.headers, Context.current())
                        proceed(input)
                    } else {
                        proceed(input)
                    }
                }

                onStartChildWorkflow { input, proceed ->
                    val wfCtx = getWorkflowContext()
                    val shouldCreateSpan = config.enableWorkflowSpans && wfCtx?.isReplaying != true

                    if (shouldCreateSpan) {
                        withSpan("StartChildWorkflow:${input.workflowType}", SpanKind.CLIENT) {
                            if (config.enableContextPropagation) {
                                propagator.inject(input.headers, Context.current())
                            }
                            proceed(input)
                        }
                    } else if (config.enableContextPropagation) {
                        propagator.inject(input.headers, Context.current())
                        proceed(input)
                    } else {
                        proceed(input)
                    }
                }

                onSignalExternalWorkflow { input, proceed ->
                    val wfCtx = getWorkflowContext()
                    val shouldCreateSpan = config.enableWorkflowSpans && wfCtx?.isReplaying != true

                    if (shouldCreateSpan) {
                        withSpan("SignalExternalWorkflow:${input.signalName}", SpanKind.CLIENT, configure = {
                            setAttribute(TemporalAttributes.WORKFLOW_ID, input.workflowId)
                        }) {
                            if (config.enableContextPropagation) {
                                propagator.inject(input.headers, Context.current())
                            }
                            proceed(input)
                        }
                    } else if (config.enableContextPropagation) {
                        propagator.inject(input.headers, Context.current())
                        proceed(input)
                    } else {
                        proceed(input)
                    }
                }

                onContinueAsNew { input, proceed ->
                    // No span for continue-as-new, just propagate context so the new run
                    // continues the trace.
                    if (config.enableContextPropagation) {
                        val existingHeaders = input.options.headers ?: emptyMap()
                        val headersWithTrace = existingHeaders.toMutableMap()
                        propagator.inject(headersWithTrace, Context.current())
                        proceed(input.copy(options = input.options.copy(headers = headersWithTrace)))
                    } else {
                        proceed(input)
                    }
                }
            }

            // --- Activation-Level Observer Hooks (for metrics only) ---

            if (config.enableMetrics) {
                onTaskStarted { ctx: WorkflowTaskContext ->
                    spanHolder.putWorkflowSpan(
                        runId = ctx.runId,
                        workflowType = ctx.workflowType,
                        taskQueue = ctx.taskQueue,
                        namespace = ctx.namespace,
                    )
                }

                onTaskCompleted { ctx: WorkflowTaskCompletedContext ->
                    val storedCtx = spanHolder.removeWorkflowSpan(ctx.runId)
                    if (storedCtx != null) {
                        metrics?.let { m ->
                            val attrs =
                                Attributes.of(
                                    TemporalAttributes.WORKFLOW_TYPE,
                                    storedCtx.workflowType ?: "unknown",
                                    TemporalAttributes.TASK_QUEUE,
                                    storedCtx.taskQueue,
                                    TemporalAttributes.NAMESPACE,
                                    storedCtx.namespace,
                                    TemporalAttributes.STATUS,
                                    TemporalAttributes.STATUS_SUCCESS,
                                )
                            m.workflowTaskCounter.add(1, attrs)
                            m.workflowTaskDuration.record(ctx.duration.inWholeMilliseconds.toDouble(), attrs)
                        }
                    }
                }

                onTaskFailed { ctx: WorkflowTaskFailedContext ->
                    val storedCtx = spanHolder.removeWorkflowSpan(ctx.runId)
                    if (storedCtx != null) {
                        metrics?.let { m ->
                            val attrs =
                                Attributes.of(
                                    TemporalAttributes.WORKFLOW_TYPE,
                                    storedCtx.workflowType ?: "unknown",
                                    TemporalAttributes.TASK_QUEUE,
                                    storedCtx.taskQueue,
                                    TemporalAttributes.NAMESPACE,
                                    storedCtx.namespace,
                                    TemporalAttributes.STATUS,
                                    TemporalAttributes.STATUS_FAILURE,
                                )
                            m.workflowTaskCounter.add(1, attrs)
                        }
                    }
                }
            }
        }

        // ==================== Activity Interceptors ====================

        activity {
            if (config.enableActivitySpans || config.enableContextPropagation) {
                onExecute { input, proceed ->
                    val parentContext = propagator.extract(input.headers)

                    if (config.enableActivitySpans) {
                        withSpan("RunActivity:${input.activityType}", SpanKind.SERVER, parentContext, configure = {
                            setAttribute(TemporalAttributes.ACTIVITY_ID, input.activityId)
                            setAttribute(TemporalAttributes.WORKFLOW_ID, input.workflowId)
                            setAttribute(TemporalAttributes.RUN_ID, input.runId)
                        }) {
                            proceed(input)
                        }
                    } else {
                        proceed(input)
                    }
                }
            }

            // --- Activation-Level Observer Hooks (for metrics only) ---

            if (config.enableMetrics) {
                onTaskStarted { ctx: ActivityTaskContext ->
                    spanHolder.putActivitySpan(
                        workflowId = ctx.workflowId,
                        runId = ctx.runId,
                        activityId = ctx.activityId,
                        activityType = ctx.activityType,
                        taskQueue = ctx.taskQueue,
                        namespace = ctx.namespace,
                    )
                }

                onTaskCompleted { ctx: ActivityTaskCompletedContext ->
                    val storedCtx = spanHolder.removeActivitySpan(ctx.workflowId, ctx.runId, ctx.activityId)
                    if (storedCtx != null) {
                        metrics?.let { m ->
                            val attrs =
                                Attributes.of(
                                    TemporalAttributes.ACTIVITY_TYPE,
                                    storedCtx.activityType ?: ctx.activityType,
                                    TemporalAttributes.TASK_QUEUE,
                                    storedCtx.taskQueue,
                                    TemporalAttributes.NAMESPACE,
                                    storedCtx.namespace,
                                    TemporalAttributes.STATUS,
                                    TemporalAttributes.STATUS_SUCCESS,
                                )
                            m.activityTaskCounter.add(1, attrs)
                            m.activityTaskDuration.record(ctx.duration.inWholeMilliseconds.toDouble(), attrs)
                        }
                    }
                }

                onTaskFailed { ctx: ActivityTaskFailedContext ->
                    val storedCtx = spanHolder.removeActivitySpan(ctx.workflowId, ctx.runId, ctx.activityId)
                    if (storedCtx != null) {
                        metrics?.let { m ->
                            val attrs =
                                Attributes.of(
                                    TemporalAttributes.ACTIVITY_TYPE,
                                    storedCtx.activityType ?: ctx.activityType,
                                    TemporalAttributes.TASK_QUEUE,
                                    storedCtx.taskQueue,
                                    TemporalAttributes.NAMESPACE,
                                    storedCtx.namespace,
                                    TemporalAttributes.STATUS,
                                    TemporalAttributes.STATUS_FAILURE,
                                )
                            m.activityTaskCounter.add(1, attrs)
                        }
                    }
                }
            }
        }
    }

/**
 * Attribute key for storing OpenTelemetry plugin state in application attributes.
 */
val OpenTelemetryPluginKey = AttributeKey<Unit>("OpenTelemetryPlugin")

private fun tryInstallLogbackAppender(otel: io.opentelemetry.api.OpenTelemetry) {
    try {
        val clazz =
            Class.forName(
                "io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender",
            )
        clazz
            .getMethod("install", io.opentelemetry.api.OpenTelemetry::class.java)
            .invoke(null, otel)
    } catch (_: ReflectiveOperationException) {
        // Logback appender not on classpath or method signature changed — skip
    }
}
