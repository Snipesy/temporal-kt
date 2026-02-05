package com.surrealdev.temporal.common.exceptions

/**
 * Exception thrown within an activity when that activity is cancelled.
 *
 * Use pattern matching to handle different cancellation reasons:
 * ```kotlin
 * catch (e: ActivityCancelledException) {
 *     when (e) {
 *         is ActivityCancelledException.TimedOut -> // handle timeout
 *         is ActivityCancelledException.WorkerShutdown -> // cleanup before shutdown
 *         else -> // handle other cancellations
 *     }
 * }
 * ```
 */
sealed class ActivityCancelledException(
    message: String,
) : TemporalCancellationException(message) {
    /** Activity no longer exists on the server (may have already completed). */
    class NotFound(
        message: String = "Activity not found",
    ) : ActivityCancelledException(message)

    /** Activity was explicitly cancelled by the workflow or user. */
    class Cancelled(
        message: String = "Activity was cancelled",
    ) : ActivityCancelledException(message)

    /** Activity exceeded its timeout. */
    class TimedOut(
        message: String = "Activity timed out",
    ) : ActivityCancelledException(message)

    /** Worker is shutting down and the graceful timeout has elapsed. */
    class WorkerShutdown(
        message: String = "Worker is shutting down",
    ) : ActivityCancelledException(message)

    /** Activity was paused. */
    class Paused(
        message: String = "Activity was paused",
    ) : ActivityCancelledException(message)

    /** Activity was reset. */
    class Reset(
        message: String = "Activity was reset",
    ) : ActivityCancelledException(message)
}
