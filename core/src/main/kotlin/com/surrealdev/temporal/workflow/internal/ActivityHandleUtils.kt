package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.exceptions.ActivityRetryState
import com.surrealdev.temporal.common.exceptions.ActivityTimeoutType
import com.surrealdev.temporal.common.exceptions.ApplicationErrorCategory
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import io.temporal.api.failure.v1.Failure

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
 * Extracts [ApplicationFailure] from the failure or its cause chain.
 *
 * Temporal wraps application failures: ActivityFailureInfo contains ApplicationFailureInfo in cause.
 * This function recursively searches the cause chain up to [maxDepth] levels.
 *
 * @param failure The proto Failure to extract from
 * @param depth Current recursion depth (internal use)
 * @param maxDepth Maximum recursion depth to prevent infinite loops
 * @return The extracted ApplicationFailure, or null if not found
 */
internal fun extractApplicationFailure(
    failure: Failure,
    depth: Int = 0,
    maxDepth: Int = 10,
): ApplicationFailure? {
    if (depth >= maxDepth) return null

    // Check this level
    if (failure.hasApplicationFailureInfo()) {
        return buildApplicationFailureFromProto(failure)
    }

    // Check nested cause
    return if (failure.hasCause()) {
        extractApplicationFailure(failure.cause, depth + 1, maxDepth)
    } else {
        null
    }
}

/**
 * Builds an [ApplicationFailure] exception from a proto Failure that has ApplicationFailureInfo.
 */
@OptIn(InternalTemporalApi::class)
internal fun buildApplicationFailureFromProto(
    failure: Failure,
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
    return ApplicationFailure.fromProto(
        type = appInfo.type ?: "UnknownApplicationFailure",
        message = failure.message,
        isNonRetryable = appInfo.nonRetryable,
        encodedDetails = if (appInfo.hasDetails()) appInfo.details.toByteArray() else null,
        category = category,
        cause = cause,
    )
}

/**
 * Recursively builds cause exceptions from proto Failure.
 *
 * Converts the proto Failure cause chain into a Kotlin exception chain.
 * When a node in the chain has [ApplicationFailureInfo], an [ApplicationFailure]
 * exception is returned instead of a generic [RuntimeException].
 * Limits recursion depth to prevent stack overflow.
 *
 * @param failure The proto Failure to convert
 * @param depth Current recursion depth (internal use)
 * @param maxDepth Maximum recursion depth to prevent stack overflow
 * @return A Throwable representing the cause chain
 */
internal fun buildCause(
    failure: Failure,
    depth: Int = 0,
    maxDepth: Int = 20,
): Throwable {
    if (depth >= maxDepth) {
        return RuntimeException(failure.message ?: "Cause failure (max depth reached)")
    }

    val nestedCause =
        if (failure.hasCause()) {
            buildCause(failure.cause, depth + 1, maxDepth)
        } else {
            null
        }

    // If this failure node has ApplicationFailureInfo, create an ApplicationFailure exception
    if (failure.hasApplicationFailureInfo()) {
        return buildApplicationFailureFromProto(failure, cause = nestedCause)
    }

    return RuntimeException(failure.message ?: "Cause failure", nestedCause)
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
