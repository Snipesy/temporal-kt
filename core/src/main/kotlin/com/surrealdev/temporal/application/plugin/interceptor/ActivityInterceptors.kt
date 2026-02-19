package com.surrealdev.temporal.application.plugin.interceptor

import com.surrealdev.temporal.application.plugin.InterceptorHook
import com.surrealdev.temporal.common.TemporalPayload

// ==================== Interceptor Hooks ====================

object ExecuteActivity : InterceptorHook<ExecuteActivityInput, Any?> {
    override val name = "ExecuteActivity"
}

object Heartbeat : InterceptorHook<HeartbeatInput, Unit> {
    override val name = "Heartbeat"
}

// ==================== Input Types ====================

/**
 * Input for the ExecuteActivity interceptor.
 *
 * Passed through the interceptor chain when an activity is about to be executed.
 */
data class ExecuteActivityInput(
    val activityType: String,
    val activityId: String,
    val workflowId: String,
    val runId: String,
    val taskQueue: String,
    val namespace: String,
    val headers: Map<String, TemporalPayload>,
)

/**
 * Input for the Heartbeat interceptor.
 *
 * Passed through the interceptor chain when an activity sends a heartbeat.
 * The [details] field contains the user-provided heartbeat payload before codec encoding.
 * Codec encoding happens after the interceptor chain completes.
 */
data class HeartbeatInput(
    val details: TemporalPayload?,
    val activityType: String,
)
