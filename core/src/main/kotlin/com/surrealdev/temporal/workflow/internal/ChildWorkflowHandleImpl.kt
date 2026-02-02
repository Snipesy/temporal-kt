package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.workflow.ChildWorkflowCancellationType
import com.surrealdev.temporal.workflow.ChildWorkflowCancelledException
import com.surrealdev.temporal.workflow.ChildWorkflowFailureException
import com.surrealdev.temporal.workflow.ChildWorkflowHandle
import com.surrealdev.temporal.workflow.ChildWorkflowStartFailureException
import com.surrealdev.temporal.workflow.SignalExternalWorkflowFailedException
import com.surrealdev.temporal.workflow.StartChildWorkflowFailureCause
import coresdk.child_workflow.ChildWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStart
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.Payloads
import kotlinx.coroutines.CompletableDeferred
import kotlin.reflect.KType

/**
 * Internal implementation of [ChildWorkflowHandle].
 *
 * This class manages the lifecycle of a child workflow execution:
 * 1. Start command is sent → waits for start resolution
 * 2. Start resolution received → either started (with runId) or failed
 * 3. Execution continues → waits for execution resolution
 * 4. Execution resolution received → completed, failed, or cancelled
 *
 * @param R The result type of the child workflow
 * @param workflowId The workflow ID of the child
 * @param seq The sequence number for this child workflow (used to correlate commands/resolutions)
 * @param workflowType The child workflow type name
 * @param state Reference to the workflow state for adding cancel commands
 * @param serializer For deserializing the result payload
 * @param returnType The expected return type for deserialization
 * @param cancellationType How to handle cancellation
 */
internal class ChildWorkflowHandleImpl<R>(
    override val workflowId: String,
    internal val seq: Int,
    private val workflowType: String,
    private val state: WorkflowState,
    override val serializer: PayloadSerializer,
    private val returnType: KType,
    private val cancellationType: ChildWorkflowCancellationType,
) : ChildWorkflowHandle<R> {
    /**
     * Deferred that completes when the child workflow starts (or fails to start).
     * Holds the run ID on success.
     */
    internal val startDeferred = CompletableDeferred<String>()

    /**
     * Deferred that completes when the child workflow execution finishes.
     * Holds the result payload on success.
     */
    internal val executionDeferred = CompletableDeferred<ChildWorkflow.ChildWorkflowResult>()

    private var _firstExecutionRunId: String? = null

    override val firstExecutionRunId: String?
        get() = _firstExecutionRunId

    @Suppress("UNCHECKED_CAST")
    override suspend fun result(): R {
        // Wait for start resolution first
        val runId = startDeferred.await()
        _firstExecutionRunId = runId

        // Wait for execution result
        val result = executionDeferred.await()

        return when {
            result.hasCompleted() -> {
                val payload = result.completed.result
                deserializeResult(payload)
            }

            result.hasFailed() -> {
                val failure = result.failed.failure
                throw ChildWorkflowFailureException(
                    childWorkflowId = workflowId,
                    childWorkflowType = workflowType,
                    failure = failure,
                )
            }

            result.hasCancelled() -> {
                val failure = result.cancelled.failure
                throw ChildWorkflowCancelledException(
                    childWorkflowId = workflowId,
                    childWorkflowType = workflowType,
                    failure = failure,
                )
            }

            else -> {
                error("Unknown child workflow result status")
            }
        }
    }

    override fun cancel(reason: String) {
        // Build cancel command
        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setCancelChildWorkflowExecution(
                    WorkflowCommands.CancelChildWorkflowExecution
                        .newBuilder()
                        .setChildWorkflowSeq(seq),
                ).build()

        state.addCommand(command)
    }

    @InternalTemporalApi
    override suspend fun signalWithPayloads(
        signalName: String,
        args: Payloads,
    ) {
        // Wait for child workflow to start before signaling
        // This ensures the child exists on the server before we try to signal it
        startDeferred.await()

        val signalSeq = state.nextSeq()

        // Build signal command using child_workflow_id target
        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setSignalExternalWorkflowExecution(
                    WorkflowCommands.SignalExternalWorkflowExecution
                        .newBuilder()
                        .setSeq(signalSeq)
                        .setChildWorkflowId(workflowId)
                        .setSignalName(signalName)
                        .addAllArgs(args.payloadsList),
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

    /**
     * Called by WorkflowState when ResolveChildWorkflowExecutionStart job is received.
     * Resolves the start deferred with success or failure.
     */
    internal fun resolveStart(resolution: ResolveChildWorkflowExecutionStart) {
        when {
            resolution.hasSucceeded() -> {
                val runId = resolution.succeeded.runId
                _firstExecutionRunId = runId
                startDeferred.complete(runId)
            }

            resolution.hasFailed() -> {
                val failed = resolution.failed
                val cause =
                    when (failed.cause) {
                        ChildWorkflow.StartChildWorkflowExecutionFailedCause
                            .START_CHILD_WORKFLOW_EXECUTION_FAILED_CAUSE_WORKFLOW_ALREADY_EXISTS,
                        -> {
                            StartChildWorkflowFailureCause.WORKFLOW_ALREADY_EXISTS
                        }

                        else -> {
                            StartChildWorkflowFailureCause.UNKNOWN
                        }
                    }
                val exception =
                    ChildWorkflowStartFailureException(
                        childWorkflowId = failed.workflowId,
                        childWorkflowType = failed.workflowType,
                        startFailureCause = cause,
                    )
                startDeferred.completeExceptionally(exception)
                // Also fail the execution deferred to ensure result() throws
                executionDeferred.completeExceptionally(exception)
            }

            resolution.hasCancelled() -> {
                val cancelled = resolution.cancelled
                val exception =
                    ChildWorkflowCancelledException(
                        childWorkflowId = workflowId,
                        childWorkflowType = workflowType,
                        failure = cancelled.failure,
                    )
                startDeferred.completeExceptionally(exception)
                executionDeferred.completeExceptionally(exception)
            }

            else -> {
                val exception = IllegalStateException("Unknown child workflow start resolution status")
                startDeferred.completeExceptionally(exception)
                executionDeferred.completeExceptionally(exception)
            }
        }
    }

    /**
     * Called by WorkflowState when ResolveChildWorkflowExecution job is received.
     * Resolves the execution deferred with the final result.
     */
    internal fun resolveExecution(result: ChildWorkflow.ChildWorkflowResult) {
        executionDeferred.complete(result)
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeResult(payload: Payload): R =
        if (payload == Payload.getDefaultInstance() || payload.data.isEmpty) {
            if (returnType.classifier == Unit::class) {
                Unit as R
            } else {
                throw IllegalStateException(
                    "Child workflow result payload is empty but return type is not Unit",
                )
            }
        } else {
            serializer.deserialize(returnType, payload) as R
        }
}
