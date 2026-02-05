package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.PayloadSerializer

/**
 * Base interface for workflow handles, shared between client-side [com.surrealdev.temporal.client.WorkflowHandle]
 * and workflow-side [ChildWorkflowHandle].
 *
 * This interface defines common operations that can be performed on any workflow,
 * whether accessed from client code or from within a parent workflow.
 */
interface WorkflowHandleBase {
    /**
     * The workflow ID.
     */
    val workflowId: String

    /**
     * Serializer associated with this workflow handle.
     * Used for converting values to/from Temporal Payloads.
     */
    val serializer: PayloadSerializer

    /**
     * Sends a signal to the workflow.
     *
     * This method uses raw payloads. Use the type-safe extension functions
     * [com.surrealdev.temporal.workflow.signal] for easier usage with automatic serialization.
     *
     * @param signalName The name of the signal to send.
     * @param args Arguments to pass with the signal.
     */
    @InternalTemporalApi
    suspend fun signalWithPayloads(
        signalName: String,
        args: TemporalPayloads,
    )
}
