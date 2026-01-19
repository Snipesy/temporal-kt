package com.surrealdev.temporal.workflow.internal

import coresdk.activity_result.ActivityResult
import coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Manages the state of a single workflow execution.
 *
 * This class tracks:
 * - Sequence numbers for deterministic command ordering
 * - Pending operations (timers, activities) that are waiting for resolution
 * - Commands to send back to the Temporal server
 * - Workflow time and replay state
 *
 * Thread safety: This class is designed for single-threaded access within a workflow.
 * Multiple workflow runs can exist concurrently, but each run is processed sequentially.
 */
internal class WorkflowState(
    val runId: String,
) {
    /**
     * Sequence number counter for deterministic operation ordering.
     * Incremented for each timer, activity, child workflow, etc.
     */
    private var nextSeq = 1

    /**
     * Generates the next sequence number.
     */
    fun nextSeq(): Int = nextSeq++

    /**
     * Current workflow time as provided by the activation.
     * This is deterministic and survives replay.
     */
    var currentTime: Instant = Instant.fromEpochMilliseconds(0)
        private set

    /**
     * Whether the workflow is currently replaying past events.
     * When replaying, side effects should not be executed.
     */
    var isReplaying: Boolean = false
        private set

    /**
     * Random seed for deterministic random number generation.
     * Updated when the activation contains an UpdateRandomSeed job.
     */
    var randomSeed: Long = 0
        internal set

    /**
     * History length as of the current activation.
     */
    var historyLength: Int = 0
        private set

    /**
     * Pending timer operations, keyed by sequence number.
     */
    private val pendingTimers = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    /**
     * Pending activity operations, keyed by sequence number.
     */
    private val pendingActivities = ConcurrentHashMap<Int, PendingActivity>()

    /**
     * Commands accumulated during this activation.
     * Drained and sent back to the server at the end of activation processing.
     */
    private val commands = mutableListOf<WorkflowCommand>()

    /**
     * Updates state from the activation's metadata.
     */
    fun updateFromActivation(
        timestamp: com.google.protobuf.Timestamp?,
        isReplaying: Boolean,
        historyLength: Int,
    ) {
        if (timestamp != null) {
            val javaInstant = java.time.Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())
            this.currentTime = javaInstant.toKotlinInstant()
        }
        this.isReplaying = isReplaying
        this.historyLength = historyLength
    }

    /**
     * Registers a pending timer and returns a deferred to await its completion.
     */
    fun registerTimer(seq: Int): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        pendingTimers[seq] = deferred
        return deferred
    }

    /**
     * Resolves a timer by its sequence number.
     * Called when a FireTimer job is received in an activation.
     */
    fun resolveTimer(seq: Int) {
        pendingTimers.remove(seq)?.complete(Unit)
    }

    /**
     * Registers a pending activity and returns a deferred to await its completion.
     */
    fun registerActivity(
        seq: Int,
        returnType: KType,
    ): CompletableDeferred<Payload?> {
        val deferred = CompletableDeferred<Payload?>()
        pendingActivities[seq] = PendingActivity(deferred, returnType)
        return deferred
    }

    /**
     * Resolves an activity by its sequence number.
     * Called when a ResolveActivity job is received in an activation.
     */
    fun resolveActivity(
        seq: Int,
        result: ActivityResult.ActivityResolution,
    ) {
        val pending = pendingActivities.remove(seq) ?: return

        when {
            result.hasCompleted() -> {
                val payload = result.completed.result
                pending.deferred.complete(payload)
            }
            result.hasFailed() -> {
                val failure = result.failed.failure
                pending.deferred.completeExceptionally(
                    ActivityFailureException(
                        message = failure.message,
                        activityType = "", // TODO: Get from pending info
                        cause = failure.cause?.let { ActivityFailureException(it.message) },
                    ),
                )
            }
            result.hasCancelled() -> {
                pending.deferred.completeExceptionally(
                    ActivityCancelledException("Activity was cancelled"),
                )
            }
            result.hasBackoff() -> {
                // For backoff, we don't resolve - the activity will be retried
                // Re-register the pending activity
                pendingActivities[seq] = pending
            }
        }
    }

    /**
     * Adds a command to be sent at the end of this activation.
     */
    fun addCommand(cmd: WorkflowCommand) {
        commands.add(cmd)
    }

    /**
     * Drains all accumulated commands and clears the list.
     */
    fun drainCommands(): List<WorkflowCommand> {
        val result = commands.toList()
        commands.clear()
        return result
    }

    /**
     * Checks if there are any pending commands.
     */
    fun hasCommands(): Boolean = commands.isNotEmpty()

    /**
     * Clears all pending operations.
     * Called on workflow eviction or completion.
     */
    fun clear() {
        // Cancel all pending timers
        pendingTimers.values.forEach { it.cancel() }
        pendingTimers.clear()

        // Cancel all pending activities
        pendingActivities.values.forEach { it.deferred.cancel() }
        pendingActivities.clear()

        commands.clear()
    }
}

/**
 * Holds a pending activity's deferred and its return type for deserialization.
 */
internal data class PendingActivity(
    val deferred: CompletableDeferred<Payload?>,
    val returnType: KType,
)

/**
 * Exception thrown when an activity fails.
 */
class ActivityFailureException(
    message: String?,
    val activityType: String = "",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception thrown when an activity is cancelled.
 */
class ActivityCancelledException(
    message: String = "Activity was cancelled",
) : RuntimeException(message)
