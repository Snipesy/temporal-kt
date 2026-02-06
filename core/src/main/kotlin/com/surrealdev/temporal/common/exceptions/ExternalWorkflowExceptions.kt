package com.surrealdev.temporal.common.exceptions

/**
 * Base exception for external workflow operation failures.
 *
 * This sealed class hierarchy allows for exhaustive when expressions
 * when handling external workflow operation exceptions (signal, cancel).
 */
sealed class ExternalWorkflowException(
    message: String,
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause)

/**
 * Exception thrown when signaling an external workflow fails.
 *
 * This can occur when signaling a child workflow or any external workflow
 * from within workflow code.
 *
 * @property targetWorkflowId The workflow ID that was being signaled
 * @property signalName The name of the signal that failed to deliver
 */
class SignalExternalWorkflowFailedException(
    val targetWorkflowId: String,
    val signalName: String,
    failureMessage: String? = null,
    message: String = buildMessage(targetWorkflowId, signalName, failureMessage),
) : ExternalWorkflowException(message) {
    companion object {
        private fun buildMessage(
            targetWorkflowId: String,
            signalName: String,
            failureMessage: String?,
        ): String =
            buildString {
                append("Failed to signal workflow (workflowId=$targetWorkflowId) ")
                append("with signal '$signalName'")
                if (!failureMessage.isNullOrEmpty()) {
                    append(": $failureMessage")
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
 */
class CancelExternalWorkflowFailedException(
    val targetWorkflowId: String,
    failureMessage: String? = null,
    message: String = buildMessage(targetWorkflowId, failureMessage),
) : ExternalWorkflowException(message) {
    companion object {
        private fun buildMessage(
            targetWorkflowId: String,
            failureMessage: String?,
        ): String =
            buildString {
                append("Failed to cancel external workflow (workflowId=$targetWorkflowId)")
                if (!failureMessage.isNullOrEmpty()) {
                    append(": $failureMessage")
                }
            }
    }
}
