package com.surrealdev.temporal.opentelemetry

import com.surrealdev.temporal.application.plugin.createApplicationPlugin
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskFailedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkerStartedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailedContext
import com.surrealdev.temporal.util.AttributeKey
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * OpenTelemetry plugin for Temporal KT.
 *
 * Provides observability through:
 * - **Tracing**: Spans for workflow and activity task execution
 * - **MDC Integration**: trace_id, span_id, trace_flags in SLF4J MDC for log correlation
 * - **Metrics**: Counters and histograms for task counts and durations
 *
 * ## Usage
 *
 * ```kotlin
 * val app = TemporalApplication {
 *     connection { target = "localhost:7233" }
 * }
 *
 * app.install(OpenTelemetryPlugin) {
 *     openTelemetry = myConfiguredOpenTelemetry  // Optional
 *     tracerName = "my-service"
 *     enableWorkflowSpans = true
 *     enableActivitySpans = true
 *     enableMdcIntegration = true
 *     enableMetrics = true
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
    createApplicationPlugin(
        name = "OpenTelemetry",
        createConfiguration = { OpenTelemetryConfig() },
    ) { config ->
        val otel = config.openTelemetry ?: GlobalOpenTelemetry.get()

        val tracer: Tracer =
            if (config.tracerVersion != null) {
                otel.getTracer(config.tracerName, config.tracerVersion)
            } else {
                otel.getTracer(config.tracerName)
            }

        val metrics: TemporalMetrics? =
            if (config.enableMetrics) {
                TemporalMetrics(otel.getMeter(config.tracerName))
            } else {
                null
            }

        val spanHolder = SpanContextHolder()

        // ==================== Workflow Hooks ====================

        if (config.enableWorkflowSpans) {
            onWorkflowTaskStarted { ctx: WorkflowTaskContext ->
                val span =
                    tracer
                        .spanBuilder("temporal.workflow.task")
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute(TemporalAttributes.WORKFLOW_TYPE, ctx.workflowType ?: "unknown")
                        .setAttribute(TemporalAttributes.RUN_ID, ctx.runId)
                        .setAttribute(TemporalAttributes.TASK_QUEUE, ctx.taskQueue)
                        .setAttribute(TemporalAttributes.NAMESPACE, ctx.namespace)
                        .startSpan()

                spanHolder.putWorkflowSpan(
                    runId = ctx.runId,
                    span = span,
                    workflowType = ctx.workflowType,
                    taskQueue = ctx.taskQueue,
                    namespace = ctx.namespace,
                )

                if (config.enableMdcIntegration) {
                    TracingMdc.put(span)
                }
            }

            on(
                com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompleted,
            ) { ctx: WorkflowTaskCompletedContext ->
                val spanWithContext = spanHolder.removeWorkflowSpan(ctx.runId)
                if (spanWithContext != null) {
                    val span = spanWithContext.span

                    // Record metrics
                    metrics?.let { m ->
                        val attrs =
                            Attributes.of(
                                TemporalAttributes.WORKFLOW_TYPE,
                                spanWithContext.workflowType ?: "unknown",
                                TemporalAttributes.TASK_QUEUE,
                                spanWithContext.taskQueue,
                                TemporalAttributes.NAMESPACE,
                                spanWithContext.namespace,
                                TemporalAttributes.STATUS,
                                TemporalAttributes.STATUS_SUCCESS,
                            )
                        m.workflowTaskCounter.add(1, attrs)
                        m.workflowTaskDuration.record(ctx.duration.inWholeMilliseconds.toDouble(), attrs)
                    }

                    if (config.enableMdcIntegration) {
                        TracingMdc.remove()
                    }

                    span.end()
                }
            }

            on(com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailed) { ctx: WorkflowTaskFailedContext ->
                val spanWithContext = spanHolder.removeWorkflowSpan(ctx.runId)
                if (spanWithContext != null) {
                    val span = spanWithContext.span

                    span.recordException(ctx.error)
                    span.setStatus(StatusCode.ERROR, ctx.error.message ?: "Workflow task failed")

                    // Record metrics
                    metrics?.let { m ->
                        val attrs =
                            Attributes.of(
                                TemporalAttributes.WORKFLOW_TYPE,
                                spanWithContext.workflowType ?: "unknown",
                                TemporalAttributes.TASK_QUEUE,
                                spanWithContext.taskQueue,
                                TemporalAttributes.NAMESPACE,
                                spanWithContext.namespace,
                                TemporalAttributes.STATUS,
                                TemporalAttributes.STATUS_FAILURE,
                            )
                        m.workflowTaskCounter.add(1, attrs)
                        // Note: duration not available in failed context
                    }

                    if (config.enableMdcIntegration) {
                        TracingMdc.remove()
                    }

                    span.end()
                }
            }
        }

        // ==================== Activity Hooks ====================

        if (config.enableActivitySpans) {
            onActivityTaskStarted { ctx: ActivityTaskContext ->
                val span =
                    tracer
                        .spanBuilder("temporal.activity.execute")
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute(TemporalAttributes.ACTIVITY_TYPE, ctx.activityType)
                        .setAttribute(TemporalAttributes.ACTIVITY_ID, ctx.activityId)
                        .setAttribute(TemporalAttributes.WORKFLOW_ID, ctx.workflowId)
                        .setAttribute(TemporalAttributes.RUN_ID, ctx.runId)
                        .setAttribute(TemporalAttributes.TASK_QUEUE, ctx.taskQueue)
                        .setAttribute(TemporalAttributes.NAMESPACE, ctx.namespace)
                        .startSpan()

                spanHolder.putActivitySpan(
                    workflowId = ctx.workflowId,
                    runId = ctx.runId,
                    activityId = ctx.activityId,
                    span = span,
                    activityType = ctx.activityType,
                    taskQueue = ctx.taskQueue,
                    namespace = ctx.namespace,
                )

                if (config.enableMdcIntegration) {
                    TracingMdc.put(span)
                }
            }

            on(
                com.surrealdev.temporal.application.plugin.hooks.ActivityTaskCompleted,
            ) { ctx: ActivityTaskCompletedContext ->
                // Extract activity info from the context
                val workflowId = ctx.workflowId
                val runId = ctx.runId
                val activityId = ctx.activityId

                val spanWithContext = spanHolder.removeActivitySpan(workflowId, runId, activityId)
                if (spanWithContext != null) {
                    val span = spanWithContext.span

                    // Record metrics
                    metrics?.let { m ->
                        val attrs =
                            Attributes.of(
                                TemporalAttributes.ACTIVITY_TYPE,
                                spanWithContext.activityType ?: ctx.activityType,
                                TemporalAttributes.TASK_QUEUE,
                                spanWithContext.taskQueue,
                                TemporalAttributes.NAMESPACE,
                                spanWithContext.namespace,
                                TemporalAttributes.STATUS,
                                TemporalAttributes.STATUS_SUCCESS,
                            )
                        m.activityTaskCounter.add(1, attrs)
                        m.activityTaskDuration.record(ctx.duration.inWholeMilliseconds.toDouble(), attrs)
                    }

                    if (config.enableMdcIntegration) {
                        TracingMdc.remove()
                    }

                    span.end()
                }
            }

            on(com.surrealdev.temporal.application.plugin.hooks.ActivityTaskFailed) { ctx: ActivityTaskFailedContext ->
                // Extract activity info from the context
                val workflowId = ctx.workflowId
                val runId = ctx.runId
                val activityId = ctx.activityId

                val spanWithContext = spanHolder.removeActivitySpan(workflowId, runId, activityId)
                if (spanWithContext != null) {
                    val span = spanWithContext.span

                    span.recordException(ctx.error)
                    span.setStatus(StatusCode.ERROR, ctx.error.message ?: "Activity task failed")

                    // Record metrics
                    metrics?.let { m ->
                        val attrs =
                            Attributes.of(
                                TemporalAttributes.ACTIVITY_TYPE,
                                spanWithContext.activityType ?: ctx.activityType,
                                TemporalAttributes.TASK_QUEUE,
                                spanWithContext.taskQueue,
                                TemporalAttributes.NAMESPACE,
                                spanWithContext.namespace,
                                TemporalAttributes.STATUS,
                                TemporalAttributes.STATUS_FAILURE,
                            )
                        m.activityTaskCounter.add(1, attrs)
                        // Note: duration not available in failed context
                    }

                    if (config.enableMdcIntegration) {
                        TracingMdc.remove()
                    }

                    span.end()
                }
            }
        }

        // ==================== Worker Hooks ====================

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

        // Return the plugin instance (Unit since we don't need state beyond the closures)
        Unit
    }

/**
 * Attribute key for storing OpenTelemetry plugin state in application attributes.
 */
val OpenTelemetryPluginKey = AttributeKey<Unit>("OpenTelemetryPlugin")
