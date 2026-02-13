package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.interceptor.CancelWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.CountWorkflowsInput
import com.surrealdev.temporal.application.plugin.interceptor.DescribeWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.FetchWorkflowHistoryInput
import com.surrealdev.temporal.application.plugin.interceptor.FetchWorkflowResultInput
import com.surrealdev.temporal.application.plugin.interceptor.Interceptor
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorRegistry
import com.surrealdev.temporal.application.plugin.interceptor.ListWorkflowsInput
import com.surrealdev.temporal.application.plugin.interceptor.QueryWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.SignalWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.StartWorkflowInput
import com.surrealdev.temporal.application.plugin.interceptor.StartWorkflowUpdateInput
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
    private val interceptorRegistry: InterceptorRegistry,
) {
    /**
     * Intercepts workflow start operations.
     */
    fun onStartWorkflow(interceptor: Interceptor<StartWorkflowInput, WorkflowHandle>) {
        interceptorRegistry.startWorkflow.add(interceptor)
    }

    /**
     * Intercepts workflow signal operations.
     */
    fun onSignalWorkflow(interceptor: Interceptor<SignalWorkflowInput, Unit>) {
        interceptorRegistry.signalWorkflow.add(interceptor)
    }

    /**
     * Intercepts workflow query operations.
     */
    fun onQueryWorkflow(interceptor: Interceptor<QueryWorkflowInput, TemporalPayloads>) {
        interceptorRegistry.queryWorkflow.add(interceptor)
    }

    /**
     * Intercepts workflow update operations.
     */
    fun onStartWorkflowUpdate(interceptor: Interceptor<StartWorkflowUpdateInput, TemporalPayloads>) {
        interceptorRegistry.startWorkflowUpdate.add(interceptor)
    }

    /**
     * Intercepts workflow cancel operations.
     */
    fun onCancelWorkflow(interceptor: Interceptor<CancelWorkflowInput, Unit>) {
        interceptorRegistry.cancelWorkflow.add(interceptor)
    }

    /**
     * Intercepts workflow terminate operations.
     */
    fun onTerminateWorkflow(interceptor: Interceptor<TerminateWorkflowInput, Unit>) {
        interceptorRegistry.terminateWorkflow.add(interceptor)
    }

    /**
     * Intercepts workflow describe operations.
     */
    fun onDescribeWorkflow(interceptor: Interceptor<DescribeWorkflowInput, WorkflowExecutionDescription>) {
        interceptorRegistry.describeWorkflow.add(interceptor)
    }

    /**
     * Intercepts workflow list operations.
     */
    fun onListWorkflows(interceptor: Interceptor<ListWorkflowsInput, WorkflowExecutionList>) {
        interceptorRegistry.listWorkflows.add(interceptor)
    }

    /**
     * Intercepts workflow count operations.
     */
    fun onCountWorkflows(interceptor: Interceptor<CountWorkflowsInput, Long>) {
        interceptorRegistry.countWorkflows.add(interceptor)
    }

    /**
     * Intercepts workflow result fetching (awaiting workflow completion).
     */
    fun onFetchWorkflowResult(interceptor: Interceptor<FetchWorkflowResultInput, TemporalPayload?>) {
        interceptorRegistry.fetchWorkflowResult.add(interceptor)
    }

    /**
     * Intercepts workflow history fetching.
     */
    fun onFetchWorkflowHistory(interceptor: Interceptor<FetchWorkflowHistoryInput, WorkflowHistory>) {
        interceptorRegistry.fetchWorkflowHistory.add(interceptor)
    }
}
