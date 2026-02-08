package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.common.exceptions.ActivityRetryState
import com.surrealdev.temporal.common.exceptions.ActivityTimeoutType
import com.surrealdev.temporal.common.exceptions.WorkflowActivityException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityTimeoutException
import com.surrealdev.temporal.common.failure.buildApplicationFailureFromProto
import com.surrealdev.temporal.common.failure.buildCause
import com.surrealdev.temporal.serialization.PayloadCodec
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
