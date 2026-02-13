package com.surrealdev.temporal.application.plugin.interceptor

import com.surrealdev.temporal.client.WorkflowStartOptions
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads

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
)

/**
 * Input for the TerminateWorkflow client interceptor.
 */
data class TerminateWorkflowInput(
    val workflowId: String,
    val runId: String?,
    val reason: String?,
)

/**
 * Input for the DescribeWorkflow client interceptor.
 */
data class DescribeWorkflowInput(
    val workflowId: String,
    val runId: String?,
)

/**
 * Input for the ListWorkflows client interceptor.
 */
data class ListWorkflowsInput(
    val query: String,
    val pageSize: Int,
)

/**
 * Input for the CountWorkflows client interceptor.
 */
data class CountWorkflowsInput(
    val query: String,
)
