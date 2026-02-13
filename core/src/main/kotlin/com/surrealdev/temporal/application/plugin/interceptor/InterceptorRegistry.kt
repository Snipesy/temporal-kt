package com.surrealdev.temporal.application.plugin.interceptor

import com.surrealdev.temporal.client.WorkflowExecutionDescription
import com.surrealdev.temporal.client.WorkflowExecutionList
import com.surrealdev.temporal.client.WorkflowHandle
import com.surrealdev.temporal.client.history.WorkflowHistory
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.workflow.ChildWorkflowHandle
import com.surrealdev.temporal.workflow.LocalActivityHandle
import com.surrealdev.temporal.workflow.RemoteActivityHandle

/**
 * Registry that holds interceptor lists for each interceptable operation.
 *
 * An [InterceptorRegistry] is created per-plugin-pipeline (application-level and task-queue-level).
 * At worker startup, the application-level and task-queue-level registries are merged into a single
 * effective registry that is passed to dispatchers and executors.
 *
 * Interceptors are executed in registration order (first registered = outermost).
 */
class InterceptorRegistry {
    // ==================== Workflow Inbound ====================

    val executeWorkflow = mutableListOf<Interceptor<ExecuteWorkflowInput, Any?>>()
    val handleSignal = mutableListOf<Interceptor<HandleSignalInput, Unit>>()
    val handleQuery = mutableListOf<Interceptor<HandleQueryInput, Any?>>()
    val validateUpdate = mutableListOf<Interceptor<ValidateUpdateInput, Unit>>()
    val executeUpdate = mutableListOf<Interceptor<ExecuteUpdateInput, Any?>>()

    // ==================== Workflow Outbound ====================

    val scheduleActivity = mutableListOf<Interceptor<ScheduleActivityInput, RemoteActivityHandle>>()
    val scheduleLocalActivity = mutableListOf<Interceptor<ScheduleLocalActivityInput, LocalActivityHandle>>()
    val startChildWorkflow = mutableListOf<Interceptor<StartChildWorkflowInput, ChildWorkflowHandle>>()
    val sleep = mutableListOf<Interceptor<SleepInput, Unit>>()
    val signalExternalWorkflow = mutableListOf<Interceptor<SignalExternalInput, Unit>>()
    val cancelExternalWorkflow = mutableListOf<Interceptor<CancelExternalInput, Unit>>()
    val continueAsNew = mutableListOf<Interceptor<ContinueAsNewInput, Nothing>>()

    // ==================== Activity Inbound ====================

    val executeActivity = mutableListOf<Interceptor<ExecuteActivityInput, Any?>>()

    // ==================== Activity Outbound ====================

    val heartbeat = mutableListOf<Interceptor<HeartbeatInput, Unit>>()

    // ==================== Client Outbound ====================

    val startWorkflow = mutableListOf<Interceptor<StartWorkflowInput, WorkflowHandle>>()
    val signalWorkflow = mutableListOf<Interceptor<SignalWorkflowInput, Unit>>()
    val queryWorkflow = mutableListOf<Interceptor<QueryWorkflowInput, TemporalPayloads>>()
    val startWorkflowUpdate = mutableListOf<Interceptor<StartWorkflowUpdateInput, TemporalPayloads>>()
    val cancelWorkflow = mutableListOf<Interceptor<CancelWorkflowInput, Unit>>()
    val terminateWorkflow = mutableListOf<Interceptor<TerminateWorkflowInput, Unit>>()
    val describeWorkflow = mutableListOf<Interceptor<DescribeWorkflowInput, WorkflowExecutionDescription>>()
    val listWorkflows = mutableListOf<Interceptor<ListWorkflowsInput, WorkflowExecutionList>>()
    val countWorkflows = mutableListOf<Interceptor<CountWorkflowsInput, Long>>()
    val fetchWorkflowResult = mutableListOf<Interceptor<FetchWorkflowResultInput, TemporalPayload?>>()
    val fetchWorkflowHistory = mutableListOf<Interceptor<FetchWorkflowHistoryInput, WorkflowHistory>>()

    /**
     * Returns all interceptor lists in a fixed order.
     * Used by [addAllFrom] and [mergeWith] to avoid enumerating every field.
     */
    private fun allLists(): List<MutableList<*>> =
        listOf(
            // Workflow Inbound
            executeWorkflow,
            handleSignal,
            handleQuery,
            validateUpdate,
            executeUpdate,
            // Workflow Outbound
            scheduleActivity,
            scheduleLocalActivity,
            startChildWorkflow,
            sleep,
            signalExternalWorkflow,
            cancelExternalWorkflow,
            continueAsNew,
            // Activity
            executeActivity,
            heartbeat,
            // Client Outbound
            startWorkflow,
            signalWorkflow,
            queryWorkflow,
            startWorkflowUpdate,
            cancelWorkflow,
            terminateWorkflow,
            describeWorkflow,
            listWorkflows,
            countWorkflows,
            fetchWorkflowResult,
            fetchWorkflowHistory,
        )

    /**
     * Appends all interceptors from [other] into this registry.
     */
    @Suppress("UNCHECKED_CAST")
    fun addAllFrom(other: InterceptorRegistry) {
        val targetLists = allLists()
        val sourceLists = other.allLists()
        for (i in targetLists.indices) {
            (targetLists[i] as MutableList<Any?>).addAll(sourceLists[i])
        }
    }

    /**
     * Creates a new registry that contains interceptors from both this registry (first)
     * and the [other] registry (appended after).
     *
     * Application-level interceptors run before task-queue-level interceptors.
     */
    fun mergeWith(other: InterceptorRegistry): InterceptorRegistry {
        val merged = InterceptorRegistry()
        merged.addAllFrom(this)
        merged.addAllFrom(other)
        return merged
    }

    companion object {
        /** An empty registry with no interceptors. */
        val EMPTY = InterceptorRegistry()
    }
}
