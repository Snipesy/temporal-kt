package com.surrealdev.temporal.common.exceptions

/**
 * Base exception for client workflow errors when waiting for workflow results from the client.
 */
sealed class ClientWorkflowException(
    message: String,
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause)

/**
 * Exception thrown when a workflow execution fails.
 *
 * This can occur when the workflow code throws an exception or
 * explicitly fails the workflow.
 *
 * @property workflowId The workflow ID that failed
 * @property runId The run ID of the failed execution
 * @property workflowType The type of workflow that failed
 */
class ClientWorkflowFailedException(
    val workflowId: String,
    val runId: String?,
    val workflowType: String?,
    failureMessage: String? = null,
    message: String = buildMessage(workflowId, runId, workflowType, failureMessage),
    cause: Throwable? = null,
) : ClientWorkflowException(message, cause) {
    /** The application failure details, if the workflow failed with an [ApplicationFailure]. */
    val applicationFailure: ApplicationFailure?
        get() =
            generateSequence(cause) { it.cause }
                .filterIsInstance<ApplicationFailure>()
                .firstOrNull()

    companion object {
        private fun buildMessage(
            workflowId: String,
            runId: String?,
            workflowType: String?,
            failureMessage: String?,
        ): String =
            buildString {
                append("Workflow ")
                if (workflowType != null) {
                    append("'$workflowType' ")
                }
                append("(workflowId=$workflowId")
                if (runId != null) {
                    append(", runId=$runId")
                }
                append(") failed")
                if (!failureMessage.isNullOrEmpty()) {
                    append(": $failureMessage")
                }
            }
    }
}

/**
 * Exception thrown when a workflow execution is canceled.
 *
 * @property workflowId The workflow ID that was canceled
 * @property runId The run ID of the canceled execution
 */
class ClientWorkflowCancelledException(
    val workflowId: String,
    val runId: String?,
    message: String = "Workflow (workflowId=$workflowId, runId=$runId) was canceled",
    cause: Throwable? = null,
) : ClientWorkflowException(message, cause)

/**
 * Exception thrown when a workflow execution is terminated.
 *
 * @property workflowId The workflow ID that was terminated
 * @property runId The run ID of the terminated execution
 * @property reason The termination reason, if provided
 */
class ClientWorkflowTerminatedException(
    val workflowId: String,
    val runId: String?,
    val reason: String?,
    message: String = buildMessage(workflowId, runId, reason),
    cause: Throwable? = null,
) : ClientWorkflowException(message, cause) {
    companion object {
        private fun buildMessage(
            workflowId: String,
            runId: String?,
            reason: String?,
        ): String =
            buildString {
                append("Workflow (workflowId=$workflowId")
                if (runId != null) {
                    append(", runId=$runId")
                }
                append(") was terminated")
                if (!reason.isNullOrEmpty()) {
                    append(": $reason")
                }
            }
    }
}

/**
 * Exception thrown when a workflow execution times out.
 *
 * @property workflowId The workflow ID that timed out
 * @property runId The run ID of the timed-out execution
 * @property timeoutType The type of timeout that occurred
 */
class ClientWorkflowTimedOutException(
    val workflowId: String,
    val runId: String?,
    val timeoutType: WorkflowTimeoutType,
    message: String = "Workflow (workflowId=$workflowId, runId=$runId) timed out: $timeoutType",
    cause: Throwable? = null,
) : ClientWorkflowException(message, cause)

/**
 * Types of workflow timeouts.
 */
enum class WorkflowTimeoutType {
    /** The workflow execution exceeded the allowed duration. */
    WORKFLOW_EXECUTION_TIMEOUT,
}

/**
 * Exception thrown when waiting for a workflow result times out.
 *
 * This is different from [ClientWorkflowTimedOutException] - this exception
 * is thrown when the client's wait operation times out, not when the
 * workflow itself times out.
 *
 * @property workflowId The workflow ID being waited on
 * @property runId The run ID being waited on
 */
class ClientWorkflowResultTimeoutException(
    val workflowId: String,
    val runId: String?,
    message: String = "Timed out waiting for workflow result (workflowId=$workflowId, runId=$runId)",
    cause: Throwable? = null,
) : ClientWorkflowException(message, cause)

/**
 * Exception thrown when a workflow is not found.
 *
 * @property workflowId The workflow ID that was not found
 * @property runId The run ID that was not found, if specified
 */
class ClientWorkflowNotFoundException(
    val workflowId: String,
    val runId: String?,
    message: String = buildMessage(workflowId, runId),
    cause: Throwable? = null,
) : ClientWorkflowException(message, cause) {
    companion object {
        private fun buildMessage(
            workflowId: String,
            runId: String?,
        ): String =
            buildString {
                append("Workflow not found (workflowId=$workflowId")
                if (runId != null) {
                    append(", runId=$runId")
                }
                append(")")
            }
    }
}

/**
 * Exception thrown when a workflow already exists with the same ID.
 *
 * @property workflowId The workflow ID that already exists
 * @property existingRunId The run ID of the existing workflow
 */
class ClientWorkflowAlreadyExistsException(
    val workflowId: String,
    val existingRunId: String?,
    message: String = "Workflow already exists (workflowId=$workflowId, existingRunId=$existingRunId)",
    cause: Throwable? = null,
) : ClientWorkflowException(message, cause)

/**
 * Exception thrown when a workflow update fails.
 *
 * @property workflowId The workflow ID
 * @property runId The run ID
 * @property updateName The name of the update that failed
 * @property updateId The ID of the update that failed
 */
class ClientWorkflowUpdateFailedException(
    val workflowId: String,
    val runId: String?,
    val updateName: String,
    val updateId: String,
    message: String,
    cause: Throwable? = null,
) : ClientWorkflowException(
        "Workflow update '$updateName' failed (workflowId=$workflowId, runId=$runId, updateId=$updateId): $message",
        cause,
    )

/**
 * Exception thrown when a workflow query is rejected.
 *
 * @property workflowId The workflow ID
 * @property runId The run ID
 * @property queryType The type of query that was rejected
 * @property status The rejection status
 */
class ClientWorkflowQueryRejectedException(
    val workflowId: String,
    val runId: String?,
    val queryType: String,
    val status: String,
    cause: Throwable? = null,
) : ClientWorkflowException(
        "Workflow query '$queryType' rejected (workflowId=$workflowId, runId=$runId): $status",
        cause,
    )
