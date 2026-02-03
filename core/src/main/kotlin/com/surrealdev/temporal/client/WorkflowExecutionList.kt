package com.surrealdev.temporal.client

import com.google.protobuf.ByteString

/**
 * Result of listing workflow executions.
 *
 * Use [TemporalClient.listWorkflows] to obtain this.
 */
data class WorkflowExecutionList(
    val executions: List<WorkflowExecutionInfo>,
    val nextPageToken: ByteString?,
) {
    /**
     * Returns true if there are more results available.
     */
    fun hasNextPage(): Boolean = nextPageToken != null && !nextPageToken.isEmpty
}

/**
 * Information about a workflow execution from list results.
 */
data class WorkflowExecutionInfo(
    val workflowId: String,
    val runId: String,
    val workflowType: String,
    val status: WorkflowExecutionStatus,
    val startTime: Long,
    val closeTime: Long?,
    val historyLength: Long,
    val taskQueue: String,
)
