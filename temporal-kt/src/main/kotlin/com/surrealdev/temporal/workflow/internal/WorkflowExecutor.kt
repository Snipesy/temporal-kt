package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.typeInfoOf
import com.surrealdev.temporal.workflow.WorkflowCancelledException
import com.surrealdev.temporal.workflow.WorkflowInfo
import coresdk.workflow_activation.WorkflowActivationOuterClass.InitializeWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivationJob
import coresdk.workflow_commands.WorkflowCommands
import coresdk.workflow_completion.WorkflowCompletion
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspend
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration
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
    private val logger = LoggerFactory.getLogger(WorkflowExecutor::class.java)

    /** MDC context for coroutine propagation with workflow identifiers. */
    private val mdcContext =
        MDCContext(
            mapOf(
                "workflowType" to methodInfo.workflowType,
                "taskQueue" to taskQueue,
                "namespace" to namespace,
                "runId" to runId,
            ),
        )

    // Create dispatcher with a timer scheduler that delegates to the workflow's timer system.
    // This allows kotlinx.coroutines.delay() to work correctly in workflows by creating
    // durable timers that survive replay.
    private val workflowDispatcher =
        WorkflowCoroutineDispatcher(
            timerScheduler =
                WorkflowTimerScheduler { delayMillis, continuation ->
                    scheduleTimerForContinuation(delayMillis, continuation)
                },
        )
    private var context: WorkflowContextImpl? = null
    private var mainCoroutine: Deferred<Any?>? = null
    private var workflowInfo: WorkflowInfo? = null

    // Job for the workflow execution - provides unified scope for structured concurrency
    private var workflowExecutionJob: kotlinx.coroutines.Job? = null

    /**
     * Processes a workflow activation and returns the completion.
     *
     * The activation processing follows a specific order to handle replay correctly:
     * 1. Update state metadata (time, replay flag)
     * 2. Process initialization job if present (starts workflow coroutine)
     * 3. Process all queued work via custom dispatcher to let workflow run until suspension
     * 4. Process resolution jobs (timers, activities) to resume the workflow
     * 5. Process queued work again to let workflow progress after resolutions
     * 6. Return commands or terminal completion
     *
     * @param activation The activation from the Temporal server
     * @param scope The coroutine scope for workflow execution
     * @return The completion to send back to the server
     */
    suspend fun activate(
        activation: WorkflowActivation,
        scope: CoroutineScope,
    ): WorkflowCompletion.WorkflowActivationCompletion =
        withContext(mdcContext) {
            try {
                logger.debug(
                    "Processing activation: jobs={}, replaying={}, historyLength={}",
                    activation.jobsList.map { jobTypeName(it) },
                    activation.isReplaying,
                    activation.historyLength,
                )

                // Update state from activation metadata
                state.updateFromActivation(
                    timestamp = if (activation.hasTimestamp()) activation.timestamp else null,
                    isReplaying = activation.isReplaying,
                    historyLength = activation.historyLength,
                )

                // Handle eviction early - it must be the only job and should not process other stages
                if (activation.jobsList.any { it.hasRemoveFromCache() }) {
                    handleEviction()
                    return@withContext buildSuccessCompletion()
                }

                // Separate jobs into ordered stages following Python SDK pattern for deterministic replay:
                // Stage 0: Initialization (if present)
                // Stage 1: Patches (for workflow versioning - not yet implemented, placeholder for future)
                // Stage 2: Signals + Updates (state mutations from external events)
                // Stage 3: Non-queries (resolutions, cancellation, random seed, etc.)
                // Stage 4: Queries (read-only operations)
                // Note: RemoveFromCache (eviction) is handled as an early exit before stage processing
                val initJob = activation.jobsList.find { it.hasInitializeWorkflow() }
                val patchJobs = activation.jobsList.filter { isPatchJob(it) }
                val signalAndUpdateJobs = activation.jobsList.filter { isSignalOrUpdateJob(it) }
                val nonQueryJobs = activation.jobsList.filter { isNonQueryJob(it) }
                val queryJobs = activation.jobsList.filter { it.hasQueryWorkflow() }

                // Stage 0: Process initialization if present
                if (initJob != null) {
                    processJob(initJob, activation, scope)
                    // Process queued work to start the workflow coroutine
                    workflowDispatcher.processAllWork()
                }

                // Stage 1: Process patches
                // Patches must be processed first for correct workflow versioning
                for (job in patchJobs) {
                    processJob(job, activation, scope)
                }
                if (patchJobs.isNotEmpty()) {
                    workflowDispatcher.processAllWork()
                    state.checkConditions()
                }

                // Stage 2: Process signals and updates
                // These can mutate workflow state and may satisfy pending conditions
                for (job in signalAndUpdateJobs) {
                    processJob(job, activation, scope)
                }
                if (signalAndUpdateJobs.isNotEmpty()) {
                    workflowDispatcher.processAllWork()
                    state.checkConditions()
                }

                // Stage 3: Process non-query jobs (resolutions, cancellation, etc.)
                // These resume suspended workflow operations
                for (job in nonQueryJobs) {
                    processJob(job, activation, scope)
                }
                if (nonQueryJobs.isNotEmpty()) {
                    workflowDispatcher.processAllWork()
                    state.checkConditions()
                }

                // Stage 4: Process queries (read-only mode)
                // Queries must not mutate workflow state to ensure deterministic replay
                if (queryJobs.isNotEmpty()) {
                    state.isReadOnly = true
                    try {
                        for (job in queryJobs) {
                            processJob(job, activation, scope)
                        }
                        workflowDispatcher.processAllWork()
                    } finally {
                        state.isReadOnly = false
                    }
                }

                // Check if workflow completed
                val mainResult = mainCoroutine
                if (mainResult != null && mainResult.isCompleted) {
                    logger.debug("Main workflow coroutine completed, building terminal completion")
                    return@withContext buildTerminalCompletion(mainResult)
                }

                // Return accumulated commands
                buildSuccessCompletion()
            } catch (e: Exception) {
                buildFailureCompletion(e)
            }
        }

    /**
     * Returns a human-readable name for the job type for logging.
     */
    private fun jobTypeName(job: WorkflowActivationJob): String =
        when {
            job.hasInitializeWorkflow() -> "InitializeWorkflow"
            job.hasFireTimer() -> "FireTimer(seq=${job.fireTimer.seq})"
            job.hasResolveActivity() -> "ResolveActivity(seq=${job.resolveActivity.seq})"
            job.hasUpdateRandomSeed() -> "UpdateRandomSeed"
            job.hasNotifyHasPatch() -> "NotifyHasPatch(${job.notifyHasPatch.patchId})"
            job.hasSignalWorkflow() -> "SignalWorkflow(${job.signalWorkflow.signalName})"
            job.hasDoUpdate() -> "DoUpdate(${job.doUpdate.name})"
            job.hasQueryWorkflow() -> "QueryWorkflow(${job.queryWorkflow.queryType})"
            job.hasCancelWorkflow() -> "CancelWorkflow"
            job.hasRemoveFromCache() -> "RemoveFromCache"
            job.hasResolveChildWorkflowExecutionStart() -> "ResolveChildWorkflowStart"
            job.hasResolveChildWorkflowExecution() -> "ResolveChildWorkflowExecution"
            job.hasResolveSignalExternalWorkflow() -> "ResolveSignalExternalWorkflow"
            job.hasResolveRequestCancelExternalWorkflow() -> "ResolveCancelExternalWorkflow"
            job.hasResolveNexusOperationStart() -> "ResolveNexusOperationStart"
            job.hasResolveNexusOperation() -> "ResolveNexusOperation"
            else -> "Unknown"
        }

    /**
     * Checks if a job is a patch job (workflow versioning).
     * Patches must be processed first for correct versioning behavior.
     */
    private fun isPatchJob(job: WorkflowActivationJob): Boolean = job.hasNotifyHasPatch()

    /**
     * Checks if a job is a signal or update job.
     * These can mutate workflow state and must be processed before queries.
     */
    private fun isSignalOrUpdateJob(job: WorkflowActivationJob): Boolean =
        job.hasSignalWorkflow() ||
            job.hasDoUpdate()

    /**
     * Checks if a job is a non-query job (resolutions, cancellation, random seed, etc.).
     * These jobs can mutate state but are not signals/updates.
     * Explicitly excludes InitializeWorkflow (processed in Stage 0) and RemoveFromCache (early exit).
     */
    private fun isNonQueryJob(job: WorkflowActivationJob): Boolean =
        job.hasFireTimer() ||
            job.hasResolveActivity() ||
            job.hasUpdateRandomSeed() ||
            job.hasCancelWorkflow() ||
            job.hasResolveChildWorkflowExecutionStart() ||
            job.hasResolveChildWorkflowExecution() ||
            job.hasResolveSignalExternalWorkflow() ||
            job.hasResolveRequestCancelExternalWorkflow() ||
            job.hasResolveNexusOperationStart() ||
            job.hasResolveNexusOperation()

    private suspend fun processJob(
        job: WorkflowActivationJob,
        activation: WorkflowActivation,
        scope: CoroutineScope,
    ) {
        when {
            job.hasInitializeWorkflow() -> {
                handleInitialize(job.initializeWorkflow, activation, scope)
            }

            job.hasFireTimer() -> {
                logger.debug("Timer fired: seq={}", job.fireTimer.seq)
                state.resolveTimer(job.fireTimer.seq)
            }

            job.hasResolveActivity() -> {
                val result = job.resolveActivity.result
                val status =
                    when {
                        result.hasCompleted() -> "completed"
                        result.hasFailed() -> "failed"
                        result.hasCancelled() -> "cancelled"
                        result.hasBackoff() -> "backoff"
                        else -> "unknown"
                    }
                logger.debug("Activity resolved: seq={}, status={}", job.resolveActivity.seq, status)
                state.resolveActivity(job.resolveActivity.seq, result)
            }

            job.hasUpdateRandomSeed() -> {
                state.randomSeed = job.updateRandomSeed.randomnessSeed
                context?.updateRandomSeed(job.updateRandomSeed.randomnessSeed)
            }

            job.hasNotifyHasPatch() -> {
                handlePatch(job.notifyHasPatch)
            }

            job.hasSignalWorkflow() -> {
                handleSignal(job.signalWorkflow)
            }

            job.hasDoUpdate() -> {
                handleUpdate(job.doUpdate)
            }

            job.hasQueryWorkflow() -> {
                handleQuery(job.queryWorkflow)
            }

            job.hasCancelWorkflow() -> {
                handleCancel()
            }

            job.hasRemoveFromCache() -> {
                handleEviction()
            }

            // Child workflow resolutions
            job.hasResolveChildWorkflowExecutionStart() -> {
                handleChildWorkflowStart(
                    job.resolveChildWorkflowExecutionStart,
                )
            }

            job.hasResolveChildWorkflowExecution() -> {
                handleChildWorkflowExecution(job.resolveChildWorkflowExecution)
            }

            // External workflow operations
            job.hasResolveSignalExternalWorkflow() -> {
                handleSignalExternalWorkflow(job.resolveSignalExternalWorkflow)
            }

            job.hasResolveRequestCancelExternalWorkflow() -> {
                handleCancelExternalWorkflow(
                    job.resolveRequestCancelExternalWorkflow,
                )
            }

            // Nexus operations
            job.hasResolveNexusOperationStart() -> {
                handleNexusOperationStart(job.resolveNexusOperationStart)
            }

            job.hasResolveNexusOperation() -> {
                handleNexusOperation(job.resolveNexusOperation)
            }
        }
    }

    private suspend fun handleInitialize(
        init: InitializeWorkflow,
        activation: WorkflowActivation,
        scope: CoroutineScope,
    ) {
        logger.debug(
            "Initializing workflow: workflowId={}, attempt={}, args={}",
            init.workflowId,
            init.attempt,
            init.argumentsCount,
        )

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

        // Create the workflow execution job as a child of the scope's Job
        val parentJob = scope.coroutineContext[kotlinx.coroutines.Job]
        workflowExecutionJob = kotlinx.coroutines.Job(parentJob)

        // Create workflow context with the execution job as parent
        // This ensures launch {} calls within the workflow are properly scoped
        context =
            WorkflowContextImpl(
                state = state,
                info = workflowInfo!!,
                serializer = serializer,
                workflowDispatcher = workflowDispatcher,
                parentJob = workflowExecutionJob!!,
                mdcContext = mdcContext,
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

        // Launch the workflow within the WorkflowContext's scope
        // This ensures all coroutines (including launch{}) share the same Job hierarchy
        // for proper structured concurrency and exception propagation
        return ctx.async {
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
            } catch (e: java.lang.reflect.InvocationTargetException) {
                // Unwrap reflection exceptions to get the actual workflow exception
                throw e.targetException ?: e
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
        logger.debug("Workflow cancellation requested")

        // Set the cancellation flag immediately
        state.cancelRequested = true

        // Defer the actual cancellation to the next dispatcher cycle
        // This allows the workflow to receive the cancellation signal
        // and potentially handle it gracefully
        if (mainCoroutine != null) {
            workflowDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
                // Cancel with our specific exception type
                mainCoroutine?.cancel(WorkflowCancelledException())
            }
        }
    }

    private fun handleEviction() {
        logger.debug("Workflow evicted from cache")

        // Mark as canceled before clearing
        state.cancelRequested = true
        // Clear state on eviction
        state.clear()
        mainCoroutine?.cancel(WorkflowCancelledException())
    }

    private fun handlePatch(patch: coresdk.workflow_activation.WorkflowActivationOuterClass.NotifyHasPatch) {
        // TODO: Implement patch tracking when workflow versioning is implemented
        // For now, just acknowledge receipt - patches will be tracked in WorkflowState
    }

    private fun handleUpdate(update: coresdk.workflow_activation.WorkflowActivationOuterClass.DoUpdate) {
        // TODO: Implement update handlers when updates are implemented
        // Updates are similar to signals but expect a response
    }

    private fun handleChildWorkflowStart(
        start: coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStart,
    ) {
        // TODO: Implement child workflow start resolution when child workflows are implemented
    }

    private fun handleChildWorkflowExecution(
        execution: coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecution,
    ) {
        // TODO: Implement child workflow execution resolution when child workflows are implemented
    }

    private fun handleSignalExternalWorkflow(
        signal: coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveSignalExternalWorkflow,
    ) {
        // TODO: Implement external workflow signal resolution when external signals are implemented
    }

    private fun handleCancelExternalWorkflow(
        cancel: coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveRequestCancelExternalWorkflow,
    ) {
        // TODO: Implement external workflow cancel resolution when external cancellation is implemented
    }

    private fun handleNexusOperationStart(
        start: coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveNexusOperationStart,
    ) {
        // TODO: Implement nexus operation start resolution when nexus operations are implemented
    }

    private fun handleNexusOperation(
        operation: coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveNexusOperation,
    ) {
        // TODO: Implement nexus operation resolution when nexus operations are implemented
    }

    private suspend fun buildTerminalCompletion(
        result: Deferred<Any?>,
    ): WorkflowCompletion.WorkflowActivationCompletion =
        try {
            val value = result.await()
            logger.debug(
                "Workflow completed successfully with result type: {}",
                value?.let { it::class.simpleName } ?: "null",
            )

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
            // Check if this is a cancellation with the cancel flag set
            if (state.cancelRequested && e is kotlinx.coroutines.CancellationException) {
                logger.debug("Workflow cancelled")
                buildWorkflowCancellationCompletion()
            } else {
                logger.debug("Workflow failed with exception: {}", e.message, e)
                buildWorkflowFailureCompletion(e)
            }
        }

    private fun buildSuccessCompletion(): WorkflowCompletion.WorkflowActivationCompletion {
        val commands = state.drainCommands()
        logger.debug("Returning {} commands", commands.size)

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

    private fun buildWorkflowCancellationCompletion(): WorkflowCompletion.WorkflowActivationCompletion {
        // Build a workflow cancellation command
        val cancelCommand =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setCancelWorkflowExecution(
                    WorkflowCommands.CancelWorkflowExecution.getDefaultInstance(),
                ).build()

        val commands = state.drainCommands().toMutableList()
        commands.add(cancelCommand)

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

    /**
     * Schedules a durable timer for a continuation.
     *
     * This is called by the [WorkflowCoroutineDispatcher] when [kotlinx.coroutines.delay]
     * is used in a workflow. It creates a proper durable timer command and registers
     * the continuation to be resumed when the timer fires.
     *
     * @param delayMillis The delay in milliseconds
     * @param continuation The continuation to resume when the timer fires
     */
    private fun scheduleTimerForContinuation(
        delayMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        // Handle zero or negative delay - resume immediately
        if (delayMillis <= 0) {
            logger.debug("Timer with zero/negative delay ({}ms), resuming immediately", delayMillis)
            // Queue the continuation to be resumed on the next dispatcher cycle
            workflowDispatcher.dispatch(continuation.context) {
                continuation.resumeWith(Result.success(Unit))
            }
            return
        }

        val seq = state.nextSeq()
        logger.debug("Scheduling timer: seq={}, delay={}ms", seq, delayMillis)
        val duration = delayMillis.milliseconds

        // Build the StartTimer command
        val javaDuration = duration.toJavaDuration()
        val protoDuration =
            com.google.protobuf.Duration
                .newBuilder()
                .setSeconds(javaDuration.seconds)
                .setNanos(javaDuration.nano)
                .build()

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setStartTimer(
                    WorkflowCommands.StartTimer
                        .newBuilder()
                        .setSeq(seq)
                        .setStartToFireTimeout(protoDuration),
                ).build()

        state.addCommand(command)

        // Register the continuation to be resumed when the timer fires
        state.registerTimerContinuation(seq, continuation)
    }
}
