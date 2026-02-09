package com.surrealdev.temporal.client.internal

import com.surrealdev.temporal.core.TemporalCoreClient
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionResponse

/**
 * Internal low-level client for making workflow service RPC calls.
 *
 * This class wraps the [TemporalCoreClient] and provides type-safe methods
 * for each workflow service operation. Uses zero-copy protobuf parsing
 * directly from native memory.
 */
internal class WorkflowServiceClient(
    private val coreClient: TemporalCoreClient,
    val namespace: String,
) {
    /**
     * Starts a new workflow execution.
     */
    suspend fun startWorkflowExecution(request: StartWorkflowExecutionRequest): StartWorkflowExecutionResponse =
        coreClient.workflowServiceCall(
            rpc = "StartWorkflowExecution",
            request = request,
        ) { input -> StartWorkflowExecutionResponse.parseFrom(input) }

    /**
     * Gets the execution history for a workflow.
     */
    suspend fun getWorkflowExecutionHistory(
        request: GetWorkflowExecutionHistoryRequest,
        timeoutMillis: Int = 0,
    ): GetWorkflowExecutionHistoryResponse =
        coreClient.workflowServiceCall(
            rpc = "GetWorkflowExecutionHistory",
            request = request,
            timeoutMillis = timeoutMillis,
        ) { input -> GetWorkflowExecutionHistoryResponse.parseFrom(input) }

    /**
     * Describes a workflow execution, returning its current status and configuration.
     */
    suspend fun describeWorkflowExecution(
        request: DescribeWorkflowExecutionRequest,
    ): DescribeWorkflowExecutionResponse =
        coreClient.workflowServiceCall(
            rpc = "DescribeWorkflowExecution",
            request = request,
        ) { input -> DescribeWorkflowExecutionResponse.parseFrom(input) }

    /**
     * Terminates a workflow execution.
     */
    suspend fun terminateWorkflowExecution(
        request: TerminateWorkflowExecutionRequest,
    ): TerminateWorkflowExecutionResponse =
        coreClient.workflowServiceCall(
            rpc = "TerminateWorkflowExecution",
            request = request,
        ) { input -> TerminateWorkflowExecutionResponse.parseFrom(input) }

    /**
     * Sends a signal to a workflow execution.
     */
    suspend fun signalWorkflowExecution(request: SignalWorkflowExecutionRequest): SignalWorkflowExecutionResponse =
        coreClient.workflowServiceCall(
            rpc = "SignalWorkflowExecution",
            request = request,
        ) { input -> SignalWorkflowExecutionResponse.parseFrom(input) }

    /**
     * Requests cancellation of a workflow execution.
     */
    suspend fun requestCancelWorkflowExecution(
        request: RequestCancelWorkflowExecutionRequest,
    ): RequestCancelWorkflowExecutionResponse =
        coreClient.workflowServiceCall(
            rpc = "RequestCancelWorkflowExecution",
            request = request,
        ) { input -> RequestCancelWorkflowExecutionResponse.parseFrom(input) }

    /**
     * Sends an update to a workflow execution and waits for the result.
     * This can block for an extended duration while the update handler executes.
     */
    suspend fun updateWorkflowExecution(
        request: UpdateWorkflowExecutionRequest,
        timeoutMillis: Int = 0,
    ): UpdateWorkflowExecutionResponse =
        coreClient.workflowServiceCall(
            rpc = "UpdateWorkflowExecution",
            request = request,
            timeoutMillis = timeoutMillis,
        ) { input -> UpdateWorkflowExecutionResponse.parseFrom(input) }

    /**
     * Queries a workflow execution for its current state.
     * This blocks until a worker picks up and executes the query.
     */
    suspend fun queryWorkflow(
        request: QueryWorkflowRequest,
        timeoutMillis: Int = 0,
    ): QueryWorkflowResponse =
        coreClient.workflowServiceCall(
            rpc = "QueryWorkflow",
            request = request,
            timeoutMillis = timeoutMillis,
        ) { input -> QueryWorkflowResponse.parseFrom(input) }

    /**
     * Lists workflow executions matching the given query.
     */
    suspend fun listWorkflowExecutions(request: ListWorkflowExecutionsRequest): ListWorkflowExecutionsResponse =
        coreClient.workflowServiceCall(
            rpc = "ListWorkflowExecutions",
            request = request,
        ) { input -> ListWorkflowExecutionsResponse.parseFrom(input) }

    /**
     * Counts workflow executions matching the given query.
     */
    suspend fun countWorkflowExecutions(request: CountWorkflowExecutionsRequest): CountWorkflowExecutionsResponse =
        coreClient.workflowServiceCall(
            rpc = "CountWorkflowExecutions",
            request = request,
        ) { input -> CountWorkflowExecutionsResponse.parseFrom(input) }
}
