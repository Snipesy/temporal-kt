package com.surrealdev.temporal.common.exceptions

/**
 * Exception thrown when within a workflow when that workflow is canceled.
 *
 * This is different from a generic CancellationException and signals that
 * the workflow should complete with a CancelWorkflowExecution command.
 */
class WorkflowCancelledException(
    message: String = "Workflow cancelled",
) : TemporalCancellationException(message)
