package com.surrealdev.temporal.application.plugin.interceptor

import com.surrealdev.temporal.client.WorkflowExecutionDescription
import com.surrealdev.temporal.client.WorkflowExecutionList
import com.surrealdev.temporal.client.WorkflowHandle
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

    /**
     * Creates a new registry that contains interceptors from both this registry (first)
     * and the [other] registry (appended after).
     *
     * Application-level interceptors run before task-queue-level interceptors.
     */
    fun mergeWith(other: InterceptorRegistry): InterceptorRegistry {
        val merged = InterceptorRegistry()

        // Workflow Inbound
        merged.executeWorkflow.addAll(this.executeWorkflow)
        merged.executeWorkflow.addAll(other.executeWorkflow)
        merged.handleSignal.addAll(this.handleSignal)
        merged.handleSignal.addAll(other.handleSignal)
        merged.handleQuery.addAll(this.handleQuery)
        merged.handleQuery.addAll(other.handleQuery)
        merged.validateUpdate.addAll(this.validateUpdate)
        merged.validateUpdate.addAll(other.validateUpdate)
        merged.executeUpdate.addAll(this.executeUpdate)
        merged.executeUpdate.addAll(other.executeUpdate)

        // Workflow Outbound
        merged.scheduleActivity.addAll(this.scheduleActivity)
        merged.scheduleActivity.addAll(other.scheduleActivity)
        merged.scheduleLocalActivity.addAll(this.scheduleLocalActivity)
        merged.scheduleLocalActivity.addAll(other.scheduleLocalActivity)
        merged.startChildWorkflow.addAll(this.startChildWorkflow)
        merged.startChildWorkflow.addAll(other.startChildWorkflow)
        merged.sleep.addAll(this.sleep)
        merged.sleep.addAll(other.sleep)
        merged.signalExternalWorkflow.addAll(this.signalExternalWorkflow)
        merged.signalExternalWorkflow.addAll(other.signalExternalWorkflow)
        merged.cancelExternalWorkflow.addAll(this.cancelExternalWorkflow)
        merged.cancelExternalWorkflow.addAll(other.cancelExternalWorkflow)
        merged.continueAsNew.addAll(this.continueAsNew)
        merged.continueAsNew.addAll(other.continueAsNew)

        // Activity Inbound
        merged.executeActivity.addAll(this.executeActivity)
        merged.executeActivity.addAll(other.executeActivity)

        // Activity Outbound
        merged.heartbeat.addAll(this.heartbeat)
        merged.heartbeat.addAll(other.heartbeat)

        // Client Outbound
        merged.startWorkflow.addAll(this.startWorkflow)
        merged.startWorkflow.addAll(other.startWorkflow)
        merged.signalWorkflow.addAll(this.signalWorkflow)
        merged.signalWorkflow.addAll(other.signalWorkflow)
        merged.queryWorkflow.addAll(this.queryWorkflow)
        merged.queryWorkflow.addAll(other.queryWorkflow)
        merged.startWorkflowUpdate.addAll(this.startWorkflowUpdate)
        merged.startWorkflowUpdate.addAll(other.startWorkflowUpdate)
        merged.cancelWorkflow.addAll(this.cancelWorkflow)
        merged.cancelWorkflow.addAll(other.cancelWorkflow)
        merged.terminateWorkflow.addAll(this.terminateWorkflow)
        merged.terminateWorkflow.addAll(other.terminateWorkflow)
        merged.describeWorkflow.addAll(this.describeWorkflow)
        merged.describeWorkflow.addAll(other.describeWorkflow)
        merged.listWorkflows.addAll(this.listWorkflows)
        merged.listWorkflows.addAll(other.listWorkflows)
        merged.countWorkflows.addAll(this.countWorkflows)
        merged.countWorkflows.addAll(other.countWorkflows)

        return merged
    }

    companion object {
        /** An empty registry with no interceptors. */
        val EMPTY = InterceptorRegistry()
    }
}
