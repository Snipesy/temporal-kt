package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.application.plugin.HookRegistry
import com.surrealdev.temporal.application.plugin.HookRegistryImpl
import com.surrealdev.temporal.application.plugin.interceptor.ContinueAsNew
import com.surrealdev.temporal.application.plugin.interceptor.ContinueAsNewInput
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleActivity
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleActivityInput
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleLocalActivity
import com.surrealdev.temporal.application.plugin.interceptor.ScheduleLocalActivityInput
import com.surrealdev.temporal.application.plugin.interceptor.Sleep
import com.surrealdev.temporal.application.plugin.interceptor.SleepInput
import com.surrealdev.temporal.application.plugin.interceptor.StartChildWorkflow
import com.surrealdev.temporal.application.plugin.interceptor.StartChildWorkflowInput
import com.surrealdev.temporal.common.RetryPolicy
import com.surrealdev.temporal.common.SearchAttributeEncoder
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.TypedSearchAttributes
import com.surrealdev.temporal.common.exceptions.WorkflowConditionTimeoutException
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.safeEncode
import com.surrealdev.temporal.serialization.safeSerialize
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.ExecutionScope
import com.surrealdev.temporal.workflow.ActivityCancellationType
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.ChildWorkflowCancellationType
import com.surrealdev.temporal.workflow.ChildWorkflowHandle
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.ContinueAsNewException
import com.surrealdev.temporal.workflow.ContinueAsNewOptions
import com.surrealdev.temporal.workflow.LocalActivityHandle
import com.surrealdev.temporal.workflow.LocalActivityOptions
import com.surrealdev.temporal.workflow.ParentClosePolicy
import com.surrealdev.temporal.workflow.RemoteActivityHandle
import com.surrealdev.temporal.workflow.SuggestContinueAsNewReason
import com.surrealdev.temporal.workflow.VersioningIntent
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.WorkflowInfo
import coresdk.child_workflow.ChildWorkflow
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.SearchAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.logging.Logger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Implementation of [WorkflowContext] for workflow execution.
 *
 * This context provides deterministic operations within a workflow:
 * - Timer scheduling (via [sleep])
 * - Activity scheduling (via [startActivityWithPayloads])
 * - Deterministic time and random values
 *
 * All operations that interact with the external world go through
 * the command system to ensure deterministic replay.
 *
 * The parent job is provided by the workflow execution scope to ensure
 * proper structured concurrency. When a child coroutine created with `launch`
 * fails, it cancels the entire workflow execution.
 */
