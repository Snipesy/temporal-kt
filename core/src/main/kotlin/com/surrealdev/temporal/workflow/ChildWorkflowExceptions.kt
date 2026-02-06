package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import io.temporal.api.failure.v1.Failure

/**
 * Base exception for child workflow-related errors.
 *
 * This sealed class hierarchy allows for exhaustive when expressions
 * when handling child workflow exceptions.
 */
sealed class ChildWorkflowException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception thrown when a child workflow execution fails.
 *
 * This occurs when the child workflow throws an exception or
 * explicitly fails.
 *
 * @property childWorkflowId The workflow ID of the child that failed
 * @property childWorkflowType The type of the child workflow
 * @property failure The Temporal failure details, if available
 */
class ChildWorkflowFailureException(
    val childWorkflowId: String,
    val childWorkflowType: String?,
    val failure: Failure?,
    message: String = buildMessage(childWorkflowId, childWorkflowType, failure),
    cause: Throwable? = null,
) : ChildWorkflowException(message, cause) {
    /** The application failure details, if the child workflow failed with an [ApplicationFailure]. */
    val applicationFailure: ApplicationFailure?
        get() =
            generateSequence(cause) { it.cause }
                .filterIsInstance<ApplicationFailure>()
                .firstOrNull()

    companion object {
        private fun buildMessage(
            childWorkflowId: String,
            childWorkflowType: String?,
            failure: Failure?,
        ): String =
            buildString {
                append("Child workflow ")
                if (childWorkflowType != null) {
                    append("'$childWorkflowType' ")
                }
                append("(workflowId=$childWorkflowId) failed")
                if (failure != null && failure.message.isNotEmpty()) {
                    append(": ${failure.message}")
                }
            }
    }
}

/**
 * Exception thrown when a child workflow is cancelled.
 *
 * @property childWorkflowId The workflow ID of the cancelled child
 * @property childWorkflowType The type of the child workflow, if known
 * @property failure The Temporal cancellation details, if available
 */
class ChildWorkflowCancelledException(
    val childWorkflowId: String,
    val childWorkflowType: String? = null,
    val failure: Failure? = null,
    message: String = buildMessage(childWorkflowId, childWorkflowType, failure),
    cause: Throwable? = null,
) : ChildWorkflowException(message, cause) {
    companion object {
        private fun buildMessage(
            childWorkflowId: String,
            childWorkflowType: String?,
            failure: Failure?,
        ): String =
            buildString {
                append("Child workflow ")
                if (childWorkflowType != null) {
                    append("'$childWorkflowType' ")
                }
                append("(workflowId=$childWorkflowId) was cancelled")
                if (failure != null && failure.message.isNotEmpty()) {
                    append(": ${failure.message}")
                }
            }
    }
}

/**
 * Exception thrown when a child workflow fails to start.
 *
 * This can occur when:
 * - A workflow with the same ID already exists
 * - The workflow type is not registered
 * - The task queue is invalid
 *
 * @property childWorkflowId The workflow ID that failed to start
 * @property childWorkflowType The type of the child workflow
 * @property cause The reason for the start failure
 */
class ChildWorkflowStartFailureException(
    val childWorkflowId: String,
    val childWorkflowType: String?,
    val startFailureCause: StartChildWorkflowFailureCause,
    message: String = buildMessage(childWorkflowId, childWorkflowType, startFailureCause),
    cause: Throwable? = null,
) : ChildWorkflowException(message, cause) {
    companion object {
        private fun buildMessage(
            childWorkflowId: String,
            childWorkflowType: String?,
            startFailureCause: StartChildWorkflowFailureCause,
        ): String =
            buildString {
                append("Child workflow ")
                if (childWorkflowType != null) {
                    append("'$childWorkflowType' ")
                }
                append("(workflowId=$childWorkflowId) failed to start: $startFailureCause")
            }
    }
}

/**
 * Reasons a child workflow may fail to start.
 */
enum class StartChildWorkflowFailureCause {
    /** A workflow with the same ID already exists. */
    WORKFLOW_ALREADY_EXISTS,

    /** Unknown or unspecified failure cause. */
    UNKNOWN,
}
