package com.surrealdev.temporal.common.exceptions

import com.surrealdev.temporal.annotation.InternalTemporalApi
import kotlin.time.Duration

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
 * Application-level failure that can be thrown from activities or workflows,
 * and is also reconstructed on the reception side when inspecting activity or
 * child workflow failures.
 *
 * ## Throwing from Activities
 *
 * Use factory methods to create instances:
 * - [failure] - Retryable failure (will be retried per retry policy)
 * - [nonRetryable] - Non-retryable failure (stops retries immediately)
 * - [failureWithDelay] - Retryable failure with explicit next retry delay
 *
 * ```kotlin
 * @Activity
 * suspend fun processPayment(cardNumber: String): Receipt {
 *     if (!isValidCard(cardNumber)) {
 *         throw ApplicationFailure.nonRetryable(
 *             message = "Invalid card format",
 *             type = "ValidationError",
 *         )
 *     }
 *     // ...
 * }
 * ```
 *
 * ## Throwing from Workflows
 * ```kotlin
 * @WorkflowRun
 * suspend fun WorkflowContext.run(): String {
 *     if (invalidState) {
 *         throw ApplicationFailure.nonRetryable(
 *             message = "Workflow in invalid state",
 *             type = "InvalidStateError",
 *         )
 *     }
 *     // ...
 * }
 * ```
 *
 * ## Inspecting on the Receive Side
 *
 * When catching [WorkflowActivityFailureException] or
 * [com.surrealdev.temporal.workflow.ChildWorkflowFailureException],
 * the [ApplicationFailure] is available via the `applicationFailure` property:
 *
 * ```kotlin
 * try {
 *     activity.result()
 * } catch (e: WorkflowActivityFailureException) {
 *     val failure = e.applicationFailure
 *     when (failure?.type) {
 *         "ValidationError" -> handleValidation(failure)
 *         "NotFound" -> handleNotFound(failure)
 *         else -> rethrow(e)
 *     }
 * }
 * ```
 *
 * ## Details
 * The [details] parameter accepts string values for additional error context.
 * These are encoded as JSON string payloads and can be retrieved on the
 * receiving side via [encodedDetails].
 *
 * ```kotlin
 * throw ApplicationFailure.failure(
 *     message = "Validation failed",
 *     type = "ValidationError",
 *     details = listOf("field: email", "expected: valid email", "got: invalid@"),
 * )
 * ```
 *
 * When thrown, the [type], [isNonRetryable], [details], [nextRetryDelay], and
 * [category] are propagated to Temporal via [io.temporal.api.failure.v1.ApplicationFailureInfo].
 */
class ApplicationFailure private constructor(
    message: String?,
    /** The type/category of the failure (e.g., "ValidationError", "NotFound"). */
    val type: String,
    /** Whether this failure should not be retried. */
    val isNonRetryable: Boolean,
    /** String details to include with the failure for debugging context. */
    val details: List<String> = emptyList(),
    /** Raw encoded details from proto, available on the receive side. Null when thrown directly. */
    val encodedDetails: ByteArray? = null,
    /** Optional explicit delay before the next retry attempt. */
    val nextRetryDelay: Duration? = null,
    /** Error category affecting logging and metrics. */
    val category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause) {
    companion object {
        /**
         * Creates a retryable application failure.
         *
         * The activity/workflow will be retried according to its retry policy.
         *
         * @param message The error message
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param details Optional string details for debugging context
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun failure(
            message: String,
            type: String = "ApplicationFailure",
            details: List<String> = emptyList(),
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationFailure(
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
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param details Optional string details for debugging context
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun nonRetryable(
            message: String,
            type: String = "ApplicationFailure",
            details: List<String> = emptyList(),
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationFailure(
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
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param details Optional string details for debugging context
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun failureWithDelay(
            message: String,
            nextRetryDelay: Duration,
            type: String = "ApplicationFailure",
            details: List<String> = emptyList(),
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationFailure(
            message = message,
            type = type,
            isNonRetryable = false,
            details = details,
            nextRetryDelay = nextRetryDelay,
            category = category,
            cause = cause,
        )

        /**
         * Reconstructs an [ApplicationFailure] from proto data on the receive side.
         *
         * This is used internally by the SDK when extracting failure information
         * from Temporal's proto [io.temporal.api.failure.v1.ApplicationFailureInfo].
         */
        @InternalTemporalApi
        fun fromProto(
            type: String,
            message: String?,
            isNonRetryable: Boolean,
            encodedDetails: ByteArray? = null,
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationFailure(
            message = message,
            type = type,
            isNonRetryable = isNonRetryable,
            encodedDetails = encodedDetails,
            category = category,
            cause = cause,
        )
    }
}
