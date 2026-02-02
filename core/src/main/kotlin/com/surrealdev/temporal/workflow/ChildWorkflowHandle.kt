package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.serialization.PayloadSerializer
import io.temporal.api.common.v1.Payloads

/**
 * Handle to a running or completed child workflow.
 *
 * Obtain a handle by calling [WorkflowContext.startChildWorkflowWithPayloads] or related extension functions.
 *
 * This interface extends [WorkflowHandleBase] to share common operations with client-side
 * workflow handles. Note that query and update operations are NOT supported on child workflows
 * from within workflow code (they require synchronous RPC calls which break determinism).
 * Use an activity to query or update child workflows if needed.
 *
 * @param R The result type of the child workflow
 */
interface ChildWorkflowHandle<R> : WorkflowHandleBase<R> {
    /**
     * The workflow ID of this child workflow.
     */
    override val workflowId: String

    /**
     * Serializer associated with this workflow handle.
     * Used for converting values to/from Temporal Payloads.
     */
    override val serializer: PayloadSerializer

    /**
     * The run ID of the first execution of this child workflow.
     * Available after the child workflow has started.
     */
    val firstExecutionRunId: String?

    /**
     * Waits for the child workflow to complete and returns its result.
     *
     * @return The result of the child workflow
     * @throws ChildWorkflowFailureException if the child workflow failed
     * @throws ChildWorkflowCancelledException if the child workflow was cancelled
     * @throws ChildWorkflowStartFailureException if the child workflow failed to start
     */
    suspend fun result(): R

    /**
     * Sends a signal to this child workflow.
     *
     * This method uses raw payloads. Use the type-safe extension functions
     * [signal] for easier usage with automatic serialization.
     *
     * @param signalName The name of the signal to send.
     * @param args Arguments to pass with the signal.
     * @throws SignalExternalWorkflowFailedException if the signal delivery failed
     */
    @InternalTemporalApi
    override suspend fun signalWithPayloads(
        signalName: String,
        args: Payloads,
    )

    /**
     * Requests cancellation of the child workflow.
     *
     * The behavior depends on the [ChildWorkflowCancellationType] set in the options:
     * - ABANDON: Returns immediately, child continues running
     * - TRY_CANCEL: Sends cancel request, returns immediately
     * - WAIT_CANCELLATION_REQUESTED: Waits for cancel request acknowledgment
     * - WAIT_CANCELLATION_COMPLETED: Waits for child to fully complete cancellation
     *
     * @param reason Optional reason for cancellation (for debugging)
     */
    fun cancel(reason: String = "Cancelled by parent workflow")
}
