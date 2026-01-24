package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.workflow.WorkflowCancelledException
import com.surrealdev.temporal.workflow.WorkflowInfo
import coresdk.workflow_activation.WorkflowActivationOuterClass.InitializeWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivationJob
import coresdk.workflow_commands.WorkflowCommands
import coresdk.workflow_completion.WorkflowCompletion
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspend
import kotlin.time.Instant
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
    internal val runId: String,
    internal val methodInfo: WorkflowMethodInfo,
    internal val serializer: PayloadSerializer,
    private val taskQueue: String,
    private val namespace: String,
    /**
     * The task queue scope for hierarchical attribute lookup.
     * Its parentScope should be the application.
     */
    private val taskQueueScope: AttributeScope,
    /**
     * Parent job for structured concurrency.
     * Workflow execution job will be a child of this job (from rootExecutorJob).
     */
    private val parentJob: Job,
) {
    internal var state: WorkflowState = WorkflowState(runId)
    internal val logger = LoggerFactory.getLogger(WorkflowExecutor::class.java)

    /**
     * Accumulated query results during read-only mode.
     * We can't use state.addCommand() in read-only mode, so we accumulate
     * query results separately and add them after exiting read-only mode.
     */
    internal val pendingQueryResults = mutableListOf<WorkflowCommands.WorkflowCommand>()

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
    // This allows kotlinx.coroutines.delay() and withTimeout() to work correctly in workflows
    // by creating durable timers that survive replay.
    internal val workflowDispatcher =
        WorkflowCoroutineDispatcher(
            timerScheduler =
                object : WorkflowTimerScheduler {
                    override fun scheduleTimer(
                        delayMillis: Long,
                        continuation: kotlinx.coroutines.CancellableContinuation<Unit>,
                    ) {
                        scheduleTimerForContinuation(delayMillis, continuation)
                    }

                    override fun scheduleTimeoutCallback(
                        delayMillis: Long,
                        block: Runnable,
                    ): kotlinx.coroutines.DisposableHandle = scheduleTimeoutCallbackTimer(delayMillis, block)
                },
        )
    internal var context: WorkflowContextImpl? = null
    internal var mainCoroutine: Deferred<Any?>? = null
    private var workflowInfo: WorkflowInfo? = null

    /**
     * The workflow instance for this specific run.
     * Created fresh for each workflow execution to ensure clean state.
     */
    internal var workflowInstance: Any? = null

    // Job for the workflow execution - provides unified scope for structured concurrency.
    // This is a CompletableJob so we can explicitly complete it when the workflow terminates.
    // Note: Job(parent) creates a child of the parent but doesn't complete automatically -
    // only coroutines complete automatically. We must call complete() or cancel() explicitly.
    private var workflowExecutionJob: kotlinx.coroutines.CompletableJob? = null

    /**
     * Cancels the workflow execution job when the workflow reaches a terminal state.
     * This is necessary because Job(parent) doesn't automatically complete when children complete,
     * so we must explicitly cancel it to allow proper worker shutdown.
     *
     * Note: We always use cancel() rather than complete() because:
     * 1. complete() doesn't cancel children - it waits for them
     * 2. cancel() immediately cancels the job and all its children
     * 3. The workflow has already reached a terminal state, so cancellation is appropriate
     */
    internal fun terminateWorkflowExecutionJob() {
        // 1. Cancel first - this queues cancellation tasks to the dispatcher
        workflowExecutionJob?.cancel()

        // 2. Process all cancellation tasks so coroutines complete properly
        try {
            workflowDispatcher.processAllWork()
        } catch (e: Exception) {
            // Swallow exceptions during cleanup - we're terminating anyway
            logger.debug("Exception during cleanup: {}", e.message)
        }

        // 3. Clear any remaining work (should be empty, but defensive)
        workflowDispatcher.clear()

        workflowExecutionJob = null
    }

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
     * @return The completion to send back to the server
     */
    suspend fun activate(activation: WorkflowActivation): WorkflowCompletion.WorkflowActivationCompletion =
        withContext(mdcContext + CoroutineName("WorkflowExecutor-activate")) {
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
                    processJob(initJob)
                    runOnce(checkConditions = true)
                }

                // Stage 1: Process patches
                for (job in patchJobs) {
                    processJob(job)
                }
                if (patchJobs.isNotEmpty()) {
                    runOnce(checkConditions = false)
                }

                // Stage 2: Process signals and updates
                for (job in signalAndUpdateJobs) {
                    processJob(job)
                }
                if (signalAndUpdateJobs.isNotEmpty()) {
                    runOnce(checkConditions = true)
                }

                // Stage 3: Process non-query jobs (resolutions, cancellation, etc.)
                for (job in nonQueryJobs) {
                    processJob(job)
                }
                if (nonQueryJobs.isNotEmpty()) {
                    runOnce(checkConditions = true)
                }

                // Check for workflow completion BEFORE processing queries.
                val mainResult = mainCoroutine
                if (mainResult != null && mainResult.isCompleted && queryJobs.isEmpty()) {
                    logger.debug("Main workflow coroutine completed, building terminal completion")
                    return@withContext buildTerminalCompletion(mainResult, methodInfo.returnType)
                }

                // Stage 4: Process queries (read-only mode, no condition checking)
                if (queryJobs.isNotEmpty()) {
                    state.isReadOnly = true
                    try {
                        for (job in queryJobs) {
                            processJob(job)
                        }
                        runOnce(checkConditions = false)
                    } finally {
                        state.isReadOnly = false
                        // Flush accumulated query results as commands
                        for (queryCommand in pendingQueryResults) {
                            state.addCommand(queryCommand)
                        }
                        pendingQueryResults.clear()
                    }
                }

                // Return accumulated commands (query responses only if queries were processed)
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
    internal fun isPatchJob(job: WorkflowActivationJob): Boolean = job.hasNotifyHasPatch()

    /**
     * Checks if a job is a signal or update job.
     * These can mutate workflow state and must be processed before queries.
     */
    internal fun isSignalOrUpdateJob(job: WorkflowActivationJob): Boolean =
        job.hasSignalWorkflow() ||
            job.hasDoUpdate()

    /**
     * Checks if a job is a non-query job (resolutions, cancellation, random seed, etc.).
     * These jobs can mutate state but are not signals/updates.
     * Explicitly excludes InitializeWorkflow (processed in Stage 0) and RemoveFromCache (early exit).
     */
    internal fun isNonQueryJob(job: WorkflowActivationJob): Boolean =
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

    /**
     * Runs one iteration of the workflow event loop, processing all queued work.
     *
     * - Check conditions that may have been satisfied by job handlers
     *
     * @param checkConditions Whether to check await conditions after processing tasks.
     *                        Should be true for stages that can mutate state (signals, updates, resolutions).
     *                        Should be false for patches and queries.
     */
    private fun runOnce(checkConditions: Boolean) {
        do {
            // Process all ready tasks (inner loop in Python's _run_once)
            workflowDispatcher.processAllWork()

            // Check conditions which may add to the ready list
            if (checkConditions) {
                state.checkConditions()
            }
        } while (workflowDispatcher.hasPendingWork())
    }

    private suspend fun processJob(job: WorkflowActivationJob) {
        when {
            job.hasInitializeWorkflow() -> {
                handleInitialize(job.initializeWorkflow)
            }

            job.hasFireTimer() -> {
                logger.debug("Timer fired: seq={}", job.fireTimer.seq)
                val callback = state.resolveTimer(job.fireTimer.seq)
                // If there's a timeout callback (from withTimeout), execute it
                if (callback != null) {
                    workflowDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
                        callback.run()
                    }
                }
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
                handlePatchJob(job.notifyHasPatch)
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

    private fun handleInitialize(init: InitializeWorkflow) {
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
                    Instant.fromEpochMilliseconds(
                        startTime.toEpochMilliseconds(),
                    ),
            )

        // Update random seed
        state.randomSeed = init.randomnessSeed

        // Create a fresh workflow instance for this run
        // This ensures each execution has clean state and replay doesn't accumulate state
        workflowInstance = methodInfo.instanceFactory()

        // Create the workflow execution job as a child of the parentJob (from rootExecutorJob)
        // SupervisorJob ensures one workflow's failure doesn't cancel other workflows
        workflowExecutionJob = SupervisorJob(parentJob)

        // Create workflow context with the execution job as parent
        // This ensures launch {} calls within the workflow are properly scoped
        // The taskQueueScope provides hierarchical attribute lookup (taskQueue -> application)
        context =
            WorkflowContextImpl(
                state = state,
                info = workflowInfo!!,
                serializer = serializer,
                workflowDispatcher = workflowDispatcher,
                parentJob = workflowExecutionJob!!,
                parentScope = taskQueueScope,
                mdcContext = mdcContext,
            )

        // Start the main workflow coroutine
        mainCoroutine = startWorkflowCoroutine(init)
    }

    private fun startWorkflowCoroutine(init: InitializeWorkflow): Deferred<Any?> {
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
                        method.callSuspend(workflowInstance!!, ctx, *args)
                    } else {
                        method.call(workflowInstance!!, ctx, *args)
                    }
                } else {
                    // Method does not use context receiver
                    if (methodInfo.isSuspend) {
                        method.callSuspend(workflowInstance!!, *args)
                    } else {
                        method.call(workflowInstance!!, *args)
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

    /**
     * Deserializes workflow/handler arguments from Payloads to typed objects.
     */
    internal fun deserializeArguments(
        payloads: List<Payload>,
        parameterTypes: List<KType>,
    ): Array<Any?> =
        payloads
            .zip(parameterTypes)
            .map { (payload, type) ->
                serializer.deserialize(type, payload)
            }.toTypedArray()

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

        state.cancelRequested = true

        // Cancel first, then process, then clear (same rationale as terminateWorkflowExecutionJob)
        mainCoroutine?.cancel(WorkflowCancelledException())

        // Process cancellation tasks
        try {
            workflowDispatcher.processAllWork()
        } catch (e: Exception) {
            logger.debug("Exception during eviction cleanup: {}", e.message)
        }

        workflowDispatcher.clear()
        state.clear()
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

    /**
     * Checks if this executor is for eviction.
     */
    fun isEviction(activation: WorkflowActivation): Boolean = activation.jobsList.any { it.hasRemoveFromCache() }
}
