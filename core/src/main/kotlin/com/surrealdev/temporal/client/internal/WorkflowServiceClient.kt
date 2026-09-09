package com.surrealdev.temporal.client.internal

import com.surrealdev.temporal.core.TemporalCoreClient
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse
import io.temporal.api.workflowservice.v1.DeleteWorkerDeploymentRequest
import io.temporal.api.workflowservice.v1.DeleteWorkerDeploymentResponse
import io.temporal.api.workflowservice.v1.DeleteWorkerDeploymentVersionRequest
import io.temporal.api.workflowservice.v1.DeleteWorkerDeploymentVersionResponse
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentResponse
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentVersionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentVersionResponse
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse
import io.temporal.api.workflowservice.v1.ListWorkerDeploymentsRequest
import io.temporal.api.workflowservice.v1.ListWorkerDeploymentsResponse
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionResponse
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentRampingVersionRequest
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentRampingVersionResponse
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
    /**
     * Identity stamped on every request that carries the field. Core sets this on requests it
     * builds itself for the worker, but client RPCs are built here, so it has to be set explicitly
     * or the server records an empty identity.
     */
    val identity: String,
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

    // ----- Worker deployment management -----

    suspend fun describeWorkerDeployment(request: DescribeWorkerDeploymentRequest): DescribeWorkerDeploymentResponse =
        coreClient.workflowServiceCall(
            rpc = "DescribeWorkerDeployment",
            request = request,
        ) { input -> DescribeWorkerDeploymentResponse.parseFrom(input) }

    suspend fun listWorkerDeployments(request: ListWorkerDeploymentsRequest): ListWorkerDeploymentsResponse =
        coreClient.workflowServiceCall(
            rpc = "ListWorkerDeployments",
            request = request,
        ) { input -> ListWorkerDeploymentsResponse.parseFrom(input) }

    suspend fun describeWorkerDeploymentVersion(
        request: DescribeWorkerDeploymentVersionRequest,
    ): DescribeWorkerDeploymentVersionResponse =
        coreClient.workflowServiceCall(
            rpc = "DescribeWorkerDeploymentVersion",
            request = request,
        ) { input -> DescribeWorkerDeploymentVersionResponse.parseFrom(input) }

    suspend fun setWorkerDeploymentCurrentVersion(
        request: SetWorkerDeploymentCurrentVersionRequest,
    ): SetWorkerDeploymentCurrentVersionResponse =
        coreClient.workflowServiceCall(
            rpc = "SetWorkerDeploymentCurrentVersion",
            request = request,
        ) { input -> SetWorkerDeploymentCurrentVersionResponse.parseFrom(input) }

    suspend fun setWorkerDeploymentRampingVersion(
        request: SetWorkerDeploymentRampingVersionRequest,
    ): SetWorkerDeploymentRampingVersionResponse =
        coreClient.workflowServiceCall(
            rpc = "SetWorkerDeploymentRampingVersion",
            request = request,
        ) { input -> SetWorkerDeploymentRampingVersionResponse.parseFrom(input) }

    suspend fun deleteWorkerDeployment(request: DeleteWorkerDeploymentRequest): DeleteWorkerDeploymentResponse =
        coreClient.workflowServiceCall(
            rpc = "DeleteWorkerDeployment",
            request = request,
        ) { input -> DeleteWorkerDeploymentResponse.parseFrom(input) }

    suspend fun deleteWorkerDeploymentVersion(
        request: DeleteWorkerDeploymentVersionRequest,
    ): DeleteWorkerDeploymentVersionResponse =
        coreClient.workflowServiceCall(
            rpc = "DeleteWorkerDeploymentVersion",
            request = request,
        ) { input -> DeleteWorkerDeploymentVersionResponse.parseFrom(input) }
}
