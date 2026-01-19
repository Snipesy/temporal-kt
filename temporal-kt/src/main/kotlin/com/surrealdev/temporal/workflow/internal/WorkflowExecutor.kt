package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.typeInfoOf
import com.surrealdev.temporal.workflow.WorkflowInfo
import coresdk.workflow_activation.WorkflowActivationOuterClass.InitializeWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivationJob
import coresdk.workflow_commands.WorkflowCommands
import coresdk.workflow_completion.WorkflowCompletion
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspend
import kotlin.time.toKotlinInstant

/**
 * Executes a single workflow instance.
 *
 * Each workflow run (identified by run_id) has exactly one executor.
 * The executor maintains the workflow state and processes activations
 * from the Temporal server, producing completion responses.
 *
 * Workflow execution is single-threaded/sequential to ensure determinism.
 */
internal class WorkflowExecutor(
    private val runId: String,
    private val methodInfo: WorkflowMethodInfo,
    private val serializer: PayloadSerializer,
    private val taskQueue: String,
    private val namespace: String,
) {
    private var state: WorkflowState = WorkflowState(runId)
    private var context: WorkflowContextImpl? = null
    private var mainCoroutine: Deferred<Any?>? = null
    private var workflowInfo: WorkflowInfo? = null

    /**
     * Processes a workflow activation and returns the completion.
     *
     * The activation processing follows a specific order to handle replay correctly:
     * 1. Update state metadata (time, replay flag)
     * 2. Process initialization job if present (starts workflow coroutine)
     * 3. Yield to let workflow run until it suspends (registers pending operations)
     * 4. Process resolution jobs (timers, activities) to resume the workflow
     * 5. Yield again to let workflow progress after resolutions
     * 6. Return commands or terminal completion
     *
     * @param activation The activation from the Temporal server
     * @param scope The coroutine scope for workflow execution
     * @return The completion to send back to the server
     */
    suspend fun activate(
        activation: WorkflowActivation,
        scope: CoroutineScope,
    ): WorkflowCompletion.WorkflowActivationCompletion {
        try {
            // Update state from activation metadata
            state.updateFromActivation(
                timestamp = if (activation.hasTimestamp()) activation.timestamp else null,
                isReplaying = activation.isReplaying,
                historyLength = activation.historyLength,
            )

            // Separate jobs into initialization and resolution jobs
            val initJob = activation.jobsList.find { it.hasInitializeWorkflow() }
            val resolutionJobs = activation.jobsList.filter { isResolutionJob(it) }
            val otherJobs = activation.jobsList.filter { !it.hasInitializeWorkflow() && !isResolutionJob(it) }

            // Step 1: Process initialization if present
            if (initJob != null) {
                processJob(initJob, activation, scope)
            }

            // Step 2: Yield to let workflow coroutine run until it suspends
            // This ensures pending operations (timers, activities) are registered
            yield()

            // Step 3: Process other jobs (signals, queries, cancellation, etc.)
            for (job in otherJobs) {
                processJob(job, activation, scope)
            }

            // Step 4: Process resolution jobs (fire timer, resolve activity)
            // These resume the workflow from its suspension points
            for (job in resolutionJobs) {
                processJob(job, activation, scope)
            }

            // Step 5: Yield again to let workflow progress after resolutions
            yield()

            // Check if workflow completed
            val mainResult = mainCoroutine
            if (mainResult != null && mainResult.isCompleted) {
                return buildTerminalCompletion(mainResult)
            }

            // Return accumulated commands
            return buildSuccessCompletion()
        } catch (e: Exception) {
            return buildFailureCompletion(e)
        }
    }

    /**
     * Checks if a job is a resolution job (resolves a pending operation).
     */
    private fun isResolutionJob(job: WorkflowActivationJob): Boolean = job.hasFireTimer() || job.hasResolveActivity()

    private suspend fun processJob(
        job: WorkflowActivationJob,
        activation: WorkflowActivation,
        scope: CoroutineScope,
    ) {
        when {
            job.hasInitializeWorkflow() -> handleInitialize(job.initializeWorkflow, activation, scope)
            job.hasFireTimer() -> state.resolveTimer(job.fireTimer.seq)
            job.hasResolveActivity() ->
                state.resolveActivity(
                    job.resolveActivity.seq,
                    job.resolveActivity.result,
                )
            job.hasUpdateRandomSeed() -> {
                state.randomSeed = job.updateRandomSeed.randomnessSeed
                context?.updateRandomSeed(job.updateRandomSeed.randomnessSeed)
            }
            job.hasSignalWorkflow() -> handleSignal(job.signalWorkflow)
            job.hasQueryWorkflow() -> handleQuery(job.queryWorkflow)
            job.hasCancelWorkflow() -> handleCancel()
            job.hasRemoveFromCache() -> handleEviction()
            // Other jobs can be added as needed
        }
    }

    private suspend fun handleInitialize(
        init: InitializeWorkflow,
        activation: WorkflowActivation,
        scope: CoroutineScope,
    ) {
        val startTime =
            if (init.hasStartTime()) {
                java.time.Instant
                    .ofEpochSecond(init.startTime.seconds, init.startTime.nanos.toLong())
                    .toKotlinInstant()
            } else {
                java.time.Instant
                    .now()
                    .toKotlinInstant()
            }

        // Build workflow info
        workflowInfo =
            WorkflowInfo(
                workflowId = init.workflowId,
                runId = runId,
                workflowType = init.workflowType,
                taskQueue = taskQueue,
                namespace = namespace,
                attempt = init.attempt,
                startTime =
                    kotlinx.datetime.Instant.fromEpochMilliseconds(
                        startTime.toEpochMilliseconds(),
                    ),
            )

        // Update random seed
        state.randomSeed = init.randomnessSeed

        // Create workflow context
        context =
            WorkflowContextImpl(
                state = state,
                info = workflowInfo!!,
                serializer = serializer,
            )

        // Start the main workflow coroutine
        mainCoroutine = startWorkflowCoroutine(init, scope)
    }

    private suspend fun startWorkflowCoroutine(
        init: InitializeWorkflow,
        scope: CoroutineScope,
    ): Deferred<Any?> {
        val ctx = context ?: error("Context not initialized")
        val method = methodInfo.runMethod

        // Deserialize arguments
        val args = deserializeArguments(init.argumentsList, methodInfo.parameterTypes)

        return scope.async {
            try {
                if (methodInfo.hasContextReceiver) {
                    // Method has WorkflowContext as extension receiver
                    if (methodInfo.isSuspend) {
                        method.callSuspend(methodInfo.implementation, ctx, *args)
                    } else {
                        method.call(methodInfo.implementation, ctx, *args)
                    }
                } else {
                    // Method does not use context receiver
                    if (methodInfo.isSuspend) {
                        method.callSuspend(methodInfo.implementation, *args)
                    } else {
                        method.call(methodInfo.implementation, *args)
                    }
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private fun deserializeArguments(
        payloads: List<Payload>,
        parameterTypes: List<KType>,
    ): Array<Any?> =
        payloads
            .zip(parameterTypes)
            .map { (payload, type) ->
                serializer.deserialize(typeInfoOf(type), payload)
            }.toTypedArray()

    private fun handleSignal(signal: coresdk.workflow_activation.WorkflowActivationOuterClass.SignalWorkflow) {
        // MVP: Signals not yet implemented
        // TODO: Queue signal for processing by signal handlers
    }

    private fun handleQuery(query: coresdk.workflow_activation.WorkflowActivationOuterClass.QueryWorkflow) {
        // MVP: Queries not yet implemented
        // TODO: Execute query handler and add QueryResult command
    }

    private fun handleCancel() {
        // MVP: Cancellation not yet fully implemented
        // TODO: Propagate cancellation to the workflow coroutine
        mainCoroutine?.cancel()
    }

    private fun handleEviction() {
        // Clear state on eviction
        state.clear()
        mainCoroutine?.cancel()
    }

    private suspend fun buildTerminalCompletion(
        result: Deferred<Any?>,
    ): WorkflowCompletion.WorkflowActivationCompletion =
        try {
            val value = result.await()

            // Serialize the result
            val resultPayload =
                if (value == Unit || methodInfo.returnType.classifier == Unit::class) {
                    Payload.getDefaultInstance()
                } else {
                    serializer.serialize(typeInfoOf(methodInfo.returnType), value)
                }

            // Build completion command
            val completeCommand =
                WorkflowCommands.WorkflowCommand
                    .newBuilder()
                    .setCompleteWorkflowExecution(
                        WorkflowCommands.CompleteWorkflowExecution
                            .newBuilder()
                            .setResult(resultPayload),
                    ).build()

            // Get any pending commands and add the completion
            val commands = state.drainCommands().toMutableList()
            commands.add(completeCommand)

            WorkflowCompletion.WorkflowActivationCompletion
                .newBuilder()
                .setRunId(runId)
                .setSuccessful(
                    WorkflowCompletion.Success
                        .newBuilder()
                        .addAllCommands(commands),
                ).build()
        } catch (e: Exception) {
            buildWorkflowFailureCompletion(e)
        }

    private fun buildSuccessCompletion(): WorkflowCompletion.WorkflowActivationCompletion {
        val commands = state.drainCommands()

        return WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(runId)
            .setSuccessful(
                WorkflowCompletion.Success
                    .newBuilder()
                    .addAllCommands(commands),
            ).build()
    }

    private fun buildFailureCompletion(exception: Exception): WorkflowCompletion.WorkflowActivationCompletion {
        val failure =
            Failure
                .newBuilder()
                .setMessage(exception.message ?: exception::class.simpleName ?: "Unknown error")
                .setStackTrace(exception.stackTraceToString())
                .setSource("Kotlin")
                .build()

        return WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(runId)
            .setFailed(
                WorkflowCompletion.Failure
                    .newBuilder()
                    .setFailure(failure),
            ).build()
    }

    private fun buildWorkflowFailureCompletion(exception: Exception): WorkflowCompletion.WorkflowActivationCompletion {
        // Build a workflow failure command
        val failure =
            Failure
                .newBuilder()
                .setMessage(exception.message ?: exception::class.simpleName ?: "Unknown error")
                .setStackTrace(exception.stackTraceToString())
                .setSource("Kotlin")
                .build()

        val failCommand =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setFailWorkflowExecution(
                    WorkflowCommands.FailWorkflowExecution
                        .newBuilder()
                        .setFailure(failure),
                ).build()

        val commands = state.drainCommands().toMutableList()
        commands.add(failCommand)

        return WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(runId)
            .setSuccessful(
                WorkflowCompletion.Success
                    .newBuilder()
                    .addAllCommands(commands),
            ).build()
    }

    /**
     * Checks if this executor is for eviction.
     */
    fun isEviction(activation: WorkflowActivation): Boolean = activation.jobsList.any { it.hasRemoveFromCache() }
}