internal class WorkflowContextImpl(
    private val state: WorkflowState,
    override val info: WorkflowInfo,
    override val serializer: PayloadSerializer,
    internal val codec: PayloadCodec,
    internal val workflowDispatcher: WorkflowCoroutineDispatcher,
    parentJob: Job,
    private val handlerJob: Job,
    override val parentScope: AttributeScope,
    internal val hookRegistry: HookRegistry = HookRegistryImpl.EMPTY,
) : WorkflowContext,
    ExecutionScope {
    companion object {
        private val logger = Logger.getLogger(WorkflowContextImpl::class.java.name)
    }

    /**
     * Verifies that the current coroutine is running on the workflow dispatcher.
     * Throws [EscapedDispatcherException] if called from an escaped context.
     *
     * Call this at the start of any workflow operation that creates commands or mutates state.
     */
    internal suspend fun ensureOnWorkflowDispatcher(operationName: String) {
        if (currentCoroutineContext()[SkipDispatcherCheck.Key] != null) return
        val currentDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        if (currentDispatcher !== workflowDispatcher) {
            throw EscapedDispatcherException(
                "[TKT1108] Workflow operation '$operationName' called from wrong dispatcher. " +
                    "Workflow operations must be called from the workflow dispatcher, not from " +
                    "withContext(Dispatchers.IO) or similar escaped contexts. " +
                    "This is non-deterministic and will cause replay failures. " +
                    "workflowId=${info.workflowId}, runId=${info.runId}",
            )
        }
    }

    // Workflow executions have their own attributes (currently empty, for future use)
    override val attributes: Attributes = Attributes(concurrent = false)
    override val isWorkflowContext: Boolean = true

    // History metrics from state, updated on each activation
    override val historyLength: Int
        get() = state.historyLength

    override val historySizeBytes: Long
        get() = state.historySizeBytes

    override fun isContinueAsNewSuggested(): Boolean = state.continueAsNewSuggested

    override val continueAsNewSuggestedReasons: Set<SuggestContinueAsNewReason>
        get() = state.suggestContinueAsNewReasons

    override val isTargetWorkerDeploymentVersionChanged: Boolean
        get() = state.targetWorkerDeploymentVersionChanged

    // Create a child job for this workflow - failures propagate to parent
    internal val job = Job(parentJob)

    /**
     * Additional coroutine context elements contributed by plugins via the
     * [BuildWorkflowCoroutineContext][com.surrealdev.temporal.application.plugin.hooks.BuildWorkflowCoroutineContext] hook. Included in [coroutineContext] and
     * [launchHandler] contexts so all child coroutines inherit them.
     */
    internal var pluginCoroutineContext: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext

    private val deterministicRandom = DeterministicRandom(state.randomSeed)

    /**
     * Runtime-registered named query handlers.
     * Keys are query names.
     */
    internal val runtimeQueryHandlers =
        mutableMapOf<String, (suspend (TemporalPayloads) -> TemporalPayload)>()

    /**
     * Runtime-registered dynamic query handler (catches all unhandled queries).
     */
    internal var runtimeDynamicQueryHandler:
        (
            suspend (
                queryType: String,
                args: TemporalPayloads,
            ) -> TemporalPayload
        )? = null

    /**
     * Runtime-registered named signal handlers.
     * Keys are signal names.
     */
    internal val runtimeSignalHandlers =
        mutableMapOf<String, suspend (TemporalPayloads) -> Unit>()

    /**
     * Runtime-registered dynamic signal handler (catches all unhandled signals).
     */
    internal var runtimeDynamicSignalHandler:
        (suspend (signalName: String, args: TemporalPayloads) -> Unit)? = null

    /**
     * Buffered signals waiting for handlers to be registered.
     * Keys are signal names, values are lists of decoded payloads.
     */
    internal val bufferedSignals =
        mutableMapOf<String, MutableList<TemporalPayloads>>()

    /**
     * Runtime-registered named update handlers.
     * Keys are update names.
     */
    internal val runtimeUpdateHandlers = mutableMapOf<String, UpdateHandlerEntry>()

    /**
     * Runtime-registered dynamic update handler (catches all unhandled updates).
     */
    internal var runtimeDynamicUpdateHandler: DynamicUpdateHandlerEntry? = null

    override val coroutineContext: CoroutineContext
        get() = job + workflowDispatcher + pluginCoroutineContext + this

    /**
     * Updates the random seed (called when UpdateRandomSeed job is received).
     */
    internal fun updateRandomSeed(newSeed: Long) {
        deterministicRandom.updateSeed(newSeed)
        state.randomSeed = newSeed
    }

    /**
     * Launches a handler coroutine under the handler job hierarchy.
     *
     * This is used for signal and update handlers which need to:
     * 1. Run on workflowDispatcher (so they can call workflow operations)
     * 2. NOT be cancelled when the main workflow completes
     * 3. Inherit plugin-contributed context (MDC, OTel, etc.)
     * 4. Be properly managed (not orphan Jobs)
     *
     * The handler job is a sibling of the workflow execution job under parentJob,
     * so handlers continue running after workflow completion but are cancelled
     * during eviction.
     *
     * Note: If the handler job has been cancelled (after terminal completion), the
     * handler still runs but under a temporary job.
     */
    internal fun launchHandler(block: suspend CoroutineScope.() -> Unit): Job {
        // If handlerJob is cancelled (after terminal completion), use a temporary job.
        // This handles edge cases where activations come after terminal completion.
        val effectiveJob = if (handlerJob.isActive) handlerJob else Job()

        var context: CoroutineContext = effectiveJob + workflowDispatcher
        context += pluginCoroutineContext
        context += this
        return CoroutineScope(context).launch(block = block)
    }

    /**
     * Starts an activity execution and returns a handle for managing it.
     *
     * @throws IllegalArgumentException if validation fails (invalid timeouts, priority, etc.)
     * @throws ReadOnlyContextException if called during query processing
     */
    override suspend fun startActivityWithPayloads(
        activityType: String,
        args: TemporalPayloads,
        options: ActivityOptions,
    ): RemoteActivityHandle {
        ensureOnWorkflowDispatcher("startActivity")
        logger.fine("Starting activity: type=$activityType, options=$options")

        // Execute through the interceptor chain
        val interceptorInput =
            ScheduleActivityInput(
                activityType = activityType,
                args = args,
                options = options,
            )
        val chain = hookRegistry.chain(ScheduleActivity)
        return chain.execute(interceptorInput) { input ->
            startActivityInternal(input)
        }
    }

    private suspend fun startActivityInternal(input: ScheduleActivityInput): RemoteActivityHandle {
        val activityType = input.activityType
        val args = input.args
        val options = input.options
        // ========== Section 1: Validation ==========

        // 1. Activity type validation
        require(activityType.isNotBlank()) {
            "activityType must not be blank"
        }

        // 2. Timeout requirements - at least one required
        require(options.startToCloseTimeout != null || options.scheduleToCloseTimeout != null) {
            "At least one of startToCloseTimeout or scheduleToCloseTimeout must be set"
        }

        // 3. Timeout positivity checks
        options.startToCloseTimeout?.let { timeout ->
            require(timeout.isPositive()) {
                "startToCloseTimeout must be positive, got: $timeout"
            }
        }

        options.scheduleToCloseTimeout?.let { timeout ->
            require(timeout.isPositive()) {
                "scheduleToCloseTimeout must be positive, got: $timeout"
            }
        }

        options.scheduleToStartTimeout?.let { timeout ->
            require(timeout.isPositive()) {
                "scheduleToStartTimeout must be positive, got: $timeout"
            }
        }

        options.heartbeatTimeout?.let { timeout ->
            require(timeout.isPositive()) {
                "heartbeatTimeout must be positive, got: $timeout"
            }
        }

        // 4. Timeout relationships
        if (options.startToCloseTimeout != null && options.scheduleToCloseTimeout != null) {
            require(options.scheduleToCloseTimeout >= options.startToCloseTimeout) {
                "scheduleToCloseTimeout (${options.scheduleToCloseTimeout}) must be >= " +
                    "startToCloseTimeout (${options.startToCloseTimeout})"
            }
        }

        if (options.scheduleToStartTimeout != null && options.scheduleToCloseTimeout != null) {
            require(options.scheduleToStartTimeout <= options.scheduleToCloseTimeout) {
                "scheduleToStartTimeout (${options.scheduleToStartTimeout}) must be <= " +
                    "scheduleToCloseTimeout (${options.scheduleToCloseTimeout})"
            }
        }

        // 4b. Three-timeout relationship validation
        if (options.scheduleToStartTimeout != null &&
            options.startToCloseTimeout != null &&
            options.scheduleToCloseTimeout != null
        ) {
            val sum = options.scheduleToStartTimeout + options.startToCloseTimeout
            require(sum <= options.scheduleToCloseTimeout) {
                "scheduleToStartTimeout (${options.scheduleToStartTimeout}) + " +
                    "startToCloseTimeout (${options.startToCloseTimeout}) = $sum " +
                    "must be <= scheduleToCloseTimeout (${options.scheduleToCloseTimeout})"
            }
        }

        // 5. Heartbeat warning (not an error)
        if (options.heartbeatTimeout != null && options.startToCloseTimeout != null) {
            if (options.heartbeatTimeout >= options.startToCloseTimeout) {
                logger.warning(
                    "heartbeatTimeout (${options.heartbeatTimeout}) >= startToCloseTimeout " +
                        "(${options.startToCloseTimeout}). Heartbeat timeout should typically be " +
                        "shorter than startToCloseTimeout for effective cancellation detection.",
                )
            }
        }

        // 6. Priority validation - 0 means unset (server default); lower keys run first
        require(options.priority >= 0) {
            "priority must be >= 0 (0 = unset/server default), got: ${options.priority}"
        }

        // 7. RetryPolicy validation
        options.retryPolicy?.let { policy ->
            require(policy.backoffCoefficient > 1.0) {
                "RetryPolicy backoffCoefficient must be > 1.0, got: ${policy.backoffCoefficient}"
            }

            require(policy.maximumAttempts >= 0) {
                "RetryPolicy maximumAttempts must be >= 0, got: ${policy.maximumAttempts}"
            }

            if (policy.maximumInterval != null) {
                require(policy.maximumInterval >= policy.initialInterval) {
                    "RetryPolicy maximumInterval (${policy.maximumInterval}) must be >= " +
                        "initialInterval (${policy.initialInterval})"
                }
            }
        }

        logger.fine("Activity validation passed: type=$activityType")

        // ========== Section 2: Sequence & ID Generation ==========

        val seq = state.nextSeq()
        val activityId = options.activityId ?: "$seq"

        logger.fine("Generated activity identifiers: type=$activityType, id=$activityId, seq=$seq")

        // ========== Section 3: Command Building ==========

        logger.fine("Building ScheduleActivity command: type=$activityType, id=$activityId")

        val scheduleActivityBuilder =
            WorkflowCommands.ScheduleActivity
                .newBuilder()
                .setSeq(seq)
                .setActivityId(activityId)
                .setActivityType(activityType)
                .setTaskQueue(options.taskQueue ?: info.taskQueue)
                .addAllArguments(codec.safeEncode(args).proto.payloadsList)

        // Set optional timeouts
        options.startToCloseTimeout?.let {
            scheduleActivityBuilder.setStartToCloseTimeout(it.toProtoDuration())
        }
        options.scheduleToCloseTimeout?.let {
            scheduleActivityBuilder.setScheduleToCloseTimeout(it.toProtoDuration())
        }
        options.scheduleToStartTimeout?.let {
            scheduleActivityBuilder.setScheduleToStartTimeout(it.toProtoDuration())
        }
        options.heartbeatTimeout?.let {
            scheduleActivityBuilder.setHeartbeatTimeout(it.toProtoDuration())
        }

        // Set retry policy if provided
        options.retryPolicy?.let { policy ->
            scheduleActivityBuilder.setRetryPolicy(policy.toProto())
        }

        // Set enum fields using inline converters
        scheduleActivityBuilder.setCancellationType(options.cancellationType.toProto())
        scheduleActivityBuilder.setVersioningIntent(options.versioningIntent.toProto())

        // Merge headers: options.headers first, then interceptor-added headers
        // (interceptor entries win on key collision)
        val headers =
            buildMap {
                options.headers?.let { putAll(it) }
                putAll(input.headers)
            }
        if (headers.isNotEmpty()) {
            scheduleActivityBuilder.putAllHeaders(headers.mapValues { (_, v) -> v.toProto() })
        }

        // Set eager execution flag
        scheduleActivityBuilder.setDoNotEagerlyExecute(options.disableEagerExecution)

        // Set priority field - passthrough of the priority key. Temporal semantics:
        // lower key = higher priority, 0 = unset (server default). Ignored by servers
        // without priority support.
        scheduleActivityBuilder.setPriority(
            io.temporal.api.common.v1.Priority
                .newBuilder()
                .setPriorityKey(options.priority)
                .build(),
        )

        val commandBuilder =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setScheduleActivity(scheduleActivityBuilder)

        options.summary?.let { summary ->
            commandBuilder.setUserMetadata(buildUserMetadata(summary))
        }

        state.addCommand(commandBuilder.build())

        logger.info(
            "Scheduled activity: type=$activityType, id=$activityId, seq=$seq, " +
                "taskQueue=${options.taskQueue ?: info.taskQueue}",
        )

        // ========== Section 4: Handle Creation & Registration ==========

        val handle =
            RemoteActivityHandleImpl(
                activityId = activityId,
                seq = seq,
                activityType = activityType,
                state = state,
                serializer = serializer,
                codec = codec,
                cancellationType = options.cancellationType,
            )

        state.registerActivity(seq, handle)

        logger.fine("Activity handle created and registered: id=$activityId, seq=$seq")

        // ========== Section 5: Return ==========

        return handle
    }

    /**
     * Starts a local activity execution and returns a handle for managing it.
     *
     * Local activities run in the same worker process as the workflow, avoiding
     * the roundtrip to the Temporal server. They're useful for short operations
     * that don't need server-side scheduling, retry management, or persistence.
     *
     * @throws IllegalArgumentException if validation fails (invalid timeouts, etc.)
     * @throws ReadOnlyContextException if called during query processing
     */
    override suspend fun startLocalActivityWithPayloads(
        activityType: String,
        args: TemporalPayloads,
        options: LocalActivityOptions,
    ): LocalActivityHandle {
        ensureOnWorkflowDispatcher("startLocalActivity")
        logger.fine("Starting local activity: type=$activityType, options=$options")

        // Execute through the interceptor chain
        val interceptorInput =
            ScheduleLocalActivityInput(
                activityType = activityType,
                args = args,
                options = options,
            )
        val chain = hookRegistry.chain(ScheduleLocalActivity)
        return chain.execute(interceptorInput) { input ->
            startLocalActivityInternal(input)
        }
    }

    private suspend fun startLocalActivityInternal(input: ScheduleLocalActivityInput): LocalActivityHandle {
        val activityType = input.activityType
        val args = input.args
        val options = input.options
        // ========== Section 1: Validation ==========

        // Activity type validation
        require(activityType.isNotBlank()) {
            "activityType must not be blank"
        }

        // Timeout requirements - at least one required (validated in LocalActivityOptions init)

        logger.fine("Local activity validation passed: type=$activityType")

        // ========== Section 2: Sequence & ID Generation ==========

        val seq = state.nextSeq()
        val activityId = options.activityId ?: "$seq"

        logger.fine("Generated local activity identifiers: type=$activityType, id=$activityId, seq=$seq")

        // ========== Section 3: Command Building ==========

        logger.fine("Building ScheduleLocalActivity command: type=$activityType, id=$activityId")

        // Encode once - the same payloads are reused for backoff reschedules via the handle
        val encodedArgs = codec.safeEncode(args).proto.payloadsList

        val scheduleLocalActivityBuilder =
            WorkflowCommands.ScheduleLocalActivity
                .newBuilder()
                .setSeq(seq)
                .setActivityId(activityId)
                .setActivityType(activityType)
                .setAttempt(1) // Initial attempt is 1
                .addAllArguments(encodedArgs)

        // Set optional timeouts
        options.startToCloseTimeout?.let {
            scheduleLocalActivityBuilder.setStartToCloseTimeout(it.toProtoDuration())
        }
        options.scheduleToCloseTimeout?.let {
            scheduleLocalActivityBuilder.setScheduleToCloseTimeout(it.toProtoDuration())
        }
        options.scheduleToStartTimeout?.let {
            scheduleLocalActivityBuilder.setScheduleToStartTimeout(it.toProtoDuration())
        }

        // Set local retry threshold
        scheduleLocalActivityBuilder.setLocalRetryThreshold(options.localRetryThreshold.toProtoDuration())

        // Set retry policy if provided
        options.retryPolicy?.let { policy ->
            scheduleLocalActivityBuilder.setRetryPolicy(policy.toProto())
        }

        // Set cancellation type - per proto comment, default to WAIT_CANCELLATION_COMPLETED
        scheduleLocalActivityBuilder.setCancellationType(options.cancellationType.toProto())

        // Set headers from interceptor input (may be modified by interceptors)
        val protoHeaders = input.headers.mapValues { (_, v) -> v.toProto() }
        if (protoHeaders.isNotEmpty()) {
            scheduleLocalActivityBuilder.putAllHeaders(protoHeaders)
        }

        val userMetadata = options.summary?.let { buildUserMetadata(it) }

        val commandBuilder =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setScheduleLocalActivity(scheduleLocalActivityBuilder)

        userMetadata?.let { commandBuilder.setUserMetadata(it) }

        state.addCommand(commandBuilder.build())

        logger.info(
            "Scheduled local activity: type=$activityType, id=$activityId, seq=$seq",
        )

        // ========== Section 4: Handle Creation & Registration ==========

        val handle =
            LocalActivityHandleImpl(
                activityId = activityId,
                initialSeq = seq,
                activityType = activityType,
                state = state,
                context = this,
                serializer = serializer,
                options = options,
                cancellationType = options.cancellationType,
                arguments = encodedArgs,
                headers = protoHeaders,
                userMetadata = userMetadata,
            )

        state.registerLocalActivity(seq, handle)

        logger.fine("Local activity handle created and registered: id=$activityId, seq=$seq")

        // ========== Section 5: Return ==========

        return handle
    }

    /**
     * Builds the per-command user metadata carrying a UI-facing summary.
     * See [buildUserMetadata] in ProtoConversions.kt.
     */
    private fun buildUserMetadata(summary: String): io.temporal.api.sdk.v1.UserMetadata =
        buildUserMetadata(serializer, summary)

    override suspend fun sleep(
        duration: Duration,
        summary: String?,
    ) {
        ensureOnWorkflowDispatcher("sleep")
        if (duration.isNegative() || duration == Duration.ZERO) {
            // No-op for zero or negative duration
            return
        }

        // Execute through the interceptor chain
        val interceptorInput = SleepInput(duration = duration, summary = summary)
        val chain = hookRegistry.chain(Sleep)
        chain.execute(interceptorInput) { input ->
            sleepInternal(input.duration, input.summary)
        }
    }

    private suspend fun sleepInternal(
        duration: Duration,
        summary: String?,
    ) {
        val seq = state.nextSeq()

        // Build the StartTimer command using Java builder API
        val commandBuilder =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setStartTimer(
                    WorkflowCommands.StartTimer
                        .newBuilder()
                        .setSeq(seq)
                        .setStartToFireTimeout(duration.toProtoDuration()),
                )

        summary?.let { commandBuilder.setUserMetadata(buildUserMetadata(it)) }

        state.addCommand(commandBuilder.build())

        // Register pending timer and await
        val deferred = state.registerTimer(seq)
        try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The sleeping coroutine was cancelled: tell core to cancel the server-side
            // timer, unless it already fired or the workflow is tearing down (terminal
            // completion / eviction must not emit commands - replay compatibility).
            if (!state.workflowCompleted && state.removeTimer(seq)) {
                state.addCommand(
                    WorkflowCommands.WorkflowCommand
                        .newBuilder()
                        .setCancelTimer(
                            WorkflowCommands.CancelTimer
                                .newBuilder()
                                .setSeq(seq),
                        ).build(),
                )
            }
            throw e
        }
    }

    override suspend fun awaitCondition(condition: () -> Boolean) {
        awaitConditionInternal(condition, timeout = null, timeoutSummary = null)
    }

    override suspend fun awaitCondition(
        timeout: Duration,
        timeoutSummary: String?,
        condition: () -> Boolean,
    ) {
        awaitConditionInternal(condition, timeout, timeoutSummary)
    }

    /**
     * Internal implementation for awaiting a condition with optional timeout.
     *
     * @param condition The condition to wait for
     * @param timeout Optional timeout duration; null means wait indefinitely
     * @param timeoutSummary Optional description for debugging
     */
    private suspend fun awaitConditionInternal(
        condition: () -> Boolean,
        timeout: Duration?,
        timeoutSummary: String?,
    ) {
        ensureOnWorkflowDispatcher("awaitCondition")
        // Check the condition immediately - if already true, no need to wait
        if (condition()) {
            return
        }

        // Register the condition with the workflow state
        // The condition will be checked deterministically after signals/updates and non-query jobs
        val deferred = state.registerCondition(condition)

        if (timeout == null) {
            // No timeout - simple await
            deferred.await()
        } else {
            try {
                // Use coroutine withTimeout - it uses delay() which is intercepted
                // by WorkflowTimerScheduler to create durable timers.
                // withTimeout schedules its timeout timer synchronously in this call
                // stack, so the summary handoff below reaches exactly that timer.
                timeoutSummary?.let { state.pendingTimerSummary = it }
                try {
                    kotlinx.coroutines.withTimeout(timeout) {
                        deferred.await()
                    }
                } finally {
                    // Defensive: never leak a stale summary to an unrelated timer
                    state.pendingTimerSummary = null
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Clean up the condition from registry
                state.removeCondition(deferred)
                // Rethrow as custom exception with context
                throw WorkflowConditionTimeoutException(
                    message = timeoutSummary ?: "Condition wait timed out after $timeout",
                    timeout = timeout,
                    summary = timeoutSummary,
                    cause = e,
                )
            }
        }
    }

    // Full activation-timestamp precision (nanoseconds), matching Python's time_ns
    override fun now(): Instant = state.currentTime

    override fun randomUuid(): String = deterministicRandom.randomUuid()

    override fun patched(patchId: String): Boolean {
        // Check memoized result first (ensures determinism within execution)
        state.getPatchMemo(patchId)?.let { return it }

        // Core logic: true if not replaying OR if patch was notified
        val usePatch = !isReplaying || state.isPatchNotified(patchId)

        // Memoize the result
        state.setPatchMemo(patchId, usePatch)

        // Send command only if using the patch
        if (usePatch) {
            state.addCommand(createSetPatchMarkerCommand(patchId))
        }

        return usePatch
    }

    override fun deprecatePatch(patchId: String) {
        // Idempotent within an execution: record the marker only once
        if (state.getPatchMemo(patchId) != null) {
            return
        }
        state.setPatchMemo(patchId, true)
        state.addCommand(createSetPatchMarkerCommand(patchId, deprecated = true))
    }

    override suspend fun startChildWorkflowWithPayloads(
        workflowType: String,
        args: TemporalPayloads,
        options: ChildWorkflowOptions,
    ): ChildWorkflowHandle {
        ensureOnWorkflowDispatcher("startChildWorkflow")

        // Execute through the interceptor chain
        val interceptorInput =
            StartChildWorkflowInput(
                workflowType = workflowType,
                args = args,
                options = options,
            )
        val chain = hookRegistry.chain(StartChildWorkflow)
        return chain.execute(interceptorInput) { input ->
            startChildWorkflowInternal(input)
        }
    }

    private suspend fun startChildWorkflowInternal(input: StartChildWorkflowInput): ChildWorkflowHandle {
        val workflowType = input.workflowType
        val args = input.args
        val options = input.options
        val seq = state.nextSeq()
        val childWorkflowId = options.workflowId ?: "${info.workflowId}-child-$seq"

        // Build the StartChildWorkflowExecution command
        val commandBuilder =
            WorkflowCommands.StartChildWorkflowExecution
                .newBuilder()
                .setSeq(seq)
                .setNamespace(info.namespace)
                .setWorkflowId(childWorkflowId)
                .setWorkflowType(workflowType)
                .setTaskQueue(options.taskQueue ?: info.taskQueue)
                .addAllInput(codec.safeEncode(args).proto.payloadsList)
                .setParentClosePolicy(options.parentClosePolicy.toProto())
                .setCancellationType(options.cancellationType.toProto())

        // Set optional timeouts
        options.workflowExecutionTimeout?.let {
            commandBuilder.setWorkflowExecutionTimeout(it.toProtoDuration())
        }
        options.workflowRunTimeout?.let {
            commandBuilder.setWorkflowRunTimeout(it.toProtoDuration())
        }

        // Set retry policy if provided
        options.retryPolicy?.let { policy ->
            commandBuilder.setRetryPolicy(policy.toProto())
        }

        // Set workflow ID reuse policy if provided
        options.workflowIdReusePolicy?.let {
            commandBuilder.setWorkflowIdReusePolicy(it.toProto())
        }

        // Set workflow task timeout if provided
        options.workflowTaskTimeout?.let {
            commandBuilder.setWorkflowTaskTimeout(it.toProtoDuration())
        }

        // Set cron schedule if provided
        options.cronSchedule?.takeIf { it.isNotBlank() }?.let {
            commandBuilder.setCronSchedule(it)
        }

        // Set memo if provided
        options.memo?.takeIf { it.isNotEmpty() }?.let { memo ->
            commandBuilder.putAllMemo(memo.mapValues { (_, v) -> v.toProto() })
        }

        // Versioning intent and priority (see ActivityOptions.priority for key semantics)
        commandBuilder.setVersioningIntent(options.versioningIntent.toProto())
        commandBuilder.setPriority(
            io.temporal.api.common.v1.Priority
                .newBuilder()
                .setPriorityKey(options.priority)
                .build(),
        )

        // Set search attributes if provided
        options.searchAttributes?.let { attrs ->
            if (attrs.isNotEmpty()) {
                val encoded = SearchAttributeEncoder.encode(attrs)
                commandBuilder.setSearchAttributes(
                    SearchAttributes.newBuilder().putAllIndexedFields(encoded).build(),
                )
            }
        }

        // Set headers from interceptor input (may be modified by interceptors)
        if (input.headers.isNotEmpty()) {
            commandBuilder.putAllHeaders(input.headers.mapValues { (_, v) -> v.toProto() })
        }

        val childCommandBuilder =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setStartChildWorkflowExecution(commandBuilder)

        options.summary?.let { summary ->
            childCommandBuilder.setUserMetadata(buildUserMetadata(summary))
        }

        state.addCommand(childCommandBuilder.build())

        // Create and register the handle
        val handle =
            ChildWorkflowHandleImpl(
                workflowId = childWorkflowId,
                seq = seq,
                workflowType = workflowType,
                state = state,
                serializer = serializer,
                codec = codec,
                cancellationType = options.cancellationType,
                hookRegistry = hookRegistry,
            )

        state.registerChildWorkflow(seq, handle)

        return handle
    }

    override fun setQueryHandlerWithPayloads(
        name: String,
        handler: (suspend (TemporalPayloads) -> TemporalPayload)?,
    ) {
        if (handler == null) {
            runtimeQueryHandlers.remove(name)
        } else {
            runtimeQueryHandlers[name] = handler
        }
    }

    override fun setDynamicQueryHandlerWithPayloads(
        handler: (
            suspend (
                queryType: String,
                args: TemporalPayloads,
            ) -> TemporalPayload
        )?,
    ) {
        runtimeDynamicQueryHandler = handler
    }

    override fun setSignalHandlerWithPayloads(
        name: String,
        handler: (suspend (TemporalPayloads) -> Unit)?,
    ) {
        if (handler == null) {
            runtimeSignalHandlers.remove(name)
        } else {
            runtimeSignalHandlers[name] = handler
            // Immediately launch tasks for any buffered signals.
            // Replayed buffered signals must behave exactly like live signal deliveries:
            // run on the handler job (surviving workflow completion) and swallow-and-log
            // exceptions instead of failing the workflow. Matches Python, which replays
            // buffered signals through the same processing path as live ones.
            bufferedSignals.remove(name)?.let { signals ->
                for (payloads in signals) {
                    launchHandler {
                        try {
                            handler(payloads)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warning("Buffered signal handler threw exception: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    override fun setDynamicSignalHandlerWithPayloads(
        handler: (
            suspend (signalName: String, args: TemporalPayloads) -> Unit
        )?,
    ) {
        runtimeDynamicSignalHandler = handler
        if (handler != null) {
            // Immediately launch tasks for all buffered signals - same semantics as
            // live signal deliveries (handler job + swallow-and-log), see above
            for ((signalName, signals) in bufferedSignals) {
                for (payloads in signals) {
                    launchHandler {
                        try {
                            handler(signalName, payloads)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warning("Buffered dynamic signal handler threw exception: ${e.message}")
                        }
                    }
                }
            }
            bufferedSignals.clear()
        }
    }

    override fun setUpdateHandlerWithPayloads(
        name: String,
        handler: (suspend (TemporalPayloads) -> TemporalPayload)?,
        validator: ((TemporalPayloads) -> Unit)?,
    ) {
        if (handler == null) {
            runtimeUpdateHandlers.remove(name)
        } else {
            runtimeUpdateHandlers[name] = UpdateHandlerEntry(handler, validator)
        }
    }

    override fun setDynamicUpdateHandlerWithPayloads(
        handler: (suspend (updateName: String, args: TemporalPayloads) -> TemporalPayload)?,
        validator: ((updateName: String, args: TemporalPayloads) -> Unit)?,
    ) {
        runtimeDynamicUpdateHandler =
            if (handler != null) {
                DynamicUpdateHandlerEntry(handler, validator)
            } else {
                null
            }
    }

    override suspend fun upsertSearchAttributes(attributes: TypedSearchAttributes) {
        ensureOnWorkflowDispatcher("upsertSearchAttributes")

        if (attributes.isEmpty()) {
            return // No-op for empty attributes
        }

        // Encode using existing SearchAttributeEncoder
        val encoded = SearchAttributeEncoder.encode(attributes)

        // Build the command
        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setUpsertWorkflowSearchAttributes(
                    WorkflowCommands.UpsertWorkflowSearchAttributes
                        .newBuilder()
                        .setSearchAttributes(
                            SearchAttributes.newBuilder().putAllIndexedFields(encoded).build(),
                        ),
                ).build()

        state.addCommand(command)

        logger.info("Upserted ${attributes.size} search attributes")
    }

    @InternalTemporalApi
    override suspend fun continueAsNewInternal(
        options: ContinueAsNewOptions,
        typedArgs: List<Pair<KType, Any?>>,
    ): Nothing {
        ensureOnWorkflowDispatcher("continueAsNew")

        // Serialize typed args to TemporalPayloads
        val serializedArgs =
            if (typedArgs.isEmpty()) {
                TemporalPayloads.EMPTY
            } else {
                val payloads =
                    typedArgs.map { (type, value) ->
                        serializer.safeSerialize(type, value)
                    }
                TemporalPayloads.of(payloads)
            }

        // Execute through the interceptor chain
        val interceptorInput =
            ContinueAsNewInput(
                options = options,
                args = serializedArgs,
            )
        val chain = hookRegistry.chain(ContinueAsNew)
        chain.execute(interceptorInput) { input ->
            throw ContinueAsNewException(
                options = input.options,
                typedArgs = typedArgs,
                serializedArgs = input.args,
            )
        }
    }

    override fun getExternalWorkflowHandle(
        workflowId: String,
        runId: String?,
    ): com.surrealdev.temporal.workflow.ExternalWorkflowHandle =
        ExternalWorkflowHandleImpl(
            workflowId = workflowId,
            runId = runId,
            namespace = info.namespace,
            state = state,
            serializer = serializer,
            codec = codec,
            hookRegistry = hookRegistry,
        )

    /**
     * Gets the current replaying state.
     */
    override val isReplaying: Boolean
        get() = state.isReplaying
}

/**
 * Entry for a runtime-registered update handler.
 */
internal data class UpdateHandlerEntry(
    val handler: suspend (TemporalPayloads) -> TemporalPayload,
    val validator: ((TemporalPayloads) -> Unit)?,
)

/**
 * Entry for a runtime-registered dynamic update handler.
 */
internal data class DynamicUpdateHandlerEntry(
    val handler: suspend (updateName: String, args: TemporalPayloads) -> TemporalPayload,
    val validator: ((updateName: String, args: TemporalPayloads) -> Unit)?,
)

/**
 * Converts our domain [ParentClosePolicy] to the protobuf enum.
 */
private fun ParentClosePolicy.toProto(): ChildWorkflow.ParentClosePolicy =
    when (this) {
        ParentClosePolicy.TERMINATE -> ChildWorkflow.ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE
        ParentClosePolicy.ABANDON -> ChildWorkflow.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON
        ParentClosePolicy.REQUEST_CANCEL -> ChildWorkflow.ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL
    }

/**
 * Converts our domain [ChildWorkflowCancellationType] to the protobuf enum.
 */
private fun ChildWorkflowCancellationType.toProto(): ChildWorkflow.ChildWorkflowCancellationType =
    when (this) {
        ChildWorkflowCancellationType.ABANDON -> {
            ChildWorkflow.ChildWorkflowCancellationType.ABANDON
        }

        ChildWorkflowCancellationType.TRY_CANCEL -> {
            ChildWorkflow.ChildWorkflowCancellationType.TRY_CANCEL
        }

        ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED -> {
            ChildWorkflow.ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED
        }

        ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED -> {
            ChildWorkflow.ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED
        }
    }

/**
 * Converts domain [ActivityCancellationType] to protobuf enum.
 *
 * Mapping:
 * - TRY_CANCEL → TRY_CANCEL: Request cancellation, don't wait
 * - WAIT_CANCELLATION_COMPLETED → WAIT_CANCELLATION_COMPLETED: Wait for activity to acknowledge
 * - ABANDON → ABANDON: Immediately abandon without cancellation request
 */
private fun ActivityCancellationType.toProto(): WorkflowCommands.ActivityCancellationType =
    when (this) {
        ActivityCancellationType.TRY_CANCEL -> {
            WorkflowCommands.ActivityCancellationType.TRY_CANCEL
        }

        ActivityCancellationType.WAIT_CANCELLATION_COMPLETED -> {
            WorkflowCommands.ActivityCancellationType.WAIT_CANCELLATION_COMPLETED
        }

        ActivityCancellationType.ABANDON -> {
            WorkflowCommands.ActivityCancellationType.ABANDON
        }
    }

/**
 * Converts domain [VersioningIntent] to protobuf enum.
 *
 * Mapping:
 * - UNSPECIFIED → UNSPECIFIED: Use server default behavior
 * - DEFAULT → DEFAULT: Use default version from task queue
 * - COMPATIBLE → COMPATIBLE: Use version compatible with current workflow
 */
private fun VersioningIntent.toProto(): coresdk.common.Common.VersioningIntent =
    when (this) {
        VersioningIntent.UNSPECIFIED -> {
            coresdk.common.Common.VersioningIntent.UNSPECIFIED
        }

        VersioningIntent.DEFAULT -> {
            coresdk.common.Common.VersioningIntent.DEFAULT
        }

        VersioningIntent.COMPATIBLE -> {
            coresdk.common.Common.VersioningIntent.COMPATIBLE
        }
    }

/**
 * Converts domain [RetryPolicy] to protobuf message.
 *
 * Used for both activity and child workflow retry policies.
 *
 * Converts all fields:
 * - initialInterval: First retry delay
 * - backoffCoefficient: Exponential backoff multiplier (must be > 1.0)
 * - maximumAttempts: Max retry count (0 = unlimited)
 * - maximumInterval: Cap on retry delay (optional)
 * - nonRetryableErrorTypes: Error types that should not be retried
 */
private fun RetryPolicy.toProto(): io.temporal.api.common.v1.RetryPolicy {
    val retryPolicyBuilder =
        io.temporal.api.common.v1.RetryPolicy
            .newBuilder()
            .setInitialInterval(initialInterval.toProtoDuration())
            .setBackoffCoefficient(backoffCoefficient)
            .setMaximumAttempts(maximumAttempts)

    maximumInterval?.let {
        retryPolicyBuilder.setMaximumInterval(it.toProtoDuration())
    }

    if (nonRetryableErrorTypes.isNotEmpty()) {
        retryPolicyBuilder.addAllNonRetryableErrorTypes(nonRetryableErrorTypes)
    }

    return retryPolicyBuilder.build()
}

/**
 * Coroutine context element that signals to skip dispatcher validation.
 *
 * Used for unit testing workflow context operations without a full workflow environment.
 * When present in the coroutine context, [WorkflowContextImpl.ensureOnWorkflowDispatcher]
 * will not throw [EscapedDispatcherException].
 *
 * Usage:
 * ```kotlin
 * withContext(SkipDispatcherCheck) {
 *     // workflow context operations work without dispatcher validation
 * }
 * ```
 */
@InternalTemporalApi
object SkipDispatcherCheck : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key

    object Key : CoroutineContext.Key<SkipDispatcherCheck>
}
