package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.serialization.PayloadSerializer
import io.temporal.api.common.v1.Payloads

/**
 * Handle to an external workflow execution.
 *
 * This handle allows signaling and cancelling workflows that are not children
 * of the current workflow. For child workflows, use [ChildWorkflowHandle] instead.
 *
 * Unlike [ChildWorkflowHandle], this interface does not support:
 * - `result()` - external workflows are not tracked by the parent
 * - `awaitStart()` - we don't know when external workflows start
 *
 * Obtain a handle via [WorkflowContext] extension function `getExternalWorkflowHandle()`.
 */
interface ExternalWorkflowHandle : WorkflowHandleBase {
    /**
     * The workflow ID of the external workflow.
     */
    override val workflowId: String

    /**
     * The run ID of the external workflow, if targeting a specific run.
     * When null, targets the latest run of the workflow.
     */
    val runId: String?

    /**
     * The namespace of the external workflow.
     */
    val namespace: String

    /**
     * Serializer associated with this workflow handle.
     * Used for converting values to/from Temporal Payloads.
     */
    override val serializer: PayloadSerializer

    /**
     * Sends a signal to this external workflow.
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
     * Requests cancellation of the external workflow.
     *
     * Unlike child workflow cancellation, this operation is asynchronous and suspends
     * until the cancellation request has been acknowledged by the server.
     *
     * @param reason Optional reason for cancellation (for debugging)
     * @throws CancelExternalWorkflowFailedException if the cancellation request failed
     */
    suspend fun cancel(reason: String = "")
}
