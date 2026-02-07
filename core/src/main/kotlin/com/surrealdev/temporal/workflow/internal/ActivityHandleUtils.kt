package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.ActivityRetryState
import com.surrealdev.temporal.common.exceptions.ActivityTimeoutType
import com.surrealdev.temporal.common.exceptions.ApplicationErrorCategory
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.common.exceptions.PayloadProcessingException
import com.surrealdev.temporal.common.exceptions.RemoteException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityTimeoutException
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.safeDecode
import com.surrealdev.temporal.serialization.safeEncode
import io.temporal.api.failure.v1.ApplicationFailureInfo
import io.temporal.api.failure.v1.Failure
import org.slf4j.LoggerFactory
import kotlin.time.toJavaDuration

private val logger = LoggerFactory.getLogger("com.surrealdev.temporal.workflow.internal.ActivityHandleUtils")

/*
 * Shared utility functions for activity handle implementations.
 *
 * These functions handle common proto-to-domain conversions used by both
 * [RemoteActivityHandleImpl] and [LocalActivityHandleImpl].
 */

/**
 * Maps proto RetryState to Kotlin [ActivityRetryState] enum.
 */
internal fun mapRetryState(protoState: io.temporal.api.enums.v1.RetryState): ActivityRetryState =
    when (protoState) {
        io.temporal.api.enums.v1.RetryState.RETRY_STATE_UNSPECIFIED -> {
            ActivityRetryState.UNSPECIFIED
        }

        io.temporal.api.enums.v1.RetryState.RETRY_STATE_IN_PROGRESS -> {
            ActivityRetryState.IN_PROGRESS
        }

        io.temporal.api.enums.v1.RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE -> {
            ActivityRetryState.NON_RETRYABLE_FAILURE
        }

        io.temporal.api.enums.v1.RetryState.RETRY_STATE_TIMEOUT -> {
            ActivityRetryState.TIMEOUT
        }

        io.temporal.api.enums.v1.RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED -> {
            ActivityRetryState.MAXIMUM_ATTEMPTS_REACHED
        }

        io.temporal.api.enums.v1.RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET -> {
            ActivityRetryState.RETRY_POLICY_NOT_SET
        }

        io.temporal.api.enums.v1.RetryState.RETRY_STATE_INTERNAL_SERVER_ERROR -> {
            ActivityRetryState.INTERNAL_SERVER_ERROR
        }

        io.temporal.api.enums.v1.RetryState.RETRY_STATE_CANCEL_REQUESTED -> {
            ActivityRetryState.CANCEL_REQUESTED
        }

        else -> {
            ActivityRetryState.UNSPECIFIED
        }
    }

/**
 * Maps proto TimeoutType to Kotlin [ActivityTimeoutType] enum.
 */
internal fun mapTimeoutType(protoType: io.temporal.api.enums.v1.TimeoutType): ActivityTimeoutType =
    when (protoType) {
        io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START -> ActivityTimeoutType.SCHEDULE_TO_START
        io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE -> ActivityTimeoutType.START_TO_CLOSE
        io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE -> ActivityTimeoutType.SCHEDULE_TO_CLOSE
        io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_HEARTBEAT -> ActivityTimeoutType.HEARTBEAT
        else -> ActivityTimeoutType.START_TO_CLOSE // Default fallback
    }

/**
 * Builds an [ApplicationFailure] exception from a proto Failure that has ApplicationFailureInfo,
 * with codec decoding of details.
 *
 * This is the primary path used by activity handle implementations where the codec is available.
 */
@OptIn(InternalTemporalApi::class)
internal suspend fun buildApplicationFailureFromProto(
    failure: Failure,
    codec: PayloadCodec,
    cause: Throwable? = null,
): ApplicationFailure {
    val appInfo = failure.applicationFailureInfo
    val category =
        when (appInfo.category) {
            io.temporal.api.enums.v1.ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN -> {
                ApplicationErrorCategory.BENIGN
            }

            else -> {
                ApplicationErrorCategory.UNSPECIFIED
            }
        }
    val details =
        if (appInfo.hasDetails()) {
            codec.safeDecode(EncodedTemporalPayloads(appInfo.details))
        } else {
            TemporalPayloads.EMPTY
        }
    return ApplicationFailure
        .fromProtoWithPayloads(
            type = appInfo.type ?: "UnknownApplicationFailure",
            message = failure.message,
            isNonRetryable = appInfo.nonRetryable,
            details = details,
            category = category,
            cause = cause,
        ).also { it.protoFailure = failure }
}

