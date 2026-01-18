package com.surrealdev.temporal.activity

import kotlinx.datetime.Instant

/**
 * Context available within an activity execution.
 *
 * This context provides access to activity information and operations like
 * heartbeating and cancellation checking.
 *
 * Usage:
 * ```kotlin
 * interface MyActivity {
 *     suspend fun ActivityContext.greet(name: String): String
 * }
 * ```
 */
interface ActivityContext {
    /**
     * Information about the currently executing activity.
     */
    val info: ActivityInfo

    /**
     * Reports progress of a long-running activity.
     *
     * Call this periodically for activities that take longer than
     * the heartbeat timeout. The details can be retrieved if the
     * activity is retried.
     *
     * @param details Progress details to record
     */
    suspend fun heartbeat(details: Any? = null)

    /**
     * Checks if cancellation has been requested for this activity.
     *
     * Activities should check this periodically and exit gracefully
     * when true.
     */
    val isCancellationRequested: Boolean

    /**
     * Throws [ActivityCancelledException] if cancellation has been requested.
     *
     * Convenience method for activities that want to exit immediately on cancellation.
     */
    fun ensureNotCancelled()
}

/**
 * Information about the currently executing activity.
 */
data class ActivityInfo(
    /** Unique identifier for this activity execution. */
    val activityId: String,
    /** The activity type name. */
    val activityType: String,
    /** The task queue this activity is running on. */
    val taskQueue: String,
    /** Attempt number (1-based). */
    val attempt: Int,
    /** When this activity execution started. */
    val startTime: Instant,
    /** When this activity is scheduled to timeout. */
    val deadline: Instant?,
    /** Heartbeat details from the previous attempt, if any. */
    val heartbeatDetails: Any?,
    /** The workflow that scheduled this activity. */
    val workflowInfo: ActivityWorkflowInfo,
)

/**
 * Information about the workflow that scheduled an activity.
 */
data class ActivityWorkflowInfo(
    /** Workflow ID of the parent workflow. */
    val workflowId: String,
    /** Run ID of the parent workflow. */
    val runId: String,
    /** Workflow type of the parent workflow. */
    val workflowType: String,
    /** Namespace of the parent workflow. */
    val namespace: String,
)

/**
 * Exception thrown when an activity is cancelled.
 */
class ActivityCancelledException(
    message: String = "Activity was cancelled",
) : RuntimeException(message)
