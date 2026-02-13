package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompleted
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailed
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskStarted
import com.surrealdev.temporal.application.plugin.interceptor.CancelExternalInput
import com.surrealdev.temporal.application.plugin.interceptor.ContinueAsNewInput
import com.surrealdev.temporal.application.plugin.interceptor.ExecuteUpdateInput
import com.surrealdev.temporal.application.plugin.interceptor.ExecuteWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.HandleQueryInput
import com.surrealdev.temporal.application.plugin.interceptor.HandleSignalInput
import com.surrealdev.temporal.application.plugin.interceptor.Interceptor
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorRegistry
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleActivityInput
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleLocalActivityInput
import com.surrealdev.temporal.application.plugin.interceptor.SignalExternalInput
import com.surrealdev.temporal.application.plugin.interceptor.SleepInput
import com.surrealdev.temporal.application.plugin.interceptor.StartChildWorkflowInput
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
    private val interceptorRegistry: InterceptorRegistry,
) {
    // ==================== Inbound Interceptors ====================

    /**
     * Intercepts workflow execution (main workflow method invocation).
     */
    fun onExecute(interceptor: Interceptor<ExecuteWorkflowInput, Any?>) {
        interceptorRegistry.executeWorkflow.add(interceptor)
    }

    /**
     * Intercepts signal delivery to the workflow.
     */
    fun onHandleSignal(interceptor: Interceptor<HandleSignalInput, Unit>) {
        interceptorRegistry.handleSignal.add(interceptor)
    }

    /**
     * Intercepts query handling.
     */
    fun onHandleQuery(interceptor: Interceptor<HandleQueryInput, Any?>) {
        interceptorRegistry.handleQuery.add(interceptor)
    }

    /**
     * Intercepts update validation (before acceptance).
     */
    fun onValidateUpdate(interceptor: Interceptor<ValidateUpdateInput, Unit>) {
        interceptorRegistry.validateUpdate.add(interceptor)
    }

    /**
     * Intercepts update execution (after acceptance).
     */
    fun onExecuteUpdate(interceptor: Interceptor<ExecuteUpdateInput, Any?>) {
        interceptorRegistry.executeUpdate.add(interceptor)
    }

    // ==================== Outbound Interceptors ====================

    /**
     * Intercepts remote activity scheduling from workflow code.
     */
    fun onScheduleActivity(interceptor: Interceptor<ScheduleActivityInput, RemoteActivityHandle>) {
        interceptorRegistry.scheduleActivity.add(interceptor)
    }

    /**
     * Intercepts local activity scheduling from workflow code.
     */
    fun onScheduleLocalActivity(interceptor: Interceptor<ScheduleLocalActivityInput, LocalActivityHandle>) {
        interceptorRegistry.scheduleLocalActivity.add(interceptor)
    }

    /**
     * Intercepts child workflow starting from workflow code.
     */
    fun onStartChildWorkflow(interceptor: Interceptor<StartChildWorkflowInput, ChildWorkflowHandle>) {
        interceptorRegistry.startChildWorkflow.add(interceptor)
    }

    /**
     * Intercepts workflow sleep/timer from workflow code.
     */
    fun onSleep(interceptor: Interceptor<SleepInput, Unit>) {
        interceptorRegistry.sleep.add(interceptor)
    }

    /**
     * Intercepts signaling external workflows from workflow code.
     */
    fun onSignalExternalWorkflow(interceptor: Interceptor<SignalExternalInput, Unit>) {
        interceptorRegistry.signalExternalWorkflow.add(interceptor)
    }

    /**
     * Intercepts canceling external workflows from workflow code.
     */
    fun onCancelExternalWorkflow(interceptor: Interceptor<CancelExternalInput, Unit>) {
        interceptorRegistry.cancelExternalWorkflow.add(interceptor)
    }

    /**
     * Intercepts continue-as-new from workflow code.
     */
    fun onContinueAsNew(interceptor: Interceptor<ContinueAsNewInput, Nothing>) {
        interceptorRegistry.continueAsNew.add(interceptor)
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
