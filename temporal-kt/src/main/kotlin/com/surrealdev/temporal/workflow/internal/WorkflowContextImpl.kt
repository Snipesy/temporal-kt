package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.ExecutionScope
import com.surrealdev.temporal.workflow.ActivityCancellationType
import com.surrealdev.temporal.workflow.ActivityHandle
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.ChildWorkflowCancellationType
import com.surrealdev.temporal.workflow.ChildWorkflowHandle
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.ParentClosePolicy
import com.surrealdev.temporal.workflow.VersioningIntent
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.WorkflowInfo
import coresdk.child_workflow.ChildWorkflow
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.Payloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaDuration

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
    private val workflowDispatcher: WorkflowCoroutineDispatcher,
    parentJob: Job,
    override val parentScope: AttributeScope,
    private val mdcContext: MDCContext? = null,
) : WorkflowContext,
    ExecutionScope {
    companion object {
        private val logger = Logger.getLogger(WorkflowContextImpl::class.java.name)
    }

    // Workflow executions have their own attributes (currently empty, for future use)
    override val attributes: Attributes = Attributes(concurrent = false)
    override val isWorkflowContext: Boolean = true

    // Create a child job for this workflow - failures propagate to parent
    private val job = Job(parentJob)
    private val deterministicRandom = DeterministicRandom(state.randomSeed)

    /**
     * Runtime-registered named query handlers.
     * Keys are query names.
     */
    internal val runtimeQueryHandlers =
        mutableMapOf<String, (suspend (List<Payload>) -> Payload)>()

    /**
     * Runtime-registered dynamic query handler (catches all unhandled queries).
     */
    internal var runtimeDynamicQueryHandler:
        (
            suspend (
                queryType: String,
                args: List<Payload>,
            ) -> Payload
        )? = null

    /**
     * Runtime-registered named signal handlers.
     * Keys are signal names.
     */
    internal val runtimeSignalHandlers =
        mutableMapOf<String, suspend (List<Payload>) -> Unit>()

    /**
     * Runtime-registered dynamic signal handler (catches all unhandled signals).
     */
    internal var runtimeDynamicSignalHandler:
        (suspend (signalName: String, args: List<Payload>) -> Unit)? = null

    /**
     * Buffered signals waiting for handlers to be registered.
     * Keys are signal names, values are lists of signal inputs.
     */
    internal val bufferedSignals =
        mutableMapOf<String, MutableList<coresdk.workflow_activation.WorkflowActivationOuterClass.SignalWorkflow>>()

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
        get() =
            if (mdcContext !=
                null
            ) {
                job + workflowDispatcher + mdcContext + this
            } else {
                job + workflowDispatcher + this
            }

    /**
     * Updates the random seed (called when UpdateRandomSeed job is received).
     */
    internal fun updateRandomSeed(newSeed: Long) {
        deterministicRandom.updateSeed(newSeed)
        state.randomSeed = newSeed
    }

    /**
     * Starts an activity execution and returns a handle for managing it.
     *
     * @throws IllegalArgumentException if validation fails (invalid timeouts, priority, etc.)
     * @throws ReadOnlyContextException if called during query processing
     */
    override suspend fun <R> startActivityWithPayloads(
        activityType: String,
        args: Payloads,
        options: ActivityOptions,
        returnType: KType?,
    ): ActivityHandle<R> {
        logger.fine("Starting activity: type=$activityType, options=$options")

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

        // 6. Priority validation
        require(options.priority in 0..100) {
            "priority must be in range 0-100, got: ${options.priority}"
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
                .addAllArguments(args.payloadsList)

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

        // Set headers if provided
        options.headers?.let {
            scheduleActivityBuilder.putAllHeaders(it)
        }

        // Set eager execution flag
        scheduleActivityBuilder.setDoNotEagerlyExecute(options.disableEagerExecution)

        // Set priority field
        // Note: Priority support was added in Temporal Server 1.22.0 (May 2023).
        // On older servers, this field is ignored (no error). Priority key is 1-5 by default,
        // but our API uses 0-100 for future extensibility. The proto supports any int32 value.
        scheduleActivityBuilder.setPriority(
            io.temporal.api.common.v1.Priority
                .newBuilder()
                .setPriorityKey(options.priority)
                .build(),
        )

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setScheduleActivity(scheduleActivityBuilder)
                .build()

        state.addCommand(command)

        logger.info(
            "Scheduled activity: type=$activityType, id=$activityId, seq=$seq, " +
                "taskQueue=${options.taskQueue ?: info.taskQueue}",
        )

        // ========== Section 4: Handle Creation & Registration ==========

        val effectiveReturnType = returnType ?: typeOf<Any?>()

        val handle =
            ActivityHandleImpl<R>(
                activityId = activityId,
                seq = seq,
                activityType = activityType,
                state = state,
                serializer = serializer,
                returnType = effectiveReturnType,
                cancellationType = options.cancellationType,
            )

        state.registerActivity(seq, handle)

        logger.fine("Activity handle created and registered: id=$activityId, seq=$seq")

        // ========== Section 5: Return ==========

        return handle
    }

    override suspend fun sleep(duration: Duration) {
        if (duration.isNegative() || duration == Duration.ZERO) {
            // No-op for zero or negative duration
            return
        }

        val seq = state.nextSeq()

        // Build the StartTimer command using Java builder API
        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setStartTimer(
                    WorkflowCommands.StartTimer
                        .newBuilder()
                        .setSeq(seq)
                        .setStartToFireTimeout(duration.toProtoDuration()),
                ).build()

        state.addCommand(command)

        // Register pending timer and await
        val deferred = state.registerTimer(seq)
        deferred.await()
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
                // by WorkflowTimerScheduler to create durable timers
                kotlinx.coroutines.withTimeout(timeout) {
                    deferred.await()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Clean up the condition from registry
                state.removeCondition(deferred)
                // Rethrow as custom exception with context
                throw com.surrealdev.temporal.workflow.WorkflowConditionTimeoutException(
                    message = timeoutSummary ?: "Condition wait timed out after $timeout",
                    timeout = timeout,
                    summary = timeoutSummary,
                    cause = e,
                )
            }
        }
    }

    override fun now(): Instant =
        Instant.fromEpochMilliseconds(
            state.currentTime.toEpochMilliseconds(),
        )

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

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R : Any?> startChildWorkflowWithPayloads(
        workflowType: String,
        args: Payloads,
        options: ChildWorkflowOptions,
        returnType: KType?,
    ): ChildWorkflowHandle<R> {
        val effectiveReturnType = returnType ?: typeOf<Any?>()
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
                .addAllInput(args.payloadsList)
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

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setStartChildWorkflowExecution(commandBuilder)
                .build()

        state.addCommand(command)

        // Create and register the handle
        val handle =
            ChildWorkflowHandleImpl<R>(
                id = childWorkflowId,
                seq = seq,
                workflowType = workflowType,
                state = state,
                serializer = serializer,
                returnType = effectiveReturnType,
                cancellationType = options.cancellationType,
            )

        state.registerChildWorkflow(seq, handle)

        return handle
    }

    override fun setQueryHandlerWithPayloads(
        name: String,
        handler: (suspend (List<Payload>) -> Payload)?,
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
                args: List<Payload>,
            ) -> Payload
        )?,
    ) {
        runtimeDynamicQueryHandler = handler
    }

    override fun setSignalHandlerWithPayloads(
        name: String,
        handler: (suspend (List<Payload>) -> Unit)?,
    ) {
        if (handler == null) {
            runtimeSignalHandlers.remove(name)
        } else {
            runtimeSignalHandlers[name] = handler
            // Immediately launch tasks for any buffered signals
            // These tasks are queued to the WorkflowCoroutineDispatcher and will
            // execute during the next processAllWork() call, matching Python SDK behavior
            bufferedSignals.remove(name)?.let { signals ->
                for (signal in signals) {
                    launch { handler(signal.inputList) }
                }
            }
        }
    }

    override fun setDynamicSignalHandlerWithPayloads(
        handler: (
            suspend (signalName: String, args: List<Payload>) -> Unit
        )?,
    ) {
        runtimeDynamicSignalHandler = handler
        if (handler != null) {
            // Immediately launch tasks for all buffered signals
            // These tasks are queued to the WorkflowCoroutineDispatcher and will
            // execute during the next processAllWork() call, matching Python SDK behavior
            for ((signalName, signals) in bufferedSignals) {
                for (signal in signals) {
                    launch { handler(signalName, signal.inputList) }
                }
            }
            bufferedSignals.clear()
        }
    }

    override fun setUpdateHandlerWithPayloads(
        name: String,
        handler: (suspend (List<Payload>) -> Payload)?,
        validator: ((List<Payload>) -> Unit)?,
    ) {
        if (handler == null) {
            runtimeUpdateHandlers.remove(name)
        } else {
            runtimeUpdateHandlers[name] = UpdateHandlerEntry(handler, validator)
        }
    }

    override fun setDynamicUpdateHandlerWithPayloads(
        handler: (suspend (updateName: String, args: List<Payload>) -> Payload)?,
        validator: ((updateName: String, args: List<Payload>) -> Unit)?,
    ) {
        runtimeDynamicUpdateHandler =
            if (handler != null) {
                DynamicUpdateHandlerEntry(handler, validator)
            } else {
                null
            }
    }

    /**
     * Gets the current replaying state.
     */
    val isReplaying: Boolean
        get() = state.isReplaying
}

/**
 * Entry for a runtime-registered update handler.
 */
internal data class UpdateHandlerEntry(
    val handler: suspend (List<Payload>) -> Payload,
    val validator: ((List<Payload>) -> Unit)?,
)

/**
 * Entry for a runtime-registered dynamic update handler.
 */
internal data class DynamicUpdateHandlerEntry(
    val handler: suspend (updateName: String, args: List<Payload>) -> Payload,
    val validator: ((updateName: String, args: List<Payload>) -> Unit)?,
)

/**
 * Converts a Kotlin [Duration] to a protobuf [com.google.protobuf.Duration].
 */
private fun Duration.toProtoDuration(): com.google.protobuf.Duration {
    val javaDuration = this.toJavaDuration()
    return com.google.protobuf.Duration
        .newBuilder()
        .setSeconds(javaDuration.seconds)
        .setNanos(javaDuration.nano)
        .build()
}

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
 * Converts domain [RetryPolicy][com.surrealdev.temporal.workflow.RetryPolicy] to protobuf message.
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
private fun com.surrealdev.temporal.workflow.RetryPolicy.toProto(): io.temporal.api.common.v1.RetryPolicy {
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
