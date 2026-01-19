package com.surrealdev.temporal.client.internal

import com.surrealdev.temporal.core.TemporalCoreClient
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse

/**
 * Internal low-level client for making workflow service RPC calls.
 *
 * This class wraps the [TemporalCoreClient] and provides type-safe methods
 * for each workflow service operation. It handles protobuf serialization
 * and deserialization.
 */
internal class WorkflowServiceClient(
    private val coreClient: TemporalCoreClient,
    val namespace: String,
) {
    /**
     * Starts a new workflow execution.
     */
    suspend fun startWorkflowExecution(request: StartWorkflowExecutionRequest): StartWorkflowExecutionResponse {
        val responseBytes =
            coreClient.workflowServiceCall(
                rpc = "StartWorkflowExecution",
                request = request.toByteArray(),
            )
        return StartWorkflowExecutionResponse.parseFrom(responseBytes)
    }

    /**
     * Gets the execution history for a workflow.
     */
    suspend fun getWorkflowExecutionHistory(
        request: GetWorkflowExecutionHistoryRequest,
    ): GetWorkflowExecutionHistoryResponse {
        val responseBytes =
            coreClient.workflowServiceCall(
                rpc = "GetWorkflowExecutionHistory",
                request = request.toByteArray(),
            )
        return GetWorkflowExecutionHistoryResponse.parseFrom(responseBytes)
    }

    /**
     * Describes a workflow execution, returning its current status and configuration.
     */
    suspend fun describeWorkflowExecution(
        request: DescribeWorkflowExecutionRequest,
    ): DescribeWorkflowExecutionResponse {
        val responseBytes =
            coreClient.workflowServiceCall(
                rpc = "DescribeWorkflowExecution",
                request = request.toByteArray(),
            )
        return DescribeWorkflowExecutionResponse.parseFrom(responseBytes)
    }

    /**
     * Terminates a workflow execution.
     */
    suspend fun terminateWorkflowExecution(
        request: TerminateWorkflowExecutionRequest,
    ): TerminateWorkflowExecutionResponse {
        val responseBytes =
            coreClient.workflowServiceCall(
                rpc = "TerminateWorkflowExecution",
                request = request.toByteArray(),
            )
        return TerminateWorkflowExecutionResponse.parseFrom(responseBytes)
    }

    /**
     * Sends a signal to a workflow execution.
     */
    suspend fun signalWorkflowExecution(request: SignalWorkflowExecutionRequest): SignalWorkflowExecutionResponse {
        val responseBytes =
            coreClient.workflowServiceCall(
                rpc = "SignalWorkflowExecution",
                request = request.toByteArray(),
            )
        return SignalWorkflowExecutionResponse.parseFrom(responseBytes)
    }

    /**
     * Requests cancellation of a workflow execution.
     */
    suspend fun requestCancelWorkflowExecution(
        request: RequestCancelWorkflowExecutionRequest,
    ): RequestCancelWorkflowExecutionResponse {
        val responseBytes =
            coreClient.workflowServiceCall(
                rpc = "RequestCancelWorkflowExecution",
                request = request.toByteArray(),
            )
        return RequestCancelWorkflowExecutionResponse.parseFrom(responseBytes)
    }
}