/**
 * Recursively builds cause exceptions from proto Failure, with codec decoding.
 *
 * This is the primary path used by activity handle implementations where the codec is available.
 * When a node in the chain has [ApplicationFailureInfo][io.temporal.api.failure.v1.ApplicationFailureInfo],
 * details are decoded through the codec.
 */
internal suspend fun buildCause(
    failure: Failure,
    codec: PayloadCodec,
    depth: Int = 0,
    maxDepth: Int = 20,
): Throwable {
    if (depth >= maxDepth) {
        return RuntimeException(failure.message ?: "Cause failure (max depth reached)")
    }

    val nestedCause =
        if (failure.hasCause()) {
            buildCause(failure.cause, codec, depth + 1, maxDepth)
        } else {
            null
        }

    // If this failure node has ApplicationFailureInfo, create an ApplicationFailure exception
    if (failure.hasApplicationFailureInfo()) {
        return buildApplicationFailureFromProto(failure, codec, cause = nestedCause)
    }

    return RemoteException(
        message = failure.message ?: "Cause failure",
        cause = nestedCause,
    ).also { it.protoFailure = failure }
}

/**
 * Determines the failure type string from a proto Failure.
 */
internal fun determineFailureType(failure: Failure): String =
    when {
        failure.hasApplicationFailureInfo() -> "ApplicationFailure"
        failure.hasCanceledFailureInfo() -> "CanceledFailure"
        failure.hasTerminatedFailureInfo() -> "TerminatedFailure"
        failure.hasServerFailureInfo() -> "ServerFailure"
        failure.hasResetWorkflowFailureInfo() -> "ResetWorkflowFailure"
        failure.hasActivityFailureInfo() -> "ActivityFailure"
        failure.hasChildWorkflowExecutionFailureInfo() -> "ChildWorkflowExecutionFailure"
        else -> "UnknownFailure"
    }

/**
 * Extracts retry state from a proto Failure if it contains ActivityFailureInfo.
 */
internal fun extractRetryState(failure: Failure): ActivityRetryState =
    if (failure.hasActivityFailureInfo()) {
        mapRetryState(failure.activityFailureInfo.retryState)
    } else {
        ActivityRetryState.UNSPECIFIED
    }

/**
 * Builds a [WorkflowActivityException] from a proto [Failure].
 *
 * Returns [WorkflowActivityTimeoutException] for timeouts,
 * [WorkflowActivityFailureException] for all other failures.
 * The cause chain is fully reconstructed through [buildCause] / [buildApplicationFailureFromProto].
 *
 * Shared by [RemoteActivityHandleImpl] and [LocalActivityHandleImpl].
 */
internal suspend fun buildActivityFailureException(
    failure: Failure,
    codec: PayloadCodec,
    activityType: String,
    activityId: String,
): WorkflowActivityException {
    // Check for timeout first - should return WorkflowActivityTimeoutException
    if (failure.hasTimeoutFailureInfo()) {
        val timeoutInfo = failure.timeoutFailureInfo
        return WorkflowActivityTimeoutException(
            activityType = activityType,
            activityId = activityId,
            timeoutType = mapTimeoutType(timeoutInfo.timeoutType),
            message = failure.message ?: "Activity timed out",
            cause = if (failure.hasCause()) buildCause(failure.cause, codec) else null,
        )
    }

    // Build the cause chain. When the root failure has ApplicationFailureInfo,
    // create an ApplicationFailure exception as the cause (with any nested causes).
    // This ensures applicationFailure is always findable in the cause chain.
    val cause: Throwable? =
        if (failure.hasApplicationFailureInfo()) {
            val nestedCause = if (failure.hasCause()) buildCause(failure.cause, codec) else null
            buildApplicationFailureFromProto(failure, codec, cause = nestedCause)
        } else if (failure.hasCause()) {
            buildCause(failure.cause, codec)
        } else {
            null
        }

    return WorkflowActivityFailureException(
        activityType = activityType,
        activityId = activityId,
        failureType = determineFailureType(failure),
        retryState = extractRetryState(failure),
        message = failure.message ?: "Activity failed",
        cause = cause,
    )
}

