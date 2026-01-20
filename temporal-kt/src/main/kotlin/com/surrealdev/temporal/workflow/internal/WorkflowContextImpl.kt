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
import kotlinx.coroutines.delay
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Implementation of [WorkflowContext] for workflow execution.
 *
 * This context provides deterministic operations within a workflow:
 * - Timer scheduling (via [sleep])
 * - Activity scheduling (via [scheduleActivity])
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
    private val serializer: PayloadSerializer,
    private val workflowDispatcher: WorkflowCoroutineDispatcher,
    parentJob: Job,
) : WorkflowContext {
    // Create a child job for this workflow - failures propagate to parent
    private val job = Job(parentJob)
    private val deterministicRandom = DeterministicRandom(state.randomSeed)

    override val coroutineContext: CoroutineContext = job + workflowDispatcher

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
        // MVP: Simple busy wait with short delays
        // TODO: Implement proper condition signaling
        while (!condition()) {
            // Use a very short delay to yield to other coroutines
            delay(1)
        }
    }

    override fun now(): kotlinx.datetime.Instant {
        // Convert from kotlin.time.Instant to kotlinx.datetime.Instant
        return kotlinx.datetime.Instant.fromEpochMilliseconds(
            state.currentTime.toEpochMilliseconds(),
        )
    }

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

    /**
     * Gets the current replaying state.
     */
    val isReplaying: Boolean
        get() = state.isReplaying
}

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
