package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.hooks.BuildWorkflowCoroutineContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowCoroutineContextEvent
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompleted
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailed
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskStarted
import com.surrealdev.temporal.application.plugin.interceptor.CancelExternalInput
import com.surrealdev.temporal.application.plugin.interceptor.CancelExternalWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.ContinueAsNew
import com.surrealdev.temporal.application.plugin.interceptor.ContinueAsNewInput
import com.surrealdev.temporal.application.plugin.interceptor.ExecuteUpdate
import com.surrealdev.temporal.application.plugin.interceptor.ExecuteUpdateInput
import com.surrealdev.temporal.application.plugin.interceptor.ExecuteWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.ExecuteWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.HandleQuery
import com.surrealdev.temporal.application.plugin.interceptor.HandleQueryInput
import com.surrealdev.temporal.application.plugin.interceptor.HandleSignal
import com.surrealdev.temporal.application.plugin.interceptor.HandleSignalInput
import com.surrealdev.temporal.application.plugin.interceptor.Interceptor
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleActivity
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleActivityInput
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleLocalActivity
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleLocalActivityInput
import com.surrealdev.temporal.application.plugin.interceptor.SignalExternalInput
import com.surrealdev.temporal.application.plugin.interceptor.SignalExternalWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.Sleep
import com.surrealdev.temporal.application.plugin.interceptor.SleepInput
import com.surrealdev.temporal.application.plugin.interceptor.StartChildWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.StartChildWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.ValidateUpdate
import com.surrealdev.temporal.application.plugin.interceptor.ValidateUpdateInput
import com.surrealdev.temporal.workflow.ChildWorkflowHandle
import com.surrealdev.temporal.workflow.LocalActivityHandle
import com.surrealdev.temporal.workflow.RemoteActivityHandle

/**
 * DSL builder for workflow interceptors and observer hooks.
 *
 * Accessed via the `workflow {}` block in plugin configuration:
 * ```kotlin
 * val MyPlugin = createApplicationPlugin("MyPlugin") {
 *     workflow {
 *         // Inbound interceptors (server -> workflow code)
 *         onExecute { input, proceed -> proceed(input) }
 *         onHandleSignal { input, proceed -> proceed(input) }
 *         onHandleQuery { input, proceed -> proceed(input) }
 *         onValidateUpdate { input, proceed -> proceed(input) }
 *         onExecuteUpdate { input, proceed -> proceed(input) }
 *
 *         // Outbound interceptors (workflow code -> SDK)
 *         onScheduleActivity { input, proceed -> proceed(input) }
 *         onScheduleLocalActivity { input, proceed -> proceed(input) }
 *         onStartChildWorkflow { input, proceed -> proceed(input) }
 *         onSleep { input, proceed -> proceed(input) }
 *         onSignalExternalWorkflow { input, proceed -> proceed(input) }
 *         onCancelExternalWorkflow { input, proceed -> proceed(input) }
 *         onContinueAsNew { input, proceed -> proceed(input) }
 *
 *         // Observer hooks (existing task-level hooks)
 *         onTaskStarted { ctx -> ... }
 *         onTaskCompleted { ctx -> ... }
 *         onTaskFailed { ctx -> ... }
 *     }
 * }
 * ```
 */
