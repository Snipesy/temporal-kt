package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.common.ActivityRetryState
import com.surrealdev.temporal.common.ActivityTimeoutType
import com.surrealdev.temporal.common.ApplicationFailure

/**
 * Base exception for activity failures.
 */
sealed class ActivityException(
    message: String?,
    val activityType: String,
    val activityId: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Thrown when an activity fails due to an application error.
 *
 * Contains the full Temporal failure hierarchy for debugging.
 */
class ActivityFailureException(
    message: String?,
    activityType: String,
    activityId: String,
    /** The failure type (e.g., "ApplicationFailure", "TimeoutFailure"). */
    val failureType: String,
    /** Retry state indicating why retries stopped. */
    val retryState: ActivityRetryState,
    /** The original failure from the activity, if available. */
    val applicationFailure: ApplicationFailure? = null,
    cause: Throwable? = null,
) : ActivityException(message, activityType, activityId, cause)

/**
 * Thrown when an activity times out.
 */
class ActivityTimeoutException(
    message: String?,
    activityType: String,
    activityId: String,
    /** Which timeout was exceeded. */
    val timeoutType: ActivityTimeoutType,
    cause: Throwable? = null,
) : ActivityException(message, activityType, activityId, cause)

/**
 * Thrown when an activity is cancelled.
 */
class ActivityCancelledException(
    message: String = "Activity was cancelled",
    activityType: String = "",
    activityId: String = "",
    cause: Throwable? = null,
) : ActivityException(message, activityType, activityId, cause)
