package com.surrealdev.temporal.workflow

/**
 * Exception thrown when a workflow is canceled.
 *
 * This is different from a generic CancellationException and signals that
 * the workflow should complete with a CancelWorkflowExecution command.
 */
class WorkflowCancelledException(
    message: String = "Workflow cancelled",
) : kotlinx.coroutines.CancellationException(message)
