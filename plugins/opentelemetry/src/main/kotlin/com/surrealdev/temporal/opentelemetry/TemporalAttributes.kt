package com.surrealdev.temporal.opentelemetry

import io.opentelemetry.api.common.AttributeKey

/**
 * Semantic attribute keys for Temporal spans and metrics.
 *
 * These follow Temporal SDK conventions and OpenTelemetry semantic conventions.
 */
object TemporalAttributes {
    // Workflow attributes
    val WORKFLOW_TYPE: AttributeKey<String> = AttributeKey.stringKey("temporal.workflow.type")
    val WORKFLOW_ID: AttributeKey<String> = AttributeKey.stringKey("temporal.workflow.id")
    val RUN_ID: AttributeKey<String> = AttributeKey.stringKey("temporal.run.id")

    // Activity attributes
    val ACTIVITY_TYPE: AttributeKey<String> = AttributeKey.stringKey("temporal.activity.type")
    val ACTIVITY_ID: AttributeKey<String> = AttributeKey.stringKey("temporal.activity.id")

    // Common attributes
    val TASK_QUEUE: AttributeKey<String> = AttributeKey.stringKey("temporal.task_queue")
    val NAMESPACE: AttributeKey<String> = AttributeKey.stringKey("temporal.namespace")

    // Status attribute for metrics
    val STATUS: AttributeKey<String> = AttributeKey.stringKey("status")

    // Status values
    const val STATUS_SUCCESS = "success"
    const val STATUS_FAILURE = "failure"
    const val STATUS_CANCELLED = "cancelled"
}