@TemporalDsl
class WorkflowHookBuilder internal constructor(
    private val pluginBuilder: PluginBuilder<*>,
) {
    // ==================== Inbound Interceptors ====================

    /**
     * Intercepts workflow execution (main workflow method invocation).
     */
    fun onExecute(interceptor: Interceptor<ExecuteWorkflowInput, Any?>) {
        pluginBuilder.on(ExecuteWorkflow, interceptor)
    }

    /**
     * Intercepts signal delivery to the workflow.
     */
    fun onHandleSignal(interceptor: Interceptor<HandleSignalInput, Unit>) {
        pluginBuilder.on(HandleSignal, interceptor)
    }

    /**
     * Intercepts query handling.
     */
    fun onHandleQuery(interceptor: Interceptor<HandleQueryInput, Any?>) {
        pluginBuilder.on(HandleQuery, interceptor)
    }

    /**
     * Intercepts update validation (before acceptance).
     */
    fun onValidateUpdate(interceptor: Interceptor<ValidateUpdateInput, Unit>) {
        pluginBuilder.on(ValidateUpdate, interceptor)
    }

    /**
     * Intercepts update execution (after acceptance).
     */
    fun onExecuteUpdate(interceptor: Interceptor<ExecuteUpdateInput, Any?>) {
        pluginBuilder.on(ExecuteUpdate, interceptor)
    }

    // ==================== Outbound Interceptors ====================

    /**
     * Intercepts remote activity scheduling from workflow code.
     */
    fun onScheduleActivity(interceptor: Interceptor<ScheduleActivityInput, RemoteActivityHandle>) {
        pluginBuilder.on(ScheduleActivity, interceptor)
    }

    /**
     * Intercepts local activity scheduling from workflow code.
     */
    fun onScheduleLocalActivity(interceptor: Interceptor<ScheduleLocalActivityInput, LocalActivityHandle>) {
        pluginBuilder.on(ScheduleLocalActivity, interceptor)
    }

    /**
     * Intercepts child workflow starting from workflow code.
     */
    fun onStartChildWorkflow(interceptor: Interceptor<StartChildWorkflowInput, ChildWorkflowHandle>) {
        pluginBuilder.on(StartChildWorkflow, interceptor)
    }

    /**
     * Intercepts workflow sleep/timer from workflow code.
     */
    fun onSleep(interceptor: Interceptor<SleepInput, Unit>) {
        pluginBuilder.on(Sleep, interceptor)
    }

    /**
     * Intercepts signaling external workflows from workflow code.
     */
    fun onSignalExternalWorkflow(interceptor: Interceptor<SignalExternalInput, Unit>) {
        pluginBuilder.on(SignalExternalWorkflow, interceptor)
    }

    /**
     * Intercepts canceling external workflows from workflow code.
     */
    fun onCancelExternalWorkflow(interceptor: Interceptor<CancelExternalInput, Unit>) {
        pluginBuilder.on(CancelExternalWorkflow, interceptor)
    }

    /**
     * Intercepts continue-as-new from workflow code.
     */
    fun onContinueAsNew(interceptor: Interceptor<ContinueAsNewInput, Nothing>) {
        pluginBuilder.on(ContinueAsNew, interceptor)
    }

    // ==================== Coroutine Context Hook ====================

    /**
     * Hook that fires when the workflow coroutine context is being built.
     *
     * Use [WorkflowCoroutineContextEvent.contribute] to add [kotlin.coroutines.CoroutineContext]
     * elements to the workflow's base scope. These elements are inherited by ALL child coroutines
     * (`async {}`, `launch {}`, signal handlers, update handlers).
     *
     * Use [WorkflowCoroutineContextEvent.onCompletion] to register cleanup callbacks.
     */
    fun onBuildCoroutineContext(handler: (WorkflowCoroutineContextEvent) -> Unit) {
        pluginBuilder.on(BuildWorkflowCoroutineContext, handler)
    }

    // ==================== Observer Hooks ====================

    /**
     * Registers a handler for when a workflow task starts.
     *
     * Called before dispatching a workflow activation.
     */
    fun onTaskStarted(handler: suspend (WorkflowTaskContext) -> Unit) {
        pluginBuilder.on(WorkflowTaskStarted, handler)
    }

    /**
     * Registers a handler for when a workflow task completes successfully.
     *
     * Called after a workflow activation completes.
     */
    fun onTaskCompleted(handler: suspend (WorkflowTaskCompletedContext) -> Unit) {
        pluginBuilder.on(WorkflowTaskCompleted, handler)
    }

    /**
     * Registers a handler for when a workflow task fails.
     *
     * Called when a workflow activation dispatch fails.
     */
    fun onTaskFailed(handler: suspend (WorkflowTaskFailedContext) -> Unit) {
        pluginBuilder.on(WorkflowTaskFailed, handler)
    }
}
