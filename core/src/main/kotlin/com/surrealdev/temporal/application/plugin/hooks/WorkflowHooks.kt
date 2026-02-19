package com.surrealdev.temporal.application.plugin.hooks

import com.surrealdev.temporal.application.plugin.Hook
import com.surrealdev.temporal.common.TemporalPayload
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_completion.WorkflowCompletion
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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

/**
 * Hook that fires when a workflow's coroutine context is being built.
 *
 * Plugins use this to contribute [CoroutineContext] elements that persist across
 * all child coroutines — `async {}`, `launch {}`, signal handlers, update handlers.
 *
 * This is the primary mechanism for plugins like OpenTelemetry to install
 * [kotlin.coroutines.CoroutineContext.Element]s (e.g., `KotlinContextElement`,
 * `MDCContext`) into the workflow's base scope.
 *
 * The hook fires once per workflow initialization, before the workflow coroutine
 * is launched.
 */
object BuildWorkflowCoroutineContext : Hook<(WorkflowCoroutineContextEvent) -> Unit> {
    override val name = "BuildWorkflowCoroutineContext"
}

/**
 * Event passed to [BuildWorkflowCoroutineContext] hook handlers.
 *
 * Handlers call [contribute] to add [CoroutineContext] elements to the workflow's
 * base scope, and [onCompletion] to register cleanup callbacks that fire when
 * the workflow coroutine finishes.
 */
class WorkflowCoroutineContextEvent(
    val workflowType: String,
    val workflowId: String,
    val runId: String,
    val namespace: String,
    val taskQueue: String,
    val headers: Map<String, TemporalPayload>?,
    val isReplaying: Boolean,
    /** The base MDC context map from the workflow (workflowType, taskQueue, etc.). */
    val mdcContextMap: Map<String, String>,
) {
    private var context: CoroutineContext = EmptyCoroutineContext
    private val handlers = mutableListOf<(Throwable?) -> Unit>()

    /** Add elements to the workflow's base coroutine context. */
    fun contribute(element: CoroutineContext) {
        context += element
    }

    /**
     * Register a callback that fires when the workflow coroutine completes.
     *
     * @param handler receives `null` on success, or the exception on failure
     */
    fun onCompletion(handler: (Throwable?) -> Unit) {
        handlers.add(handler)
    }

    internal val contributedContext: CoroutineContext get() = context
    internal val completionHandlers: List<(Throwable?) -> Unit> get() = handlers
}
