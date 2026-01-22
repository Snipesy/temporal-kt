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
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
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

    /**
     * Accumulated query results during read-only mode.
     * We can't use state.addCommand() in read-only mode, so we accumulate
     * query results separately and add them after exiting read-only mode.
     */
    private val pendingQueryResults = mutableListOf<WorkflowCommands.WorkflowCommand>()

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

    /**
     * The workflow instance for this specific run.
     * Created fresh for each workflow execution to ensure clean state.
     */
    private var workflowInstance: Any? = null

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
                    runOnce(checkConditions = true)
                }

                // Stage 1: Process patches
                for (job in patchJobs) {
                    processJob(job, activation, scope)
                }
                if (patchJobs.isNotEmpty()) {
                    runOnce(checkConditions = false)
                }

                // Stage 2: Process signals and updates
                for (job in signalAndUpdateJobs) {
                    processJob(job, activation, scope)
                }
                if (signalAndUpdateJobs.isNotEmpty()) {
                    runOnce(checkConditions = true)
                }

                // Stage 3: Process non-query jobs (resolutions, cancellation, etc.)
                for (job in nonQueryJobs) {
                    processJob(job, activation, scope)
                }
                if (nonQueryJobs.isNotEmpty()) {
                    runOnce(checkConditions = true)
                }

                // Check for workflow completion BEFORE processing queries.
                val mainResult = mainCoroutine
                if (mainResult != null && mainResult.isCompleted && queryJobs.isEmpty()) {
                    logger.debug("Main workflow coroutine completed, building terminal completion")
                    return@withContext buildTerminalCompletion(mainResult)
                }

                // Stage 4: Process queries (read-only mode, no condition checking)
                if (queryJobs.isNotEmpty()) {
                    state.isReadOnly = true
                    try {
                        for (job in queryJobs) {
                            processJob(job, activation, scope)
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
                    Instant.fromEpochMilliseconds(
                        startTime.toEpochMilliseconds(),
                    ),
            )

        // Update random seed
        state.randomSeed = init.randomnessSeed

        // Create a fresh workflow instance for this run
        // This ensures each execution has clean state and replay doesn't accumulate state
        workflowInstance = methodInfo.instanceFactory()

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

    private fun deserializeArguments(
        payloads: List<Payload>,
        parameterTypes: List<KType>,
    ): Array<Any?> =
        payloads
            .zip(parameterTypes)
            .map { (payload, type) ->
                serializer.deserialize(typeInfoOf(type), payload)
            }.toTypedArray()

    private suspend fun handleSignal(signal: coresdk.workflow_activation.WorkflowActivationOuterClass.SignalWorkflow) {
        val signalName = signal.signalName
        val inputPayloads = signal.inputList

        logger.debug("Processing signal: name={}, args={}", signalName, inputPayloads.size)

        val ctx = context as? WorkflowContextImpl

        // Check runtime-registered handlers first (they take precedence)
        val runtimeHandler = ctx?.runtimeSignalHandlers?.get(signalName)
        val runtimeDynamicHandler = ctx?.runtimeDynamicSignalHandler

        // Then check annotation-defined handlers
        val annotationHandler = methodInfo.signalHandlers[signalName]
        val annotationDynamicHandler = methodInfo.signalHandlers[null]

        // Determine which handler to use:
        // 1. Runtime handler for specific signal
        // 2. Annotation handler for specific signal
        // 3. Runtime dynamic handler
        // 4. Annotation dynamic handler
        // 5. Buffer the signal for later
        when {
            runtimeHandler != null -> {
                invokeRuntimeSignalHandler(runtimeHandler, inputPayloads)
            }
            annotationHandler != null -> {
                invokeAnnotationSignalHandler(annotationHandler, signal, isDynamic = false)
            }
            runtimeDynamicHandler != null -> {
                invokeRuntimeDynamicSignalHandler(runtimeDynamicHandler, signalName, inputPayloads)
            }
            annotationDynamicHandler != null -> {
                invokeAnnotationSignalHandler(annotationDynamicHandler, signal, isDynamic = true)
            }
            else -> {
                // Buffer the signal for later when a handler is registered
                logger.debug("No handler found for signal '{}', buffering for later", signalName)
                ctx?.bufferedSignals?.getOrPut(signalName) { mutableListOf() }?.add(signal)
            }
        }
    }

    /**
     * Invokes a runtime-registered signal handler.
     */
    private suspend fun invokeRuntimeSignalHandler(
        handler: suspend (List<Payload>) -> Unit,
        args: List<Payload>,
    ) {
        try {
            handler(args)
        } catch (e: Exception) {
            // Signal handlers should not fail the workflow
            // Log the error but continue
            logger.warn("Signal handler threw exception: {}", e.message, e)
        }
    }

    /**
     * Invokes a runtime-registered dynamic signal handler.
     */
    private suspend fun invokeRuntimeDynamicSignalHandler(
        handler: suspend (signalName: String, args: List<Payload>) -> Unit,
        signalName: String,
        args: List<Payload>,
    ) {
        try {
            handler(signalName, args)
        } catch (e: Exception) {
            // Signal handlers should not fail the workflow
            logger.warn("Dynamic signal handler threw exception: {}", e.message, e)
        }
    }

    /**
     * Invokes an annotation-defined signal handler.
     */
    private suspend fun invokeAnnotationSignalHandler(
        handler: SignalHandlerInfo,
        signal: coresdk.workflow_activation.WorkflowActivationOuterClass.SignalWorkflow,
        isDynamic: Boolean,
    ) {
        try {
            val ctx = context ?: error("WorkflowContext not initialized")
            val method = handler.handlerMethod

            // For dynamic handlers, the first argument is the signal name
            val args =
                if (isDynamic) {
                    val remainingParamTypes = handler.parameterTypes.drop(1)
                    val deserializedArgs = deserializeArguments(signal.inputList, remainingParamTypes)
                    arrayOf(signal.signalName, *deserializedArgs)
                } else {
                    deserializeArguments(signal.inputList, handler.parameterTypes)
                }

            if (handler.hasContextReceiver) {
                if (handler.isSuspend) {
                    method.callSuspend(workflowInstance!!, ctx, *args)
                } else {
                    method.call(workflowInstance!!, ctx, *args)
                }
            } else {
                if (handler.isSuspend) {
                    method.callSuspend(workflowInstance!!, *args)
                } else {
                    method.call(workflowInstance!!, *args)
                }
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e
            logger.warn("Signal handler threw exception: {}", cause.message, cause)
        } catch (e: Exception) {
            // Signal handlers should not fail the workflow
            logger.warn("Signal handler threw exception: {}", e.message, e)
        }
    }

    private suspend fun handleQuery(query: coresdk.workflow_activation.WorkflowActivationOuterClass.QueryWorkflow) {
        val queryId = query.queryId
        val queryType = query.queryType

        logger.debug("Processing query: id={}, type={}", queryId, queryType)

        val ctx = context as? WorkflowContextImpl

        // Check runtime-registered handlers first (they take precedence)
        val runtimeHandler = ctx?.runtimeQueryHandlers?.get(queryType)
        val runtimeDynamicHandler = ctx?.runtimeDynamicQueryHandler

        // Then check annotation-defined handlers
        val annotationHandler = methodInfo.queryHandlers[queryType]
        val annotationDynamicHandler = methodInfo.queryHandlers[null]

        // Determine which handler to use:
        // 1. Runtime handler for specific query type
        // 2. Annotation handler for specific query type
        // 3. Runtime dynamic handler
        // 4. Annotation dynamic handler
        when {
            runtimeHandler != null -> {
                handleRuntimeQuery(queryId, queryType, runtimeHandler, query.argumentsList, isDynamic = false)
            }
            annotationHandler != null -> {
                handleAnnotationQuery(queryId, queryType, annotationHandler, query, isDynamic = false)
            }
            runtimeDynamicHandler != null -> {
                handleRuntimeDynamicQuery(queryId, queryType, runtimeDynamicHandler, query.argumentsList)
            }
            annotationDynamicHandler != null -> {
                handleAnnotationQuery(queryId, queryType, annotationDynamicHandler, query, isDynamic = true)
            }
            else -> {
                logger.debug("No handler found for query type: {}", queryType)
                addFailedQueryResult(queryId, "Unknown query type: $queryType")
            }
        }
    }

    /**
     * Handles a runtime-registered query handler (specific query type).
     * Runtime handlers receive raw Payloads and return a Payload directly.
     */
    private suspend fun handleRuntimeQuery(
        queryId: String,
        queryType: String,
        handler: suspend (List<Payload>) -> Payload,
        args: List<Payload>,
        isDynamic: Boolean,
    ) {
        try {
            val resultPayload = handler(args)
            addSuccessQueryResult(queryId, resultPayload)
        } catch (e: ReadOnlyContextException) {
            logger.warn("Query handler attempted state mutation: {}", e.message)
            addFailedQueryResult(queryId, "Query attempted state mutation: ${e.message}")
        } catch (e: Exception) {
            logger.warn("Query handler threw exception: {}", e.message)
            addFailedQueryResult(queryId, "Query failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Handles a runtime-registered dynamic query handler.
     * Dynamic handlers receive the query type name and raw Payloads, and return a Payload.
     */
    private suspend fun handleRuntimeDynamicQuery(
        queryId: String,
        queryType: String,
        handler: suspend (queryType: String, args: List<Payload>) -> Payload,
        args: List<Payload>,
    ) {
        try {
            val resultPayload = handler(queryType, args)
            addSuccessQueryResult(queryId, resultPayload)
        } catch (e: ReadOnlyContextException) {
            logger.warn("Query handler attempted state mutation: {}", e.message)
            addFailedQueryResult(queryId, "Query attempted state mutation: ${e.message}")
        } catch (e: Exception) {
            logger.warn("Query handler threw exception: {}", e.message)
            addFailedQueryResult(queryId, "Query failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Handles an annotation-defined query handler.
     */
    private suspend fun handleAnnotationQuery(
        queryId: String,
        queryType: String,
        handler: QueryHandlerInfo,
        query: coresdk.workflow_activation.WorkflowActivationOuterClass.QueryWorkflow,
        isDynamic: Boolean,
    ) {
        try {
            // For dynamic handlers, the first argument is the query type name
            val args =
                if (isDynamic) {
                    val remainingParamTypes = handler.parameterTypes.drop(1)
                    val deserializedArgs = deserializeArguments(query.argumentsList, remainingParamTypes)
                    arrayOf(queryType, *deserializedArgs)
                } else {
                    deserializeArguments(query.argumentsList, handler.parameterTypes)
                }

            val result = invokeQueryHandler(handler, args)

            // Serialize the result using the handler's declared return type
            val payload =
                if (result == Unit || handler.returnType.classifier == Unit::class) {
                    Payload.getDefaultInstance()
                } else {
                    serializer.serialize(typeInfoOf(handler.returnType), result)
                }

            addSuccessQueryResult(queryId, payload)
        } catch (e: ReadOnlyContextException) {
            logger.warn("Query handler attempted state mutation: {}", e.message)
            addFailedQueryResult(queryId, "Query attempted state mutation: ${e.message}")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e
            logger.warn("Query handler threw exception: {}", cause.message)
            addFailedQueryResult(queryId, "Query failed: ${cause.message ?: cause::class.simpleName}")
        } catch (e: Exception) {
            logger.warn("Query handler threw exception: {}", e.message)
            addFailedQueryResult(queryId, "Query failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Invokes an annotation-defined query handler method.
     */
    private suspend fun invokeQueryHandler(
        handler: QueryHandlerInfo,
        args: Array<Any?>,
    ): Any? {
        val ctx = context ?: error("WorkflowContext not initialized")
        val method = handler.handlerMethod ?: error("Handler method is null for annotation-defined handler")

        return if (handler.hasContextReceiver) {
            if (handler.isSuspend) {
                method.callSuspend(workflowInstance!!, ctx, *args)
            } else {
                method.call(workflowInstance!!, ctx, *args)
            }
        } else {
            if (handler.isSuspend) {
                method.callSuspend(workflowInstance!!, *args)
            } else {
                method.call(workflowInstance!!, *args)
            }
        }
    }

    /**
     * Adds a successful query result to the pending results.
     */
    private fun addSuccessQueryResult(
        queryId: String,
        payload: Payload,
    ) {
        val queryResult =
            WorkflowCommands.QueryResult
                .newBuilder()
                .setQueryId(queryId)
                .setSucceeded(
                    WorkflowCommands.QuerySuccess
                        .newBuilder()
                        .setResponse(payload),
                ).build()

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setRespondToQuery(queryResult)
                .build()

        pendingQueryResults.add(command)
    }

    /**
     * Adds a failed query result to the pending results.
     */
    private fun addFailedQueryResult(
        queryId: String,
        errorMessage: String,
    ) {
        val failure =
            Failure
                .newBuilder()
                .setMessage(errorMessage)
                .setSource("Kotlin")
                .build()

        val queryResult =
            WorkflowCommands.QueryResult
                .newBuilder()
                .setQueryId(queryId)
                .setFailed(failure)
                .build()

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setRespondToQuery(queryResult)
                .build()

        pendingQueryResults.add(command)
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

    private suspend fun handleUpdate(update: coresdk.workflow_activation.WorkflowActivationOuterClass.DoUpdate) {
        val updateName = update.name
        val protocolInstanceId = update.protocolInstanceId
        val inputPayloads = update.inputList
        val runValidator = update.runValidator

        logger.debug(
            "Processing update: name={}, id={}, protocol_instance_id={}, run_validator={}",
            updateName,
            update.id,
            protocolInstanceId,
            runValidator,
        )

        val ctx = context

        // Check runtime-registered handlers first (they take precedence)
        val runtimeHandler = ctx?.runtimeUpdateHandlers?.get(updateName)
        val runtimeDynamicHandler = ctx?.runtimeDynamicUpdateHandler

        // Then check annotation-defined handlers
        val annotationHandler = methodInfo.updateHandlers[updateName]
        val annotationDynamicHandler = methodInfo.updateHandlers[null]

        // Determine which handler to use:
        // 1. Runtime handler for specific update
        // 2. Annotation handler for specific update
        // 3. Runtime dynamic handler
        // 4. Annotation dynamic handler
        when {
            runtimeHandler != null -> {
                invokeRuntimeUpdateHandler(
                    runtimeHandler,
                    protocolInstanceId,
                    inputPayloads,
                    runValidator,
                )
            }
            annotationHandler != null -> {
                invokeAnnotationUpdateHandler(
                    annotationHandler,
                    update,
                    isDynamic = false,
                )
            }
            runtimeDynamicHandler != null -> {
                invokeRuntimeDynamicUpdateHandler(
                    runtimeDynamicHandler,
                    protocolInstanceId,
                    updateName,
                    inputPayloads,
                    runValidator,
                )
            }
            annotationDynamicHandler != null -> {
                invokeAnnotationUpdateHandler(
                    annotationDynamicHandler,
                    update,
                    isDynamic = true,
                )
            }
            else -> {
                // Unlike signals, updates fail immediately if no handler exists
                logger.debug("No handler found for update '{}', rejecting", updateName)
                addUpdateRejectedCommand(protocolInstanceId, "Unknown update type: $updateName")
            }
        }
    }

    /**
     * Invokes a runtime-registered update handler.
     */
    private suspend fun invokeRuntimeUpdateHandler(
        handler: UpdateHandlerEntry,
        protocolInstanceId: String,
        args: List<Payload>,
        runValidator: Boolean,
    ) {
        try {
            // Run validator if requested (in read-only mode)
            if (runValidator && handler.validator != null) {
                state.isReadOnly = true
                try {
                    handler.validator.invoke(args)
                } finally {
                    state.isReadOnly = false
                }
            }

            // Accept the update
            addUpdateAcceptedCommand(protocolInstanceId)

            // Execute the handler
            val resultPayload = handler.handler(args)

            // Complete with result
            addUpdateCompletedCommand(protocolInstanceId, resultPayload)
        } catch (e: ReadOnlyContextException) {
            logger.warn("Update validator attempted state mutation: {}", e.message)
            addUpdateRejectedCommand(protocolInstanceId, "Validator attempted state mutation: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // Validation failure
            logger.warn("Update validation failed: {}", e.message)
            addUpdateRejectedCommand(protocolInstanceId, e.message ?: "Validation failed")
        } catch (e: Exception) {
            logger.warn("Update handler threw exception: {}", e.message, e)
            addUpdateRejectedCommand(protocolInstanceId, "Update failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Invokes a runtime-registered dynamic update handler.
     */
    private suspend fun invokeRuntimeDynamicUpdateHandler(
        handler: DynamicUpdateHandlerEntry,
        protocolInstanceId: String,
        updateName: String,
        args: List<Payload>,
        runValidator: Boolean,
    ) {
        try {
            // Run validator if requested (in read-only mode)
            if (runValidator && handler.validator != null) {
                state.isReadOnly = true
                try {
                    handler.validator.invoke(updateName, args)
                } finally {
                    state.isReadOnly = false
                }
            }

            // Accept the update
            addUpdateAcceptedCommand(protocolInstanceId)

            // Execute the handler
            val resultPayload = handler.handler(updateName, args)

            // Complete with result
            addUpdateCompletedCommand(protocolInstanceId, resultPayload)
        } catch (e: ReadOnlyContextException) {
            logger.warn("Update validator attempted state mutation: {}", e.message)
            addUpdateRejectedCommand(protocolInstanceId, "Validator attempted state mutation: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // Validation failure
            logger.warn("Update validation failed: {}", e.message)
            addUpdateRejectedCommand(protocolInstanceId, e.message ?: "Validation failed")
        } catch (e: Exception) {
            logger.warn("Dynamic update handler threw exception: {}", e.message, e)
            addUpdateRejectedCommand(protocolInstanceId, "Update failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Invokes an annotation-defined update handler.
     */
    private suspend fun invokeAnnotationUpdateHandler(
        handler: UpdateHandlerInfo,
        update: coresdk.workflow_activation.WorkflowActivationOuterClass.DoUpdate,
        isDynamic: Boolean,
    ) {
        val protocolInstanceId = update.protocolInstanceId
        val runValidator = update.runValidator

        try {
            val ctx = context ?: error("WorkflowContext not initialized")
            val method = handler.handlerMethod

            // For dynamic handlers, the first argument is the update name
            val args =
                if (isDynamic) {
                    val remainingParamTypes = handler.parameterTypes.drop(1)
                    val deserializedArgs = deserializeArguments(update.inputList, remainingParamTypes)
                    arrayOf(update.name, *deserializedArgs)
                } else {
                    deserializeArguments(update.inputList, handler.parameterTypes)
                }

            // Run validator if requested (in read-only mode)
            if (runValidator && handler.validatorMethod != null) {
                state.isReadOnly = true
                try {
                    invokeValidatorMethod(handler.validatorMethod, args, ctx)
                } finally {
                    state.isReadOnly = false
                }
            }

            // Accept the update
            addUpdateAcceptedCommand(protocolInstanceId)

            // Execute the handler
            val result =
                if (handler.hasContextReceiver) {
                    if (handler.isSuspend) {
                        method.callSuspend(workflowInstance!!, ctx, *args)
                    } else {
                        method.call(workflowInstance!!, ctx, *args)
                    }
                } else {
                    if (handler.isSuspend) {
                        method.callSuspend(workflowInstance!!, *args)
                    } else {
                        method.call(workflowInstance!!, *args)
                    }
                }

            // Serialize the result
            val resultPayload =
                if (result == Unit || handler.returnType.classifier == Unit::class) {
                    Payload.getDefaultInstance()
                } else {
                    serializer.serialize(typeInfoOf(handler.returnType), result)
                }

            // Complete with result
            addUpdateCompletedCommand(protocolInstanceId, resultPayload)
        } catch (e: ReadOnlyContextException) {
            logger.warn("Update validator attempted state mutation: {}", e.message)
            addUpdateRejectedCommand(protocolInstanceId, "Validator attempted state mutation: ${e.message}")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is IllegalArgumentException) {
                // Validation failure
                logger.warn("Update validation failed: {}", cause.message)
                addUpdateRejectedCommand(protocolInstanceId, cause.message ?: "Validation failed")
            } else {
                logger.warn("Update handler threw exception: {}", cause.message, cause)
                addUpdateRejectedCommand(
                    protocolInstanceId,
                    "Update failed: ${cause.message ?: cause::class.simpleName}",
                )
            }
        } catch (e: IllegalArgumentException) {
            // Validation failure
            logger.warn("Update validation failed: {}", e.message)
            addUpdateRejectedCommand(protocolInstanceId, e.message ?: "Validation failed")
        } catch (e: Exception) {
            logger.warn("Update handler threw exception: {}", e.message, e)
            addUpdateRejectedCommand(protocolInstanceId, "Update failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Invokes an update validator method.
     */
    private fun invokeValidatorMethod(
        validator: kotlin.reflect.KFunction<*>,
        args: Array<Any?>,
        ctx: WorkflowContextImpl,
    ) {
        // Validators cannot be suspend functions
        // Check if validator uses WorkflowContext as extension receiver
        val extensionReceiver = validator.extensionReceiverParameter
        val hasContextReceiver =
            extensionReceiver?.type?.classifier == com.surrealdev.temporal.workflow.WorkflowContext::class

        if (hasContextReceiver) {
            validator.call(workflowInstance!!, ctx, *args)
        } else {
            validator.call(workflowInstance!!, *args)
        }
    }

    /**
     * Adds an update accepted command.
     */
    private fun addUpdateAcceptedCommand(protocolInstanceId: String) {
        val updateResponse =
            WorkflowCommands.UpdateResponse
                .newBuilder()
                .setProtocolInstanceId(protocolInstanceId)
                .setAccepted(
                    com.google.protobuf.Empty
                        .getDefaultInstance(),
                ).build()

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setUpdateResponse(updateResponse)
                .build()

        state.addCommand(command)
    }

    /**
     * Adds an update rejected command.
     */
    private fun addUpdateRejectedCommand(
        protocolInstanceId: String,
        message: String,
    ) {
        val failure =
            Failure
                .newBuilder()
                .setMessage(message)
                .setSource("Kotlin")
                .build()

        val updateResponse =
            WorkflowCommands.UpdateResponse
                .newBuilder()
                .setProtocolInstanceId(protocolInstanceId)
                .setRejected(failure)
                .build()

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setUpdateResponse(updateResponse)
                .build()

        state.addCommand(command)
    }

    /**
     * Adds an update completed command.
     */
    private fun addUpdateCompletedCommand(
        protocolInstanceId: String,
        payload: Payload,
    ) {
        val updateResponse =
            WorkflowCommands.UpdateResponse
                .newBuilder()
                .setProtocolInstanceId(protocolInstanceId)
                .setCompleted(payload)
                .build()

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setUpdateResponse(updateResponse)
                .build()

        state.addCommand(command)
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
