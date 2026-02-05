package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.CancelExternalWorkflowFailedException
import com.surrealdev.temporal.common.exceptions.SignalExternalWorkflowFailedException
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.workflow.ExternalWorkflowHandle
import coresdk.common.Common.NamespacedWorkflowExecution
import coresdk.workflow_commands.WorkflowCommands

/**
 * Internal implementation of [ExternalWorkflowHandle].
 *
 * This class handles signaling and cancelling external (non-child) workflows
 * from within workflow code.
 *
 * @param workflowId The workflow ID of the external workflow
 * @param runId The run ID of the external workflow, or null to target the latest run
 * @param namespace The namespace of the external workflow
 * @param state Reference to the workflow state for adding commands and tracking pending operations
 * @param serializer For serializing signal arguments
 */
internal class ExternalWorkflowHandleImpl(
    override val workflowId: String,
    override val runId: String?,
    override val namespace: String,
    private val state: WorkflowState,
    override val serializer: PayloadSerializer,
) : ExternalWorkflowHandle {
    /**
     * Builds the NamespacedWorkflowExecution proto used for targeting this external workflow.
     */
    private fun buildWorkflowExecution(): NamespacedWorkflowExecution {
        val builder =
            NamespacedWorkflowExecution
                .newBuilder()
                .setNamespace(namespace)
                .setWorkflowId(workflowId)

        runId?.let { builder.setRunId(it) }

        return builder.build()
    }

    @InternalTemporalApi
    override suspend fun signalWithPayloads(
        signalName: String,
        args: TemporalPayloads,
    ) {
        val signalSeq = state.nextSeq()

        // Build signal command using workflow_execution target (not child_workflow_id)
        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setSignalExternalWorkflowExecution(
                    WorkflowCommands.SignalExternalWorkflowExecution
                        .newBuilder()
                        .setSeq(signalSeq)
                        .setWorkflowExecution(buildWorkflowExecution())
                        .setSignalName(signalName)
                        .addAllArgs(args.toProto().payloadsList),
                ).build()

        state.addCommand(command)

        // Register and await resolution
        val deferred = state.registerExternalSignal(signalSeq)
        val failure = deferred.await()

        // If there was a failure, throw an exception
        if (failure != null) {
            throw SignalExternalWorkflowFailedException(
                targetWorkflowId = workflowId,
                signalName = signalName,
                failure = failure,
            )
        }
    }

    override suspend fun cancel(reason: String) {
        val cancelSeq = state.nextSeq()

        // Build cancel command
        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setRequestCancelExternalWorkflowExecution(
                    WorkflowCommands.RequestCancelExternalWorkflowExecution
                        .newBuilder()
                        .setSeq(cancelSeq)
                        .setWorkflowExecution(buildWorkflowExecution())
                        .setReason(reason),
                ).build()

        state.addCommand(command)

        // Register and await resolution
        val deferred = state.registerExternalCancel(cancelSeq)
        val failure = deferred.await()

        // If there was a failure, throw an exception
        if (failure != null) {
            throw CancelExternalWorkflowFailedException(
                targetWorkflowId = workflowId,
                failure = failure,
            )
        }
    }
}
