package com.surrealdev.temporal.application.plugin.interceptor

import com.surrealdev.temporal.common.TemporalPayload

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
