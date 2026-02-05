package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.common.ApplicationFailure
import com.surrealdev.temporal.workflow.internal.extractApplicationFailure
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
            // First check the cause chain (populated when buildCause creates ApplicationFailure)
            generateSequence(cause) { it.cause }
                .filterIsInstance<ApplicationFailure>()
                .firstOrNull()
                // Fallback: extract from proto failure if cause chain doesn't contain it
                ?: failure?.let { extractApplicationFailure(it) }

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

/**
 * Exception thrown when signaling an external workflow fails.
 *
 * This can occur when signaling a child workflow or any external workflow
 * from within workflow code.
 *
 * @property targetWorkflowId The workflow ID that was being signaled
 * @property signalName The name of the signal that failed to deliver
 * @property failure The Temporal failure details, if available
 */
class SignalExternalWorkflowFailedException(
    val targetWorkflowId: String,
    val signalName: String,
    val failure: Failure?,
    message: String = buildMessage(targetWorkflowId, signalName, failure),
) : RuntimeException(message) {
    companion object {
        private fun buildMessage(
            targetWorkflowId: String,
            signalName: String,
            failure: Failure?,
        ): String =
            buildString {
                append("Failed to signal workflow (workflowId=$targetWorkflowId) ")
                append("with signal '$signalName'")
                if (failure != null && failure.message.isNotEmpty()) {
                    append(": ${failure.message}")
                }
            }
    }
}

/**
 * Exception thrown when cancelling an external workflow fails.
 *
 * This can occur when the external workflow doesn't exist, has already
 * completed, or when there's a server-side error processing the cancel request.
 *
 * @property targetWorkflowId The workflow ID that was being cancelled
 * @property failure The Temporal failure details, if available
 */
class CancelExternalWorkflowFailedException(
    val targetWorkflowId: String,
    val failure: Failure?,
    message: String = buildMessage(targetWorkflowId, failure),
) : RuntimeException(message) {
    companion object {
        private fun buildMessage(
            targetWorkflowId: String,
            failure: Failure?,
        ): String =
            buildString {
                append("Failed to cancel external workflow (workflowId=$targetWorkflowId)")
                if (failure != null && failure.message.isNotEmpty()) {
                    append(": ${failure.message}")
                }
            }
    }
}
