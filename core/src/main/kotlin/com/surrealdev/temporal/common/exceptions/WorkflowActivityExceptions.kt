package com.surrealdev.temporal.common.exceptions

/**
 * Base exception for activity failures as viewed from within workflow code.
 */
sealed class WorkflowActivityException(
    message: String?,
    val activityType: String,
    val activityId: String,
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause)

/**
 * Thrown when an activity fails due to an application error.
 *
 * Contains the full Temporal failure hierarchy for debugging.
 * The [applicationFailure] is extracted from the [cause] chain when
 * the activity failed with an [ApplicationFailure].
 */
class WorkflowActivityFailureException(
    message: String?,
    activityType: String,
    activityId: String,
    /** The failure type (e.g., "ApplicationFailure", "TimeoutFailure"). */
    val failureType: String,
    /** Retry state indicating why retries stopped. */
    val retryState: ActivityRetryState,
    cause: Throwable? = null,
) : WorkflowActivityException(message, activityType, activityId, cause) {
    /** The application failure details, if the activity failed with an [ApplicationFailure]. */
    val applicationFailure: ApplicationFailure?
        get() =
            generateSequence(cause) { it.cause }
                .filterIsInstance<ApplicationFailure>()
                .firstOrNull()
}

/**
 * Thrown when an activity times out.
 */
class WorkflowActivityTimeoutException(
    message: String?,
    activityType: String,
    activityId: String,
    /** Which timeout was exceeded. */
    val timeoutType: ActivityTimeoutType,
    cause: Throwable? = null,
) : WorkflowActivityException(message, activityType, activityId, cause)

/**
 * Thrown when an activity is cancelled.
 */
class WorkflowActivityCancelledException(
    message: String = "Activity was cancelled",
    activityType: String = "",
    activityId: String = "",
    cause: Throwable? = null,
) : WorkflowActivityException(message, activityType, activityId, cause)

/**
 * Indicates why activity retries stopped.
 *
 * Maps to [io.temporal.api.enums.v1.RetryState].
 */
enum class ActivityRetryState {
    UNSPECIFIED,
    IN_PROGRESS,
    NON_RETRYABLE_FAILURE,
    TIMEOUT,
    MAXIMUM_ATTEMPTS_REACHED,
    RETRY_POLICY_NOT_SET,
    INTERNAL_SERVER_ERROR,
    CANCEL_REQUESTED,
}

/**
 * Types of activity timeouts.
 *
 * Maps to [io.temporal.api.enums.v1.TimeoutType].
 */
enum class ActivityTimeoutType {
    SCHEDULE_TO_START,
    START_TO_CLOSE,
    SCHEDULE_TO_CLOSE,
    HEARTBEAT,
}
