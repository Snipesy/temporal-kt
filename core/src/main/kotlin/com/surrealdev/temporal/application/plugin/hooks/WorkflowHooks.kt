package com.surrealdev.temporal.application.plugin.hooks

import com.surrealdev.temporal.application.plugin.Hook
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_completion.WorkflowCompletion
import kotlin.time.Duration

/**
 * Hook called before dispatching a workflow activation.
 *
 * This hook is fired in [com.surrealdev.temporal.application.worker.ManagedWorker.pollWorkflowActivations]
 * before the activation is dispatched to the workflow dispatcher.
 *
 * Use this hook to:
 * - Track workflow execution
 * - Initialize workflow-scoped resources (e.g., dependency injection scopes)
 * - Record metrics or logs
 * - Implement workflow middleware
 */
object WorkflowTaskStarted : Hook<suspend (WorkflowTaskContext) -> Unit> {
    override val name = "WorkflowTaskStarted"
}

/**
 * Context provided to [WorkflowTaskStarted] hook handlers.
 *
 * @property activation The workflow activation received from Core SDK
 * @property runId The workflow run ID
 * @property workflowType The workflow type name (null if not an initialize job)
 * @property taskQueue The task queue name
 * @property namespace The namespace
 */
data class WorkflowTaskContext(
    val activation: WorkflowActivation,
    val runId: String,
    val workflowType: String?,
    val taskQueue: String,
    val namespace: String,
)

/**
 * Hook called after a workflow activation completes successfully.
 *
 * This hook is fired in [com.surrealdev.temporal.application.worker.ManagedWorker.pollWorkflowActivations]
 * after the workflow dispatcher returns a completion.
 *
 * Use this hook to:
 * - Clean up workflow-scoped resources
 * - Record metrics or performance data
 * - Implement workflow middleware
 */
object WorkflowTaskCompleted : Hook<suspend (WorkflowTaskCompletedContext) -> Unit> {
    override val name = "WorkflowTaskCompleted"
}

/**
 * Context provided to [WorkflowTaskCompleted] hook handlers.
 *
 * @property activation The workflow activation that was processed
 * @property completion The workflow activation completion
 * @property runId The workflow run ID
 * @property duration The time taken to process the activation
 */
data class WorkflowTaskCompletedContext(
    val activation: WorkflowActivation,
    val completion: WorkflowCompletion.WorkflowActivationCompletion,
    val runId: String,
    val duration: Duration,
)

/**
 * Hook called when a workflow activation fails.
 *
 * This hook is fired in [com.surrealdev.temporal.application.worker.ManagedWorker.pollWorkflowActivations]
 * when an exception is thrown during workflow dispatch.
 *
 * Use this hook to:
 * - Log errors
 * - Clean up workflow-scoped resources
 * - Record error metrics
 */
object WorkflowTaskFailed : Hook<suspend (WorkflowTaskFailedContext) -> Unit> {
    override val name = "WorkflowTaskFailed"
}

/**
 * Context provided to [WorkflowTaskFailed] hook handlers.
 *
 * @property activation The workflow activation that failed
 * @property error The exception that was thrown
 * @property runId The workflow run ID
 */
data class WorkflowTaskFailedContext(
    val activation: WorkflowActivation,
    val error: Throwable,
    val runId: String,
)
