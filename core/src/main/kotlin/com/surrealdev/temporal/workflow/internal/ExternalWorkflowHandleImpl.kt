package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.application.plugin.interceptor.CancelExternalInput
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorChain
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorRegistry
import com.surrealdev.temporal.application.plugin.interceptor.SignalExternalInput
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.CancelExternalWorkflowFailedException
import com.surrealdev.temporal.common.exceptions.SignalExternalWorkflowFailedException
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.safeEncode
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
    private val codec: PayloadCodec,
    private val interceptorRegistry: InterceptorRegistry = InterceptorRegistry.EMPTY,
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
        // Execute through the interceptor chain
        val interceptorInput =
            SignalExternalInput(
                workflowId = workflowId,
                runId = runId,
                signalName = signalName,
                args = args,
            )

        val chain = InterceptorChain(interceptorRegistry.signalExternalWorkflow)
        chain.execute(interceptorInput) { input ->
            signalInternal(input)
        }
    }

    private suspend fun signalInternal(input: SignalExternalInput) {
        val signalSeq = state.nextSeq()

        // Build signal command using workflow_execution target (not child_workflow_id)
        val signalBuilder =
            WorkflowCommands.SignalExternalWorkflowExecution
                .newBuilder()
                .setSeq(signalSeq)
                .setWorkflowExecution(buildWorkflowExecution())
                .setSignalName(input.signalName)
                .addAllArgs(codec.safeEncode(input.args).proto.payloadsList)

        // Set headers from interceptor input (may be modified by interceptors)
        if (input.headers.isNotEmpty()) {
            signalBuilder.putAllHeaders(
                input.headers.mapValues { (_, v) -> v.toProto() },
            )
        }

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setSignalExternalWorkflowExecution(signalBuilder)
                .build()

        state.addCommand(command)

        // Register and await resolution
        val deferred = state.registerExternalSignal(signalSeq)
        val failure = deferred.await()

        // If there was a failure, throw an exception
        if (failure != null) {
            throw SignalExternalWorkflowFailedException(
                targetWorkflowId = workflowId,
                signalName = input.signalName,
                failureMessage = failure.message,
            )
        }
    }

    override suspend fun cancel(reason: String) {
        // Execute through the interceptor chain
        val interceptorInput =
            CancelExternalInput(
                workflowId = workflowId,
                runId = runId,
                reason = reason,
            )

        val chain = InterceptorChain(interceptorRegistry.cancelExternalWorkflow)
        chain.execute(interceptorInput) { input ->
            cancelInternal(input.reason)
        }
    }

    private suspend fun cancelInternal(reason: String) {
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
                failureMessage = failure.message,
            )
        }
    }
}
