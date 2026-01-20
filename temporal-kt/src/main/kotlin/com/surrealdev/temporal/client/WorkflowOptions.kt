package com.surrealdev.temporal.client

import kotlin.time.Duration
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy as ProtoWorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy as ProtoWorkflowIdReusePolicy

/**
 * Options for starting a workflow execution.
 *
 * @property workflowExecutionTimeout Maximum time the workflow can run including retries and continue-as-new.
 * @property workflowRunTimeout Maximum time a single workflow run can take.
 * @property workflowTaskTimeout Maximum time a single workflow task can take.
 * @property workflowIdReusePolicy How to handle a workflow ID that was previously used.
 * @property workflowIdConflictPolicy How to handle a workflow ID that is currently running.
 * @property retryPolicy Retry policy for the workflow execution.
 * @property cronSchedule Cron schedule for recurring workflow executions.
 * @property memo Memo fields attached to the workflow.
 * @property searchAttributes Search attributes for the workflow.
 */
data class WorkflowStartOptions(
    val workflowExecutionTimeout: Duration? = null,
    val workflowRunTimeout: Duration? = null,
    val workflowTaskTimeout: Duration? = null,
    val workflowIdReusePolicy: WorkflowIdReusePolicy = WorkflowIdReusePolicy.ALLOW_DUPLICATE,
    val workflowIdConflictPolicy: WorkflowIdConflictPolicy = WorkflowIdConflictPolicy.FAIL,
    val retryPolicy: RetryPolicy? = null,
    val cronSchedule: String? = null,
    val memo: Map<String, Any?>? = null,
    val searchAttributes: Map<String, Any?>? = null,
)

/**
 * Policy for reusing a workflow ID that was previously used.
 */
enum class WorkflowIdReusePolicy {
    /** Allow starting a workflow with the same ID if the previous run completed. */
    ALLOW_DUPLICATE,

    /** Allow starting even if a workflow with the same ID is currently running. */
    ALLOW_DUPLICATE_FAILED_ONLY,

    /** Reject starting a workflow if any workflow with the same ID exists in history. */
    REJECT_DUPLICATE,

    /** Terminate the current run and start a new one. */
    TERMINATE_IF_RUNNING,

    ;

    internal fun toProto(): ProtoWorkflowIdReusePolicy =
        when (this) {
            ALLOW_DUPLICATE -> {
                ProtoWorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
            }

            ALLOW_DUPLICATE_FAILED_ONLY -> {
                ProtoWorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY
            }

            REJECT_DUPLICATE -> {
                ProtoWorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE
            }

            TERMINATE_IF_RUNNING -> {
                ProtoWorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING
            }
        }
}

/**
 * Policy for handling a workflow ID that is currently running.
 */
enum class WorkflowIdConflictPolicy {
    /** Fail if a workflow with the same ID is already running. */
    FAIL,

    /** Use the existing workflow if one is running. */
    USE_EXISTING,

    /** Terminate the existing workflow and start a new one. */
    TERMINATE_EXISTING,

    ;

    internal fun toProto(): ProtoWorkflowIdConflictPolicy =
        when (this) {
            FAIL -> ProtoWorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL
            USE_EXISTING -> ProtoWorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING
            TERMINATE_EXISTING -> ProtoWorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_TERMINATE_EXISTING
        }
}

/**
 * Retry policy for workflow or activity execution.
 *
 * @property initialInterval Initial interval between retry attempts.
 * @property backoffCoefficient Coefficient for exponential backoff.
 * @property maximumInterval Maximum interval between retry attempts.
 * @property maximumAttempts Maximum number of retry attempts (0 = unlimited).
 * @property nonRetryableErrorTypes List of error types that should not be retried.
 */
data class RetryPolicy(
    val initialInterval: Duration? = null,
    val backoffCoefficient: Double = 2.0,
    val maximumInterval: Duration? = null,
    val maximumAttempts: Int = 0,
    val nonRetryableErrorTypes: List<String> = emptyList(),
)

/**
 * Description of a workflow execution's current state.
 *
 * @property workflowId The workflow ID.
 * @property runId The current run ID.
 * @property workflowType The workflow type name.
 * @property status The current execution status.
 * @property startTime When the workflow started (epoch millis).
 * @property closeTime When the workflow closed, if applicable (epoch millis).
 * @property historyLength Number of events in the workflow history.
 */
data class WorkflowExecutionDescription(
    val workflowId: String,
    val runId: String,
    val workflowType: String,
    val status: WorkflowExecutionStatus,
    val startTime: Long,
    val closeTime: Long?,
    val historyLength: Long,
)

/**
 * Status of a workflow execution.
 */
enum class WorkflowExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED,
    TERMINATED,
    CONTINUED_AS_NEW,
    TIMED_OUT,
    UNKNOWN,
    ;

    companion object {
        internal fun fromProto(status: io.temporal.api.enums.v1.WorkflowExecutionStatus): WorkflowExecutionStatus =
            when (status) {
                io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING -> {
                    RUNNING
                }

                io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED -> {
                    COMPLETED
                }

                io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED -> {
                    FAILED
                }

                io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED -> {
                    CANCELED
                }

                io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED -> {
                    TERMINATED
                }

                io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW -> {
                    CONTINUED_AS_NEW
                }

                io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> {
                    TIMED_OUT
                }

                else -> {
                    UNKNOWN
                }
            }
    }
}
