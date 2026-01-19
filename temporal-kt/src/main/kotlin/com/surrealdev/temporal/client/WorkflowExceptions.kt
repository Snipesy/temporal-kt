package com.surrealdev.temporal.client

import io.temporal.api.failure.v1.Failure

/**
 * Base exception for workflow-related errors.
 */
open class WorkflowException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception thrown when a workflow execution fails.
 *
 * This can occur when the workflow code throws an exception or
 * explicitly fails the workflow.
 *
 * @property workflowId The workflow ID that failed
 * @property runId The run ID of the failed execution
 * @property workflowType The type of workflow that failed
 * @property failure The Temporal failure details, if available
 */
class WorkflowFailedException(
    val workflowId: String,
    val runId: String?,
    val workflowType: String?,
    val failure: Failure?,
    message: String = buildMessage(workflowId, runId, workflowType, failure),
    cause: Throwable? = null,
) : WorkflowException(message, cause) {
    companion object {
        private fun buildMessage(
            workflowId: String,
            runId: String?,
            workflowType: String?,
            failure: Failure?,
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
                if (failure != null && failure.message.isNotEmpty()) {
                    append(": ${failure.message}")
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
class WorkflowCanceledException(
    val workflowId: String,
    val runId: String?,
    message: String = "Workflow (workflowId=$workflowId, runId=$runId) was canceled",
    cause: Throwable? = null,
) : WorkflowException(message, cause)

/**
 * Exception thrown when a workflow execution is terminated.
 *
 * @property workflowId The workflow ID that was terminated
 * @property runId The run ID of the terminated execution
 * @property reason The termination reason, if provided
 */
class WorkflowTerminatedException(
    val workflowId: String,
    val runId: String?,
    val reason: String?,
    message: String = buildMessage(workflowId, runId, reason),
    cause: Throwable? = null,
) : WorkflowException(message, cause) {
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
class WorkflowTimedOutException(
    val workflowId: String,
    val runId: String?,
    val timeoutType: WorkflowTimeoutType,
    message: String = "Workflow (workflowId=$workflowId, runId=$runId) timed out: $timeoutType",
    cause: Throwable? = null,
) : WorkflowException(message, cause)

/**
 * Types of workflow timeouts.
 */
enum class WorkflowTimeoutType {
    /** The workflow execution exceeded the allowed duration. */
    WORKFLOW_EXECUTION_TIMEOUT,

    /** A single workflow run exceeded the allowed duration. */
    WORKFLOW_RUN_TIMEOUT,

    /** A workflow task exceeded the allowed duration. */
    WORKFLOW_TASK_TIMEOUT,
}

/**
 * Exception thrown when waiting for a workflow result times out.
 *
 * This is different from [WorkflowTimedOutException] - this exception
 * is thrown when the client's wait operation times out, not when the
 * workflow itself times out.
 *
 * @property workflowId The workflow ID being waited on
 * @property runId The run ID being waited on
 */
class WorkflowResultTimeoutException(
    val workflowId: String,
    val runId: String?,
    message: String = "Timed out waiting for workflow result (workflowId=$workflowId, runId=$runId)",
    cause: Throwable? = null,
) : WorkflowException(message, cause)

/**
 * Exception thrown when a workflow is not found.
 *
 * @property workflowId The workflow ID that was not found
 * @property runId The run ID that was not found, if specified
 */
class WorkflowNotFoundException(
    val workflowId: String,
    val runId: String?,
    message: String = buildMessage(workflowId, runId),
    cause: Throwable? = null,
) : WorkflowException(message, cause) {
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
class WorkflowAlreadyExistsException(
    val workflowId: String,
    val existingRunId: String?,
    message: String = "Workflow already exists (workflowId=$workflowId, existingRunId=$existingRunId)",
    cause: Throwable? = null,
) : WorkflowException(message, cause)
