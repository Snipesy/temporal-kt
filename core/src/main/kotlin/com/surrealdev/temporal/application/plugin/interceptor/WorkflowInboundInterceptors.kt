package com.surrealdev.temporal.application.plugin.interceptor

import com.surrealdev.temporal.application.plugin.InterceptorHook
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads

// ==================== Interceptor Hooks ====================

object ExecuteWorkflow : InterceptorHook<ExecuteWorkflowInput, Any?> {
    override val name = "ExecuteWorkflow"
}

object HandleSignal : InterceptorHook<HandleSignalInput, Unit> {
    override val name = "HandleSignal"
}

object HandleQuery : InterceptorHook<HandleQueryInput, Any?> {
    override val name = "HandleQuery"
}

object ValidateUpdate : InterceptorHook<ValidateUpdateInput, Unit> {
    override val name = "ValidateUpdate"
}

object ExecuteUpdate : InterceptorHook<ExecuteUpdateInput, Any?> {
    override val name = "ExecuteUpdate"
}

// ==================== Input Types ====================

/**
 * Input for the ExecuteWorkflow interceptor.
 *
 * Passed through the interceptor chain when a workflow's main method is about to be invoked.
 */
data class ExecuteWorkflowInput(
    val workflowType: String,
    val runId: String,
    val workflowId: String,
    val namespace: String,
    val taskQueue: String,
    val headers: Map<String, TemporalPayload>?,
    val args: TemporalPayloads,
)

/**
 * Input for the HandleSignal interceptor.
 *
 * Passed through the interceptor chain when a signal is being delivered to a workflow.
 */
data class HandleSignalInput(
    val signalName: String,
    val args: TemporalPayloads,
    val runId: String,
    val workflowType: String?,
    val headers: Map<String, TemporalPayload>?,
)

/**
 * Input for the HandleQuery interceptor.
 *
 * Passed through the interceptor chain when a query is being handled.
 * Note: Built-in queries (__temporal_workflow_metadata, __stack_trace) bypass interceptors.
 */
data class HandleQueryInput(
    val queryId: String,
    val queryType: String,
    val args: TemporalPayloads,
    val runId: String,
    val headers: Map<String, TemporalPayload>?,
)

/**
 * Input for the ValidateUpdate interceptor.
 *
 * Passed through the interceptor chain during the update validation phase.
 */
data class ValidateUpdateInput(
    val updateName: String,
    val protocolInstanceId: String,
    val args: TemporalPayloads,
    val runId: String,
    val headers: Map<String, TemporalPayload>?,
)

/**
 * Input for the ExecuteUpdate interceptor.
 *
 * Passed through the interceptor chain during the update execution phase (after acceptance).
 */
data class ExecuteUpdateInput(
    val updateName: String,
    val protocolInstanceId: String,
    val args: TemporalPayloads,
    val runId: String,
    val headers: Map<String, TemporalPayload>?,
)
