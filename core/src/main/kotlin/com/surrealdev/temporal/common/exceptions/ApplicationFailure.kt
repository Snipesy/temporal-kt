package com.surrealdev.temporal.common.exceptions

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.PayloadSerializer
import io.temporal.api.failure.v1.Failure
import kotlin.reflect.KType
import kotlin.reflect.typeOf
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
 * ## Typed Details
 *
 * Use the reified factory methods to attach typed details that flow through
 * the codec pipeline (compression, encryption, etc.):
 *
 * ```kotlin
 * throw ApplicationFailure.failure<ErrorInfo>(
 *     message = "Validation failed",
 *     type = "ValidationError",
 *     detail = ErrorInfo(code = 400, field = "email"),
 * )
 * ```
 *
 * On the receive side, deserialize details back:
 * ```kotlin
 * val info = failure.detail<ErrorInfo>(serializer)
 * ```
 *
 * ## Inspecting on the Receive Side
 *
 * When catching [WorkflowActivityFailureException] or
 * [ChildWorkflowFailureException],
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
 * When thrown, the [type], [isNonRetryable], [details], [nextRetryDelay], and
 * [category] are propagated to Temporal via [io.temporal.api.failure.v1.ApplicationFailureInfo].
 */
class ApplicationFailure private constructor(
    message: String?,
    /** The type/category of the failure (e.g., "ValidationError", "NotFound"). */
    val type: String,
    /** Whether this failure should not be retried. */
    val isNonRetryable: Boolean,
    /**
     * Raw typed values stored at throw time. Serialized later at the outbound boundary
     * where the serializer + codec are available.
     */
    @PublishedApi internal val rawDetails: List<Pair<KType, Any?>> = emptyList(),
    /**
     * Decoded payloads from inbound. Available on the receive side after codec decoding.
     * Use [detail] or [detailList] to deserialize to typed objects.
     */
    val details: TemporalPayloads = TemporalPayloads.EMPTY,
    /** Optional explicit delay before the next retry attempt. */
    val nextRetryDelay: Duration? = null,
    /** Error category affecting logging and metrics. */
    val category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause) {
    /**
     * The underlying proto Failure object, available when this exception was reconstructed
     * from a remote failure. Null for locally created failures.
     */
    @InternalTemporalApi
    internal var protoFailure: Failure? = null

    /**
     * The original stack trace from the remote execution environment, or null if this
     * failure was created locally. Populated when reconstructing failures from proto data.
     */
    val originalStackTrace: String?
        get() = protoFailure?.stackTrace?.ifEmpty { null }

    /**
     * Serializes raw details using the provided serializer.
     * Called at the outbound boundary (workflow completion, activity completion)
     * where the serializer is available.
     *
     * If [rawDetails] are present (throw side), they are serialized.
     * Otherwise, returns the already-decoded [details] (receive side / pre-serialized).
     */
    @InternalTemporalApi
    fun serializeDetails(serializer: PayloadSerializer): TemporalPayloads {
        if (rawDetails.isNotEmpty()) {
            return TemporalPayloads.of(
                rawDetails.map { (type, value) ->
                    serializer.serialize(type, value)
                },
            )
        }
        return details
    }

    /**
     * Deserializes the first detail payload to the specified type.
     *
     * @return The deserialized detail, or null if no details are present.
     */
    inline fun <reified T> detail(serializer: PayloadSerializer): T? {
        if (details.isEmpty) return null
        return serializer.deserialize(typeOf<T>(), details[0]) as T
    }

    /**
     * Deserializes the detail payload at [index] to the specified type.
     *
     * @param index The zero-based index of the detail to deserialize.
     * @return The deserialized detail, or null if [index] is out of bounds.
     */
    inline fun <reified T> detail(
        index: Int,
        serializer: PayloadSerializer,
    ): T? {
        if (index !in details.indices) return null
        return serializer.deserialize(typeOf<T>(), details[index]) as T
    }

    /**
     * Returns the number of detail payloads attached to this failure.
     */
    val detailSize: Int
        get() = details.size

    /**
     * Deserializes all detail payloads to a list of the specified type.
     *
     * @return A list of deserialized details.
     */
    inline fun <reified T> detailList(serializer: PayloadSerializer): List<T> =
        details.payloads.map { serializer.deserialize(typeOf<T>(), it) as T }

    companion object {
        /**
         * Creates a retryable application failure.
         *
         * The activity/workflow will be retried according to its retry policy.
         *
         * @param message The error message
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun failure(
            message: String,
            type: String = "ApplicationFailure",
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationFailure(
            message = message,
            type = type,
            isNonRetryable = false,
            category = category,
            cause = cause,
        )

        /**
         * Creates a retryable application failure with pre-serialized detail payloads.
         *
         * Prefer the reified extension [ApplicationFailure.Companion.failure] with a `detail`
         * parameter instead, which handles serialization automatically through the codec pipeline.
         *
         * @param message The error message
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param details Pre-serialized detail payloads
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @InternalTemporalApi
        fun failure(
            message: String,
            type: String = "ApplicationFailure",
            details: TemporalPayloads,
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
         * Creates a non-retryable application failure.
         *
         * The activity/workflow will NOT be retried regardless of retry policy.
         * Use this for permanent failures like validation errors or
         * business rule violations.
         *
         * @param message The error message
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun nonRetryable(
            message: String,
            type: String = "ApplicationFailure",
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationFailure(
            message = message,
            type = type,
            isNonRetryable = true,
            category = category,
            cause = cause,
        )

        /**
         * Creates a non-retryable application failure with pre-serialized detail payloads.
         *
         * Prefer the reified extension [ApplicationFailure.Companion.nonRetryable] with a `detail`
         * parameter instead, which handles serialization automatically through the codec pipeline.
         *
         * @param message The error message
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param details Pre-serialized detail payloads
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @InternalTemporalApi
        fun nonRetryableWithPayloads(
            message: String,
            type: String = "ApplicationFailure",
            details: TemporalPayloads,
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
         * Creates a retryable failure with explicit next retry delay.
         *
         * Overrides the calculated backoff from retry policy for the next attempt.
         *
         * @param message The error message
         * @param nextRetryDelay The delay before the next retry attempt
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @JvmOverloads
        fun failureWithDelay(
            message: String,
            nextRetryDelay: Duration,
            type: String = "ApplicationFailure",
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationFailure(
            message = message,
            type = type,
            isNonRetryable = false,
            nextRetryDelay = nextRetryDelay,
            category = category,
            cause = cause,
        )

        /**
         * Creates a retryable failure with explicit next retry delay and pre-serialized detail payloads.
         *
         * Prefer the reified extension [ApplicationFailure.Companion.failureWithDelay] with a `detail`
         * parameter instead, which handles serialization automatically through the codec pipeline.
         *
         * @param message The error message
         * @param nextRetryDelay The delay before the next retry attempt
         * @param type The failure type/category (defaults to "ApplicationFailure")
         * @param details Pre-serialized detail payloads
         * @param category Error category affecting logging and metrics
         * @param cause Optional cause exception
         */
        @JvmStatic
        @InternalTemporalApi
        fun failureWithDelayWithPayloads(
            message: String,
            nextRetryDelay: Duration,
            type: String = "ApplicationFailure",
            details: TemporalPayloads,
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
         * Internal factory used by reified extension functions.
         * Stores raw typed values for deferred serialization at the outbound boundary.
         */
        @PublishedApi
        internal fun failureWithRawDetails(
            message: String,
            type: String,
            rawDetails: List<Pair<KType, Any?>>,
            isNonRetryable: Boolean,
            nextRetryDelay: Duration?,
            category: ApplicationErrorCategory,
            cause: Throwable?,
        ) = ApplicationFailure(
            message = message,
            type = type,
            isNonRetryable = isNonRetryable,
            rawDetails = rawDetails,
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
        fun fromProtoWithPayloads(
            type: String,
            message: String?,
            isNonRetryable: Boolean,
            details: TemporalPayloads = TemporalPayloads.EMPTY,
            category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
            cause: Throwable? = null,
        ) = ApplicationFailure(
            message = message,
            type = type,
            isNonRetryable = isNonRetryable,
            details = details,
            category = category,
            cause = cause,
        )
    }
}

// =============================================================================
// Reified Companion Extensions
// =============================================================================

/**
 * Creates a retryable application failure with a single typed detail.
 *
 * The detail value will be serialized at the outbound boundary using the
 * configured [PayloadSerializer] and encoded through the [com.surrealdev.temporal.serialization.PayloadCodec].
 * This is the recommended way to attach typed details to a failure.
 *
 * ```kotlin
 * throw ApplicationFailure.failure<ErrorInfo>(
 *     message = "Validation failed",
 *     type = "ValidationError",
 *     detail = ErrorInfo(code = 400, field = "email"),
 * )
 * ```
 *
 * @param T The type of the detail value
 * @param message The error message
 * @param type The failure type/category (defaults to "ApplicationFailure")
 * @param detail The typed detail value to attach to the failure
 * @param category Error category affecting logging and metrics
 * @param cause Optional cause exception
 */
inline fun <reified T> ApplicationFailure.Companion.failure(
    message: String,
    type: String = "ApplicationFailure",
    detail: T,
    category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
    cause: Throwable? = null,
) = failureWithRawDetails(
    message = message,
    type = type,
    rawDetails = listOf(typeOf<T>() to detail),
    isNonRetryable = false,
    nextRetryDelay = null,
    category = category,
    cause = cause,
)

/**
 * Creates a non-retryable application failure with a single typed detail.
 *
 * The detail value will be serialized at the outbound boundary using the
 * configured [PayloadSerializer] and encoded through the [com.surrealdev.temporal.serialization.PayloadCodec].
 * This is the recommended way to attach typed details to a non-retryable failure.
 *
 * ```kotlin
 * throw ApplicationFailure.nonRetryable<ErrorInfo>(
 *     message = "Invalid input",
 *     type = "ValidationError",
 *     detail = ErrorInfo(code = 400, field = "email"),
 * )
 * ```
 *
 * @param T The type of the detail value
 * @param message The error message
 * @param type The failure type/category (defaults to "ApplicationFailure")
 * @param detail The typed detail value to attach to the failure
 * @param category Error category affecting logging and metrics
 * @param cause Optional cause exception
 */
inline fun <reified T> ApplicationFailure.Companion.nonRetryable(
    message: String,
    type: String = "ApplicationFailure",
    detail: T,
    category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
    cause: Throwable? = null,
) = failureWithRawDetails(
    message = message,
    type = type,
    rawDetails = listOf(typeOf<T>() to detail),
    isNonRetryable = true,
    nextRetryDelay = null,
    category = category,
    cause = cause,
)

/**
 * Creates a retryable failure with explicit next retry delay and a single typed detail.
 *
 * The detail value will be serialized at the outbound boundary using the
 * configured [PayloadSerializer] and encoded through the [com.surrealdev.temporal.serialization.PayloadCodec].
 * This is the recommended way to attach typed details to a delayed-retry failure.
 *
 * ```kotlin
 * throw ApplicationFailure.failureWithDelay<ErrorInfo>(
 *     message = "Rate limited",
 *     nextRetryDelay = 30.seconds,
 *     type = "RateLimitError",
 *     detail = ErrorInfo(code = 429, retryAfter = 30),
 * )
 * ```
 *
 * @param T The type of the detail value
 * @param message The error message
 * @param nextRetryDelay The delay before the next retry attempt, overriding the retry policy backoff
 * @param type The failure type/category (defaults to "ApplicationFailure")
 * @param detail The typed detail value to attach to the failure
 * @param category Error category affecting logging and metrics
 * @param cause Optional cause exception
 */
inline fun <reified T> ApplicationFailure.Companion.failureWithDelay(
    message: String,
    nextRetryDelay: Duration,
    type: String = "ApplicationFailure",
    detail: T,
    category: ApplicationErrorCategory = ApplicationErrorCategory.UNSPECIFIED,
    cause: Throwable? = null,
) = failureWithRawDetails(
    message = message,
    type = type,
    rawDetails = listOf(typeOf<T>() to detail),
    isNonRetryable = false,
    nextRetryDelay = nextRetryDelay,
    category = category,
    cause = cause,
)
