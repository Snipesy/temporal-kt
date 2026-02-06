package com.surrealdev.temporal.workflow.internal

internal fun WorkflowExecutor.handleChildWorkflowExecution(
    execution: coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecution,
) {
    val status =
        when {
            execution.result.hasCompleted() -> "completed"
            execution.result.hasFailed() -> "failed"
            execution.result.hasCancelled() -> "cancelled"
            else -> "unknown"
        }
    logger.debug("Child workflow execution resolved: seq={}, status={}", execution.seq, status)
    state.resolveChildWorkflowExecution(execution.seq, execution.result)
}

internal suspend fun WorkflowExecutor.handleChildWorkflowStart(
    start: coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStart,
) {
    val status =
        when {
            start.hasSucceeded() -> "started(runId=${start.succeeded.runId})"
            start.hasFailed() -> "failed(cause=${start.failed.cause})"
            start.hasCancelled() -> "cancelled"
            else -> "unknown"
        }
    logger.debug("Child workflow start resolved: seq={}, status={}", start.seq, status)
    state.resolveChildWorkflowStart(start.seq, start)
}
