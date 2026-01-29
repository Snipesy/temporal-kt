package com.surrealdev.temporal.opentelemetry

import io.opentelemetry.api.trace.Span
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe storage for active spans keyed by their identifiers.
 *
 * Spans are stored when a task starts (via WorkflowTaskStarted, ActivityTaskStarted hooks)
 * and retrieved when the task completes or fails (via Completed/Failed hooks).
 *
 * This allows correlating the start and end of tasks even though they occur
 * in different hook invocations.
 *
 * The storage uses [ConcurrentHashMap] for thread-safety since tasks from
 * different workers may be processed concurrently.
 */
class SpanContextHolder {
    /**
     * Data class holding span and associated context for metrics.
     */
    data class SpanWithContext(
        val span: Span,
        val workflowType: String? = null,
        val activityType: String? = null,
        val taskQueue: String,
        val namespace: String,
        val workflowId: String? = null,
        val activityId: String? = null,
    )

    /**
     * Active workflow task spans, keyed by run ID.
     *
     * Run ID is used because workflow activations are identified by run ID
     * and a workflow can have at most one active activation at a time.
     */
    private val workflowSpans = ConcurrentHashMap<String, SpanWithContext>()

    /**
     * Active activity task spans, keyed by a composite key of workflowId:runId:activityId.
     *
     * Activity ID alone isn't globally unique, so we include workflow context.
     */
    private val activitySpans = ConcurrentHashMap<String, SpanWithContext>()

    // Workflow span operations

    /**
     * Stores a workflow span with context for later retrieval.
     *
     * @param runId The workflow run ID
     * @param span The span to store
     * @param workflowType The workflow type (may be null for non-initialize activations)
     * @param taskQueue The task queue name
     * @param namespace The namespace
     */
    fun putWorkflowSpan(
        runId: String,
        span: Span,
        workflowType: String?,
        taskQueue: String,
        namespace: String,
    ) {
        workflowSpans[runId] =
            SpanWithContext(
                span = span,
                workflowType = workflowType,
                taskQueue = taskQueue,
                namespace = namespace,
            )
    }

    /**
     * Retrieves and removes a workflow span with its context.
     *
     * @param runId The workflow run ID
     * @return The span with context, or null if not found
     */
    fun removeWorkflowSpan(runId: String): SpanWithContext? = workflowSpans.remove(runId)

    // Activity span operations

    /**
     * Stores an activity span with context for later retrieval.
     *
     * @param workflowId The workflow ID that scheduled the activity
     * @param runId The workflow run ID
     * @param activityId The activity ID
     * @param span The span to store
     * @param activityType The activity type name
     * @param taskQueue The task queue name
     * @param namespace The namespace
     */
    fun putActivitySpan(
        workflowId: String,
        runId: String,
        activityId: String,
        span: Span,
        activityType: String,
        taskQueue: String,
        namespace: String,
    ) {
        val key = makeActivityKey(workflowId, runId, activityId)
        activitySpans[key] =
            SpanWithContext(
                span = span,
                activityType = activityType,
                workflowId = workflowId,
                activityId = activityId,
                taskQueue = taskQueue,
                namespace = namespace,
            )
    }

    /**
     * Retrieves and removes an activity span with its context.
     *
     * @param workflowId The workflow ID that scheduled the activity
     * @param runId The workflow run ID
     * @param activityId The activity ID
     * @return The span with context, or null if not found
     */
    fun removeActivitySpan(
        workflowId: String,
        runId: String,
        activityId: String,
    ): SpanWithContext? {
        val key = makeActivityKey(workflowId, runId, activityId)
        return activitySpans.remove(key)
    }

    private fun makeActivityKey(
        workflowId: String,
        runId: String,
        activityId: String,
    ): String = "$workflowId:$runId:$activityId"
}