/**
 * Builds a proto [Failure] from an exception, populating [ApplicationFailureInfo] appropriately.
 *
 * When the exception is an [ApplicationFailure], the failure type, non-retryable flag, details,
 * next retry delay, and category are all preserved in the proto. For other exceptions, the proto
 * is wrapped with a default [ApplicationFailureInfo] (using the exception class name as the type)
 * to ensure the server's retry logic handles it correctly.
 *
 * This is shared by activity and workflow failure completion builders.
 */
@InternalTemporalApi
internal suspend fun buildFailureProto(
    exception: Throwable,
    serializer: PayloadSerializer,
    codec: PayloadCodec,
    depth: Int = 0,
): Failure {
    val failureBuilder =
        Failure
            .newBuilder()
            .setMessage(exception.message ?: exception::class.simpleName ?: "Unknown error")
            .setStackTrace(exception.stackTraceToString())
            .setSource("Kotlin")

    if (exception is ApplicationFailure) {
        val appInfoBuilder =
            ApplicationFailureInfo
                .newBuilder()
                .setType(exception.type)
                .setNonRetryable(exception.isNonRetryable)

        // Serialize details if present (raw details from throw side or pre-decoded payloads)
        if (exception.rawDetails.isNotEmpty() || !exception.details.isEmpty) {
            try {
                val detailsPayloads = exception.serializeDetails(serializer)
                val encoded = codec.safeEncode(detailsPayloads)
                appInfoBuilder.setDetails(encoded.toProto())
            } catch (e: PayloadProcessingException) {
                // Codec/serialization failed while encoding exception details - proceed without
                // details rather than masking the original exception. The type, message, and
                // nonRetryable flag are more important than the details.
                logger.warn("Failed to process ApplicationFailure details, omitting details: {}", e.message)
            }
        }

        // Set next retry delay if specified
        exception.nextRetryDelay?.let { delay ->
            val javaDuration = delay.toJavaDuration()
            val protoDuration =
                com.google.protobuf.Duration
                    .newBuilder()
                    .setSeconds(javaDuration.seconds)
                    .setNanos(javaDuration.nano)
                    .build()
            appInfoBuilder.setNextRetryDelay(protoDuration)
        }

        // Set error category if not default
        if (exception.category != ApplicationErrorCategory.UNSPECIFIED) {
            val protoCategory =
                when (exception.category) {
                    ApplicationErrorCategory.UNSPECIFIED -> {
                        io.temporal.api.enums.v1.ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_UNSPECIFIED
                    }

                    ApplicationErrorCategory.BENIGN -> {
                        io.temporal.api.enums.v1.ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN
                    }
                }
            appInfoBuilder.setCategory(protoCategory)
        }

        failureBuilder.setApplicationFailureInfo(appInfoBuilder)
    } else {
        // Wrap non-ApplicationFailure exceptions with ApplicationFailureInfo.
        // This matches Python SDK behavior and ensures the server's retry logic
        // handles the failure correctly (bare Failures without ApplicationFailureInfo
        // may not have retry policies applied properly by the server).
        val appInfoBuilder =
            ApplicationFailureInfo
                .newBuilder()
                .setType(
                    exception::class.qualifiedName
                        ?: exception::class.simpleName
                        ?: "UnknownException",
                ).setNonRetryable(false)
        failureBuilder.setApplicationFailureInfo(appInfoBuilder)
    }

    // Recursively serialize the cause chain
    if (depth < 20) {
        exception.cause?.let { cause ->
            failureBuilder.setCause(buildFailureProto(cause, serializer, codec, depth + 1))
        }
    } else {
        if (exception.cause != null) {
            logger.warn("Cause chain depth limit (20) reached, truncating remaining causes")
        }
    }

    return failureBuilder.build()
}
