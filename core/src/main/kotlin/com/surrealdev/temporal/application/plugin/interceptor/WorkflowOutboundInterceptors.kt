package com.surrealdev.temporal.application.plugin.interceptor

import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.ContinueAsNewOptions
import com.surrealdev.temporal.workflow.LocalActivityOptions
import kotlin.time.Duration

/**
 * Input for the ScheduleActivity interceptor.
 *
 * Passed through the interceptor chain when a workflow schedules a remote activity.
 */
data class ScheduleActivityInput(
    val activityType: String,
    val args: TemporalPayloads,
    val options: ActivityOptions,
)

/**
 * Input for the ScheduleLocalActivity interceptor.
 *
 * Passed through the interceptor chain when a workflow schedules a local activity.
 */
data class ScheduleLocalActivityInput(
    val activityType: String,
    val args: TemporalPayloads,
    val options: LocalActivityOptions,
)

/**
 * Input for the StartChildWorkflow interceptor.
 *
 * Passed through the interceptor chain when a workflow starts a child workflow.
 */
data class StartChildWorkflowInput(
    val workflowType: String,
    val args: TemporalPayloads,
    val options: ChildWorkflowOptions,
)

/**
 * Input for the Sleep interceptor.
 *
 * Passed through the interceptor chain when a workflow calls sleep/timer.
 */
data class SleepInput(
    val duration: Duration,
)

/**
 * Input for the SignalExternalWorkflow interceptor.
 *
 * Passed through the interceptor chain when a workflow signals an external workflow.
 */
data class SignalExternalInput(
    val workflowId: String,
    val runId: String?,
    val signalName: String,
    val args: TemporalPayloads,
)

/**
 * Input for the CancelExternalWorkflow interceptor.
 *
 * Passed through the interceptor chain when a workflow cancels an external workflow.
 */
data class CancelExternalInput(
    val workflowId: String,
    val runId: String?,
    val reason: String,
)

/**
 * Input for the ContinueAsNew interceptor.
 *
 * Passed through the interceptor chain when a workflow continues as new.
 */
data class ContinueAsNewInput(
    val options: ContinueAsNewOptions,
    val args: TemporalPayloads,
)
