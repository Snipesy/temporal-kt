package com.surrealdev.temporal.application.plugin.interceptor

import com.surrealdev.temporal.application.plugin.InterceptorHook
import com.surrealdev.temporal.client.WorkflowExecutionDescription
import com.surrealdev.temporal.client.WorkflowExecutionList
import com.surrealdev.temporal.client.WorkflowHandle
import com.surrealdev.temporal.client.WorkflowStartOptions
import com.surrealdev.temporal.client.history.WorkflowHistory
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads

// ==================== Interceptor Hooks ====================

object StartWorkflow : InterceptorHook<StartWorkflowInput, WorkflowHandle> {
    override val name = "StartWorkflow"
}

object SignalWorkflow : InterceptorHook<SignalWorkflowInput, Unit> {
    override val name = "SignalWorkflow"
}

object QueryWorkflow : InterceptorHook<QueryWorkflowInput, TemporalPayloads> {
    override val name = "QueryWorkflow"
}

object StartWorkflowUpdate : InterceptorHook<StartWorkflowUpdateInput, TemporalPayloads> {
    override val name = "StartWorkflowUpdate"
}

object CancelWorkflow : InterceptorHook<CancelWorkflowInput, Unit> {
    override val name = "CancelWorkflow"
}

object TerminateWorkflow : InterceptorHook<TerminateWorkflowInput, Unit> {
    override val name = "TerminateWorkflow"
}

object DescribeWorkflow : InterceptorHook<DescribeWorkflowInput, WorkflowExecutionDescription> {
    override val name = "DescribeWorkflow"
}

object ListWorkflows : InterceptorHook<ListWorkflowsInput, WorkflowExecutionList> {
    override val name = "ListWorkflows"
}

object CountWorkflows : InterceptorHook<CountWorkflowsInput, Long> {
    override val name = "CountWorkflows"
}

object FetchWorkflowResult : InterceptorHook<FetchWorkflowResultInput, TemporalPayload?> {
    override val name = "FetchWorkflowResult"
}

object FetchWorkflowHistory : InterceptorHook<FetchWorkflowHistoryInput, WorkflowHistory> {
    override val name = "FetchWorkflowHistory"
}

// ==================== Input Types ====================

/**
 * Input for the StartWorkflow client interceptor.
 */
data class StartWorkflowInput(
    val workflowType: String,
    val taskQueue: String,
    val workflowId: String,
    val args: TemporalPayloads,
    val options: WorkflowStartOptions,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the SignalWorkflow client interceptor.
 */
data class SignalWorkflowInput(
    val workflowId: String,
    val runId: String?,
    val signalName: String,
    val args: TemporalPayloads,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the QueryWorkflow client interceptor.
 */
data class QueryWorkflowInput(
    val workflowId: String,
    val runId: String?,
    val queryType: String,
    val args: TemporalPayloads,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the StartWorkflowUpdate client interceptor.
 */
data class StartWorkflowUpdateInput(
    val workflowId: String,
    val runId: String?,
    val updateName: String,
    val args: TemporalPayloads,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the CancelWorkflow client interceptor.
 */
data class CancelWorkflowInput(
    val workflowId: String,
    val runId: String?,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the TerminateWorkflow client interceptor.
 */
data class TerminateWorkflowInput(
    val workflowId: String,
    val runId: String?,
    val reason: String?,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the DescribeWorkflow client interceptor.
 */
data class DescribeWorkflowInput(
    val workflowId: String,
    val runId: String?,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the ListWorkflows client interceptor.
 */
data class ListWorkflowsInput(
    val query: String,
    val pageSize: Int,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the CountWorkflows client interceptor.
 */
data class CountWorkflowsInput(
    val query: String,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the FetchWorkflowResult client interceptor.
 */
data class FetchWorkflowResultInput(
    val workflowId: String,
    val runId: String?,
    val timeout: kotlin.time.Duration,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)

/**
 * Input for the FetchWorkflowHistory client interceptor.
 */
data class FetchWorkflowHistoryInput(
    val workflowId: String,
    val runId: String?,
    val headers: MutableMap<String, TemporalPayload> = mutableMapOf(),
)
