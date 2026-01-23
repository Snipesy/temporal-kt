package com.surrealdev.temporal.workflow

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

/**
 * Application-level failure details from an activity.
 */
data class ApplicationFailure(
    val type: String,
    val message: String?,
    val nonRetryable: Boolean,
    val details: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ApplicationFailure

        if (type != other.type) return false
        if (message != other.message) return false
        if (nonRetryable != other.nonRetryable) return false
        if (details != null) {
            if (other.details == null) return false
            if (!details.contentEquals(other.details)) return false
        } else if (other.details != null) {
            return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + nonRetryable.hashCode()
        result = 31 * result + (details?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Indicates why activity retries stopped.
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
 */
enum class ActivityTimeoutType {
    SCHEDULE_TO_START,
    START_TO_CLOSE,
    SCHEDULE_TO_CLOSE,
    HEARTBEAT,
}
