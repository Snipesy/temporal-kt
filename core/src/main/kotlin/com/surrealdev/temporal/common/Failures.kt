package com.surrealdev.temporal.common

import kotlin.time.Duration

// =============================================================================
// Temporal Failure Hierarchy
// =============================================================================

/**
 * Base class for all Temporal failure exceptions.
 *
 * This sealed class hierarchy enables exhaustive `when` matching and provides
 * a common base for failures that can occur during workflow or activity execution.
 *
 * Example:
 * ```kotlin
 * try {
 *     activity.execute()
 * } catch (e: TemporalFailure) {
 *     when (e) {
 *         is ApplicationError -> handleAppError(e)
 *         // Future: CancelledError, TimeoutError, etc.
 *     }
 * }
 * ```
 */
sealed class TemporalFailure(
    message: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Category for application errors, affecting logging and metrics behavior.
 *
 * Maps to [io.temporal.api.enums.v1.ApplicationErrorCategory].
 */
enum class ApplicationErrorCategory {
    /** Default category - normal error logging and metrics. */
    UNSPECIFIED,

    /**
     * Expected business error with minimal severity.
     *
     * BENIGN errors:
     * - Emit DEBUG-level logs instead of ERROR
     * - Do not increment error metrics
     *
     * Use for expected business outcomes like "user not found" or
     * "insufficient balance" that are valid application states,
     * not operational errors.
     */
    BENIGN,
}

/**
 * Application-level failure that can be thrown from activities or workflows.
 *
 * Use factory methods to create instances:
 * - [failure] - Retryable failure (will be retried per retry policy)
 * - [nonRetryable] - Non-retryable failure (stops retries immediately)
 * - [failureWithDelay] - Retryable failure with explicit next retry delay
 *
 * ## Usage in Activities
 * ```kotlin
 * @Activity
 * suspend fun processPayment(cardNumber: String): Receipt {
 *     if (!isValidCard(cardNumber)) {
 *         throw ApplicationError.nonRetryable(
 *             message = "Invalid card format",
 *             type = "ValidationError",
 *         )
 *     }
 *     // ...
 * }
 * ```
 *
 * ## Usage in Workflows
 * ```kotlin
 * @WorkflowRun
 * suspend fun WorkflowContext.run(): String {
 *     if (invalidState) {
 *         throw ApplicationError.nonRetryable(
 *             message = "Workflow in invalid state",
 *             type = "InvalidStateError",
 *         )
 *     }
 *     // ...
 * }
 * ```
 *
 * ## Details
 * The [details] parameter accepts string values for additional error context.
 * These are encoded as JSON string payloads and can be retrieved on the
 * receiving side.
 *
 * ```kotlin
 * throw ApplicationError.failure(
 *     message = "Validation failed",
 *     type = "ValidationError",
 *     details = listOf("field: email", "expected: valid email", "got: invalid@"),
 * )
 * ```
 *
 * When thrown, the [type], [isNonRetryable], [details], [nextRetryDelay], and
 * [category] are propagated to Temporal via [io.temporal.api.failure.v1.ApplicationFailureInfo].
 */
class ApplicationError private constructor(
    message: String?,
    /** The type/category of the failure (e.g., "ValidationError", "NotFound"). */
    val type: String,
    /** Whether this failure should not be retried. */
    val isNonRetryable: Boolean,
    /** String details to include with the failure for debugging context. */
    val details: List<String> = emptyList(),
    /** Optional explicit delay before the next retry attempt. */
    val nextRetryDelay: Duration? = null,
    /** Error category affecting logging and metrics. */
    val category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
    cause: Throwable? = null,
) : TemporalFailure(message, cause) {
    companion object {
        /**
         * Creates a retryable application failure.
         *
         * The activity/workflow will be retried according to its retry policy.
         *
         * @param message The error message
         * @param type The failure type/category (defaults to "ApplicationError")
         * @param details Optional string details for debugging context
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun failure(
            message: String,
            type: String = "ApplicationError",
            details: List<String> = emptyList(),
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationError(
            message = message,
            type = type,
            isNonRetryable = false,
            details = details,
            category = category,
            cause = cause,
        )

        /**
         * Creates a retryable application failure with vararg details.
         *
         * @param message The error message
         * @param type The failure type/category
         * @param details String details for debugging context
         */
        @JvmStatic
        fun failure(
            message: String,
            type: String,
            vararg details: String,
        ) = failure(message = message, type = type, details = details.toList())

        /**
         * Creates a non-retryable application failure.
         *
         * The activity/workflow will NOT be retried regardless of retry policy.
         * Use this for permanent failures like validation errors or
         * business rule violations.
         *
         * @param message The error message
         * @param type The failure type/category (defaults to "ApplicationError")
         * @param details Optional string details for debugging context
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun nonRetryable(
            message: String,
            type: String = "ApplicationError",
            details: List<String> = emptyList(),
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationError(
            message = message,
            type = type,
            isNonRetryable = true,
            details = details,
            category = category,
            cause = cause,
        )

        /**
         * Creates a non-retryable application failure with vararg details.
         *
         * @param message The error message
         * @param type The failure type/category
         * @param details String details for debugging context
         */
        @JvmStatic
        fun nonRetryable(
            message: String,
            type: String,
            vararg details: String,
        ) = nonRetryable(message = message, type = type, details = details.toList())

        /**
         * Creates a retryable failure with explicit next retry delay.
         *
         * Overrides the calculated backoff from retry policy for the next attempt.
         *
         * @param message The error message
         * @param nextRetryDelay The delay before the next retry attempt
         * @param type The failure type/category (defaults to "ApplicationError")
         * @param details Optional string details for debugging context
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun failureWithDelay(
            message: String,
            nextRetryDelay: Duration,
            type: String = "ApplicationError",
            details: List<String> = emptyList(),
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationError(
            message = message,
            type = type,
            isNonRetryable = false,
            details = details,
            nextRetryDelay = nextRetryDelay,
            category = category,
            cause = cause,
        )
    }
}

// =============================================================================
// Failure Data Classes (for receiving failure info on workflow side)
// =============================================================================

/**
 * Application-level failure details received from an activity or child workflow.
 *
 * This data class captures failure information extracted from Temporal's
 * proto [io.temporal.api.failure.v1.ApplicationFailureInfo].
 *
 * Use this to inspect failure details when catching [ActivityFailureException]
 * or [ChildWorkflowFailureException]:
 *
 * ```kotlin
 * try {
 *     activity.result()
 * } catch (e: ActivityFailureException) {
 *     val failure = e.applicationFailure
 *     when (failure?.type) {
 *         "ValidationError" -> handleValidation(failure)
 *         "NotFound" -> handleNotFound(failure)
 *         else -> rethrow(e)
 *     }
 * }
 * ```
 */
data class ApplicationFailure(
    /** The type/category of the failure (e.g., "ValidationError", "NotFound"). */
    val type: String,
    /** The failure message. */
    val message: String?,
    /** Whether this failure was marked as non-retryable. */
    val nonRetryable: Boolean,
    /** Serialized failure details, if any. */
    val details: ByteArray? = null,
    /** Error category affecting logging and metrics. */
    val category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ApplicationFailure

        if (type != other.type) return false
        if (message != other.message) return false
        if (nonRetryable != other.nonRetryable) return false
        if (category != other.category) return false
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
        result = 31 * result + category.hashCode()
        result = 31 * result + (details?.contentHashCode() ?: 0)
        return result
    }
}

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
