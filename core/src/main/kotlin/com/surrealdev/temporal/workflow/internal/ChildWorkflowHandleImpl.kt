package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.ChildWorkflowCancelledException
import com.surrealdev.temporal.common.exceptions.ChildWorkflowFailureException
import com.surrealdev.temporal.common.exceptions.ChildWorkflowStartFailureException
import com.surrealdev.temporal.common.exceptions.SignalExternalWorkflowFailedException
import com.surrealdev.temporal.common.exceptions.StartChildWorkflowFailureCause
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.workflow.ChildWorkflowCancellationType
import com.surrealdev.temporal.workflow.ChildWorkflowHandle
import coresdk.child_workflow.ChildWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStart
import coresdk.workflow_commands.WorkflowCommands
import kotlinx.coroutines.CompletableDeferred

/**
 * Internal implementation of [ChildWorkflowHandle].
 *
 * This class manages the lifecycle of a child workflow execution:
 * 1. Start command is sent → waits for start resolution
 * 2. Start resolution received → either started (with runId) or failed
 * 3. Execution continues → waits for execution resolution
 * 4. Execution resolution received → completed, failed, or cancelled
 *
 * @param workflowId The workflow ID of the child
 * @param seq The sequence number for this child workflow (used to correlate commands/resolutions)
 * @param workflowType The child workflow type name
 * @param state Reference to the workflow state for adding cancel commands
 * @param serializer For deserializing the result payload
 * @param cancellationType How to handle cancellation
 */
internal class ChildWorkflowHandleImpl(
    override val workflowId: String,
    internal val seq: Int,
    internal val workflowType: String,
    private val state: WorkflowState,
    override val serializer: PayloadSerializer,
    private val codec: PayloadCodec,
    private val cancellationType: ChildWorkflowCancellationType,
) : ChildWorkflowHandle {
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

    override suspend fun awaitStart(): String {
        val runId = startDeferred.await()
        _firstExecutionRunId = runId
        return runId
    }

    @OptIn(InternalTemporalApi::class)
    override suspend fun resultPayload(): TemporalPayload? {
        // Wait for start resolution first
        val runId = startDeferred.await()
        _firstExecutionRunId = runId

        // Wait for execution result
        val result = executionDeferred.await()

        return when {
            result.hasCompleted() -> {
                val payload = result.completed.result
                // Decode through codec, then return
                if (payload.data.isEmpty) {
                    null
                } else {
                    codec.decode(EncodedTemporalPayloads.fromProtoPayloadList(listOf(payload)))[0]
                }
            }

            result.hasFailed() -> {
                val failure = result.failed.failure
                val cause = buildCause(failure, codec)
                throw ChildWorkflowFailureException(
                    childWorkflowId = workflowId,
                    childWorkflowType = workflowType,
                    failureMessage = failure.message,
                    cause = cause,
                )
            }

            result.hasCancelled() -> {
                val failure = result.cancelled.failure
                val cause = buildCause(failure, codec)
                throw ChildWorkflowCancelledException(
                    childWorkflowId = workflowId,
                    childWorkflowType = workflowType,
                    failureMessage = failure.message,
                    cause = cause,
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
    @OptIn(InternalTemporalApi::class)
    override suspend fun signalWithPayloads(
        signalName: String,
        args: TemporalPayloads,
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
                        .addAllArgs(codec.encode(args).proto.payloadsList),
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
                failureMessage = failure.message,
            )
        }
    }

    /**
     * Called by WorkflowState when ResolveChildWorkflowExecutionStart job is received.
     * Resolves the start deferred with success or failure.
     */
    internal suspend fun resolveStart(resolution: ResolveChildWorkflowExecutionStart) {
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
                val cause = if (cancelled.hasFailure()) buildCause(cancelled.failure, codec) else null
                val exception =
                    ChildWorkflowCancelledException(
                        childWorkflowId = workflowId,
                        childWorkflowType = workflowType,
                        failureMessage = if (cancelled.hasFailure()) cancelled.failure.message else null,
                        cause = cause,
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
}
