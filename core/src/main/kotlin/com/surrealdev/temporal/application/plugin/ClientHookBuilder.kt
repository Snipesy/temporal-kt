package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.interceptor.CancelWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.CancelWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.CountWorkflows
import com.surrealdev.temporal.application.plugin.interceptor.CountWorkflowsInput
import com.surrealdev.temporal.application.plugin.interceptor.DescribeWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.DescribeWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.FetchWorkflowHistory
import com.surrealdev.temporal.application.plugin.interceptor.FetchWorkflowHistoryInput
import com.surrealdev.temporal.application.plugin.interceptor.FetchWorkflowResult
import com.surrealdev.temporal.application.plugin.interceptor.FetchWorkflowResultInput
import com.surrealdev.temporal.application.plugin.interceptor.Interceptor
import com.surrealdev.temporal.application.plugin.interceptor.ListWorkflows
import com.surrealdev.temporal.application.plugin.interceptor.ListWorkflowsInput
import com.surrealdev.temporal.application.plugin.interceptor.QueryWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.QueryWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.SignalWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.SignalWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.StartWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.StartWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.StartWorkflowUpdate
import com.surrealdev.temporal.application.plugin.interceptor.StartWorkflowUpdateInput
import com.surrealdev.temporal.application.plugin.interceptor.TerminateWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.TerminateWorkflowInput
import com.surrealdev.temporal.client.WorkflowExecutionDescription
import com.surrealdev.temporal.client.WorkflowExecutionList
import com.surrealdev.temporal.client.WorkflowHandle
import com.surrealdev.temporal.client.history.WorkflowHistory
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads

/**
 * DSL builder for client interceptors.
 *
 * Accessed via the `client {}` block in plugin configuration:
 * ```kotlin
 * val MyPlugin = createApplicationPlugin("MyPlugin") {
 *     client {
 *         onStartWorkflow { input, proceed ->
 *             println("Starting workflow: ${input.workflowType}")
 *             proceed(input)
 *         }
 *         onSignalWorkflow { input, proceed -> proceed(input) }
 *     }
 * }
 * ```
 */
@TemporalDsl
class ClientHookBuilder internal constructor(
    private val pluginBuilder: PluginBuilder<*>,
) {
    /**
     * Intercepts workflow start operations.
     */
    fun onStartWorkflow(interceptor: Interceptor<StartWorkflowInput, WorkflowHandle>) {
        pluginBuilder.on(StartWorkflow, interceptor)
    }

    /**
     * Intercepts workflow signal operations.
     */
    fun onSignalWorkflow(interceptor: Interceptor<SignalWorkflowInput, Unit>) {
        pluginBuilder.on(SignalWorkflow, interceptor)
    }

    /**
     * Intercepts workflow query operations.
     */
    fun onQueryWorkflow(interceptor: Interceptor<QueryWorkflowInput, TemporalPayloads>) {
        pluginBuilder.on(QueryWorkflow, interceptor)
    }

    /**
     * Intercepts workflow update operations.
     */
    fun onStartWorkflowUpdate(interceptor: Interceptor<StartWorkflowUpdateInput, TemporalPayloads>) {
        pluginBuilder.on(StartWorkflowUpdate, interceptor)
    }

    /**
     * Intercepts workflow cancel operations.
     */
    fun onCancelWorkflow(interceptor: Interceptor<CancelWorkflowInput, Unit>) {
        pluginBuilder.on(CancelWorkflow, interceptor)
    }

    /**
     * Intercepts workflow terminate operations.
     */
    fun onTerminateWorkflow(interceptor: Interceptor<TerminateWorkflowInput, Unit>) {
        pluginBuilder.on(TerminateWorkflow, interceptor)
    }

    /**
     * Intercepts workflow describe operations.
     */
    fun onDescribeWorkflow(interceptor: Interceptor<DescribeWorkflowInput, WorkflowExecutionDescription>) {
        pluginBuilder.on(DescribeWorkflow, interceptor)
    }

    /**
     * Intercepts workflow list operations.
     */
    fun onListWorkflows(interceptor: Interceptor<ListWorkflowsInput, WorkflowExecutionList>) {
        pluginBuilder.on(ListWorkflows, interceptor)
    }

    /**
     * Intercepts workflow count operations.
     */
    fun onCountWorkflows(interceptor: Interceptor<CountWorkflowsInput, Long>) {
        pluginBuilder.on(CountWorkflows, interceptor)
    }

    /**
     * Intercepts workflow result fetching (awaiting workflow completion).
     */
    fun onFetchWorkflowResult(interceptor: Interceptor<FetchWorkflowResultInput, TemporalPayload?>) {
        pluginBuilder.on(FetchWorkflowResult, interceptor)
    }

    /**
     * Intercepts workflow history fetching.
     */
    fun onFetchWorkflowHistory(interceptor: Interceptor<FetchWorkflowHistoryInput, WorkflowHistory>) {
        pluginBuilder.on(FetchWorkflowHistory, interceptor)
    }
}
