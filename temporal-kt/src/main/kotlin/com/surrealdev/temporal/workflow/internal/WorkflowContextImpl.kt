package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.typeInfoOf
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.WorkflowInfo
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * Implementation of [WorkflowContext] for workflow execution.
 *
 * This context provides deterministic operations within a workflow:
 * - Timer scheduling (via [sleep])
 * - Activity scheduling (via [scheduleActivityDirect])
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
    private val mdcContext: MDCContext? = null,
) : WorkflowContext {
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

    override val coroutineContext: CoroutineContext =
        if (mdcContext != null) job + workflowDispatcher + mdcContext else job + workflowDispatcher

    /**
     * Updates the random seed (called when UpdateRandomSeed job is received).
     */
    internal fun updateRandomSeed(newSeed: Long) {
        deterministicRandom.updateSeed(newSeed)
        state.randomSeed = newSeed
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> activity(
        activityType: String,
        options: ActivityOptions,
    ): T {
        // For MVP, return a stub that throws when methods are called
        // TODO: Implement proper activity proxy with dynamic proxy or code generation
        throw UnsupportedOperationException(
            "Activity proxy not yet implemented. Use scheduleActivityDirect instead.",
        )
    }

    /**
     * Directly schedules an activity and waits for its result.
     *
     * This is a simpler approach than the proxy-based activity() method
     * and is suitable for the MVP.
     *
     * @param activityType The activity type name (e.g., "GreeterActivity::greet")
     * @param args Arguments to pass to the activity
     * @param options Activity execution options
     * @param returnType The expected return type for deserialization
     * @return The activity result
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <R> scheduleActivityDirect(
        activityType: String,
        args: List<Any?>,
        argTypes: List<KType>,
        options: ActivityOptions,
        returnType: KType,
    ): R {
        val seq = state.nextSeq()
        val activityId = "$seq"

        // Serialize arguments
        val argumentPayloads =
            args.zip(argTypes).map { (arg, type) ->
                serializer.serialize(typeInfoOf(type), arg)
            }

        // Build the ScheduleActivity command using Java builder API
        val scheduleActivityBuilder =
            WorkflowCommands.ScheduleActivity
                .newBuilder()
                .setSeq(seq)
                .setActivityId(activityId)
                .setActivityType(activityType)
                .setTaskQueue(options.taskQueue ?: info.taskQueue)
                .addAllArguments(argumentPayloads)

        // Set timeouts
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

        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setScheduleActivity(scheduleActivityBuilder)
                .build()

        state.addCommand(command)

        // Register pending activity and await result
        val deferred = state.registerActivity(seq, returnType)
        val resultPayload = deferred.await()

        // Deserialize result
        return if (resultPayload == null || resultPayload == Payload.getDefaultInstance()) {
            if (returnType.classifier == Unit::class) {
                Unit as R
            } else {
                null as R
            }
        } else {
            serializer.deserialize(typeInfoOf(returnType), resultPayload) as R
        }
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

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> childWorkflow(
        workflowType: String,
        options: ChildWorkflowOptions,
    ): T {
        // Not implemented in MVP
        throw UnsupportedOperationException(
            "Child workflows not yet implemented",
        )
    }

    override fun setQueryHandler(
        name: String,
        handler: (suspend (List<Payload>) -> Payload)?,
    ) {
        if (handler == null) {
            runtimeQueryHandlers.remove(name)
        } else {
            runtimeQueryHandlers[name] = handler
        }
    }

    override fun setDynamicQueryHandler(
        handler: (
            suspend (
                queryType: String,
                args: List<Payload>,
            ) -> Payload
        )?,
    ) {
        runtimeDynamicQueryHandler = handler
    }

    override fun setSignalHandler(
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

    override fun setDynamicSignalHandler(handler: (suspend (signalName: String, args: List<Payload>) -> Unit)?) {
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

    override fun setUpdateHandler(
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

    override fun setDynamicUpdateHandler(
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
