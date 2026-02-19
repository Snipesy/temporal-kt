package com.surrealdev.temporal.opentelemetry

import io.opentelemetry.api.common.AttributeKey

/**
 * Semantic attribute keys for Temporal spans and metrics.
 *
 * Span attributes follow the official Temporal SDK naming conventions (.NET/Python).
 * Metric attributes use the `temporal.*` namespace for cardinality-bounded dimensions.
 */
object TemporalAttributes {
    // ==================== Span Attributes ====================
    // These match the official Temporal SDK naming conventions.
    val WORKFLOW_ID: AttributeKey<String> = AttributeKey.stringKey("temporalWorkflowID")
    val RUN_ID: AttributeKey<String> = AttributeKey.stringKey("temporalRunID")
    val ACTIVITY_ID: AttributeKey<String> = AttributeKey.stringKey("temporalActivityID")
    val UPDATE_ID: AttributeKey<String> = AttributeKey.stringKey("temporalUpdateID")

    // ==================== Metric Attributes ====================
    // These use dotted namespace for metric dimensions (bounded cardinality).

    val WORKFLOW_TYPE: AttributeKey<String> = AttributeKey.stringKey("temporal.workflow.type")
    val ACTIVITY_TYPE: AttributeKey<String> = AttributeKey.stringKey("temporal.activity.type")
    val TASK_QUEUE: AttributeKey<String> = AttributeKey.stringKey("temporal.task_queue")
    val NAMESPACE: AttributeKey<String> = AttributeKey.stringKey("temporal.namespace")
    val STATUS: AttributeKey<String> = AttributeKey.stringKey("status")

    // Status values for metrics
    const val STATUS_SUCCESS = "success"
    const val STATUS_FAILURE = "failure"
    const val STATUS_CANCELLED = "cancelled"
}
