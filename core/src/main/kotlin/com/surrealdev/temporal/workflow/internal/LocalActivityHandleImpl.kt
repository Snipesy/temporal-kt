package com.surrealdev.temporal.workflow.internal

import com.google.protobuf.Timestamp
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.toTemporal
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.workflow.ActivityCancellationType
import com.surrealdev.temporal.workflow.ActivityCancelledException
import com.surrealdev.temporal.workflow.ActivityException
import com.surrealdev.temporal.workflow.ActivityFailureException
import com.surrealdev.temporal.workflow.ActivityTimeoutException
import com.surrealdev.temporal.workflow.LocalActivityHandle
import com.surrealdev.temporal.workflow.LocalActivityOptions
import coresdk.activity_result.ActivityResult
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * Internal implementation of LocalActivityHandle that manages the lifecycle of a local activity invocation.
 *
 * This class handles:
 * - Async result awaiting via CompletableDeferred
 * - Thread-safe cancellation with idempotency
 * - Raw payload results (deserialization happens via extension function)
 * - Exception mapping from proto failures to Kotlin exceptions
 * - **DoBackoff handling**: When Core SDK signals that backoff exceeds the threshold,
 *   this class orchestrates timer scheduling and activity rescheduling with a NEW sequence number.
 *
 * **Key Design (Exception-Based Backoff Flow):**
 * When a DoBackoff resolution is received, the current deferred is completed exceptionally with
 * a DoBackoffException. The resultPayload() method catches this exception, sleeps for the backoff duration,
 * and reschedules the activity. This pattern matches the Python SDK approach.
 *
 * Thread Safety:
 * - Designed for single workflow coroutine scope
 * - cancel() uses AtomicBoolean for thread-safe idempotency
 * - cachedException field is volatile (written once, read multiple times)
 */
internal class LocalActivityHandleImpl(
    override val activityId: String,
    initialSeq: Int,
    internal val activityType: String,
    private val state: WorkflowState,
    private val context: WorkflowContextImpl,
    override val serializer: PayloadSerializer,
    private val options: LocalActivityOptions,
    private val cancellationType: ActivityCancellationType,
    private val arguments: List<Payload>,
) : LocalActivityHandle {
    companion object {
        private val logger = Logger.getLogger(LocalActivityHandleImpl::class.java.name)
    }

    /** Current sequence number - changes on backoff reschedule. */
    private var currentSeq: Int = initialSeq

    /** Result deferred - replaced on backoff reschedule. */
    private var resultDeferred = CompletableDeferred<Payload?>()

    /** Whether cancellation has been requested. */
    private val cancellationRequested = AtomicBoolean(false)

    /** Cached exception from resolution (if any). */
    @Volatile
    private var cachedException: ActivityException? = null

    /** Whether the activity has been finally resolved (not a backoff). */
    @Volatile
    private var isFinallyDone: Boolean = false

    override val isDone: Boolean
        get() = isFinallyDone

    override val isCancellationRequested: Boolean
        get() = cancellationRequested.get()

    override suspend fun resultPayload(): TemporalPayload? {
        logger.fine("Awaiting result for local activity: type=$activityType, id=$activityId, seq=$currentSeq")

        while (true) {
            try {
                // Wait for resolution
                val payload = resultDeferred.await()

                // Check if we resolved with an exception
                cachedException?.let { throw it }

                // Return raw payload (deserialization happens via extension function)
                return payload?.toTemporal()
            } catch (e: DoBackoffException) {
                logger.fine(
                    "Local activity received backoff: type=$activityType, id=$activityId, " +
                        "backoffDuration=${e.backoffDuration}, nextAttempt=${e.attempt}",
                )

                // Sleep for backoff duration using workflow timer
                context.sleep(e.backoffDuration)

                // Reschedule with new seq and backoff info
                rescheduleLocalActivity(e)
            }
        }
    }

    override fun cancel(reason: String) {
        // Check if already done
        if (isDone) {
            logger.fine("Local activity already done, cancel is no-op: id=$activityId, seq=$currentSeq")
            return
        }

        // Use compareAndSet for thread-safe idempotency
        if (!cancellationRequested.compareAndSet(false, true)) {
            logger.fine("Local activity cancellation already requested: id=$activityId, seq=$currentSeq")
            return
        }

        logger.info(
            "Requesting local activity cancellation: type=$activityType, id=$activityId, seq=$currentSeq, " +
                "cancellationType=$cancellationType, reason=\"$reason\"",
        )

        // Handle different cancellation types
        when (cancellationType) {
            ActivityCancellationType.TRY_CANCEL -> {
                // Send cancel command - Core SDK resolves immediately
                sendCancelCommand()
            }

            ActivityCancellationType.WAIT_CANCELLATION_COMPLETED -> {
                // Send cancel command - Core SDK waits for acknowledgment
                sendCancelCommand()
            }

            ActivityCancellationType.ABANDON -> {
                // Don't send cancel command - just mark as cancelled locally
                logger.info("Abandoning local activity without sending cancel command: id=$activityId, seq=$currentSeq")
            }
        }
    }

    /**
     * Sends RequestCancelLocalActivity command to Core SDK.
     */
    private fun sendCancelCommand() {
        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setRequestCancelLocalActivity(
                    WorkflowCommands.RequestCancelLocalActivity
                        .newBuilder()
                        .setSeq(currentSeq),
                ).build()

        state.addCommand(command)
    }

    override fun exceptionOrNull(): ActivityException? = cachedException

    /**
     * Handles a DoBackoff resolution from Core SDK.
     *
     * This is called when the retry backoff exceeds the local retry threshold.
     * Core SDK expects lang to:
     * 1. Schedule a timer for the backoff duration
     * 2. Reschedule the local activity with a NEW sequence number
     *
     * CRITICAL: A new sequence number must be used because each ScheduleLocalActivity
     * is a distinct command that requires its own identifier.
     *
     * @param backoff The DoBackoff message from Core SDK containing attempt and timing info
     */
    internal fun resolveBackoff(backoff: ActivityResult.DoBackoff) {
        logger.fine(
            "Resolving backoff for local activity: type=$activityType, id=$activityId, " +
                "currentSeq=$currentSeq, nextAttempt=${backoff.attempt}, " +
                "backoffDuration=${backoff.backoffDuration.seconds}s",
        )

        // Get NEW sequence number - critical!
        val newSeq = state.nextSeq()

        // Create exception with backoff info for control flow
        val exception =
            DoBackoffException(
                attempt = backoff.attempt.toUInt(),
                backoffDuration = backoff.backoffDuration.toKotlinDuration(),
                originalScheduleTime = if (backoff.hasOriginalScheduleTime()) backoff.originalScheduleTime else null,
            )

        // Complete the current deferred exceptionally to signal backoff
        resultDeferred.completeExceptionally(exception)

        // Prepare new deferred for the retry result
        resultDeferred = CompletableDeferred()

        // Update current seq to the new one
        currentSeq = newSeq

        // Register with new seq before the reschedule (will be done in rescheduleLocalActivity)
    }

    /**
     * Reschedules the local activity after a backoff timer has completed.
     */
    private fun rescheduleLocalActivity(backoff: DoBackoffException) {
        logger.fine(
            "Rescheduling local activity: type=$activityType, id=$activityId, " +
                "newSeq=$currentSeq, attempt=${backoff.attempt}",
        )

        // Build new ScheduleLocalActivity with retry info
        val command = buildScheduleLocalActivityCommand(backoff)
        state.addCommand(command)

        // Re-register with the new seq
        state.registerLocalActivity(currentSeq, this)
    }

    /**
     * Builds a ScheduleLocalActivity command for a retry.
     */
    private fun buildScheduleLocalActivityCommand(backoff: DoBackoffException): WorkflowCommands.WorkflowCommand {
        val builder =
            WorkflowCommands.ScheduleLocalActivity
                .newBuilder()
                .setSeq(currentSeq)
                .setActivityId(activityId)
                .setActivityType(activityType)
                .setAttempt(backoff.attempt.toInt())
                .addAllArguments(arguments)

        // Set original schedule time (required for correct timeout calculations)
        backoff.originalScheduleTime?.let { builder.setOriginalScheduleTime(it) }

        // Set timeouts
        options.startToCloseTimeout?.let {
            builder.setStartToCloseTimeout(it.toProtoDuration())
        }
        options.scheduleToCloseTimeout?.let {
            builder.setScheduleToCloseTimeout(it.toProtoDuration())
        }
        options.scheduleToStartTimeout?.let {
            builder.setScheduleToStartTimeout(it.toProtoDuration())
        }

        // Set retry policy if provided
        options.retryPolicy?.let { policy ->
            builder.setRetryPolicy(policy.toProto())
        }

        // Set local retry threshold
        builder.setLocalRetryThreshold(options.localRetryThreshold.toProtoDuration())

        // Set cancellation type
        builder.setCancellationType(cancellationType.toLocalActivityProto())

        return WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setScheduleLocalActivity(builder)
            .build()
    }

    /**
     * Resolves the local activity with a completed result.
     */
    internal fun resolveCompleted(result: ActivityResult.Success) {
        logger.fine("Local activity completed: type=$activityType, id=$activityId, seq=$currentSeq")
        isFinallyDone = true
        resultDeferred.complete(result.result)
    }

    /**
     * Resolves the local activity with a failure.
     */
    internal fun resolveFailed(failure: ActivityResult.Failure) {
        logger.warning("Local activity failed: type=$activityType, id=$activityId, seq=$currentSeq")
        isFinallyDone = true
        val exception = buildFailureException(failure.failure)
        cachedException = exception
        resultDeferred.complete(null) // Complete normally, exception is cached
    }

    /**
     * Resolves the local activity with cancellation.
     */
    internal fun resolveCancelled(cancellation: ActivityResult.Cancellation) {
        logger.warning("Local activity cancelled: type=$activityType, id=$activityId, seq=$currentSeq")
        isFinallyDone = true
        val exception =
            ActivityCancelledException(
                activityType = activityType,
                activityId = activityId,
                message = "Local activity was cancelled",
                cause =
                    if (cancellation.hasFailure() && cancellation.failure.hasCause()) {
                        buildCause(cancellation.failure)
                    } else {
                        null
                    },
            )
        cachedException = exception
        resultDeferred.complete(null) // Complete normally, exception is cached
    }

    /**
     * Builds an ActivityException from a proto Failure.
     * Returns ActivityTimeoutException for timeouts, ActivityFailureException for other failures.
     */
    private fun buildFailureException(failure: io.temporal.api.failure.v1.Failure): ActivityException {
        // Check for timeout first - should return ActivityTimeoutException
        if (failure.hasTimeoutFailureInfo()) {
            val timeoutInfo = failure.timeoutFailureInfo
            return ActivityTimeoutException(
                activityType = activityType,
                activityId = activityId,
                timeoutType = mapTimeoutType(timeoutInfo.timeoutType),
                message = failure.message ?: "Local activity timed out",
                cause = if (failure.hasCause()) buildCause(failure.cause) else null,
            )
        }

        return ActivityFailureException(
            activityType = activityType,
            activityId = activityId,
            failureType = determineFailureType(failure),
            retryState = extractRetryState(failure),
            applicationFailure = extractApplicationFailure(failure),
            message = failure.message ?: "Local activity failed",
            cause = if (failure.hasCause()) buildCause(failure.cause) else null,
        )
    }
}

/**
 * Internal exception for control flow during backoff handling (not user-facing).
 *
 * This exception is thrown when Core SDK signals a DoBackoff, allowing the
 * result() method to catch it, schedule a timer, and reschedule the activity.
 *
 * @property attempt The NEXT attempt number (not the one that just failed)
 * @property backoffDuration How long to wait before retrying
 * @property originalScheduleTime The time the first attempt was originally scheduled
 */
internal class DoBackoffException(
    val attempt: UInt,
    val backoffDuration: Duration,
    val originalScheduleTime: Timestamp?,
) : Exception("Local activity backoff: attempt=$attempt, duration=$backoffDuration")

/**
 * Converts a Kotlin Duration to a protobuf Duration.
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
 * Converts a protobuf Duration to a Kotlin Duration.
 */
private fun com.google.protobuf.Duration.toKotlinDuration(): Duration {
    val javaDuration = java.time.Duration.ofSeconds(this.seconds, this.nanos.toLong())
    return javaDuration.toKotlinDuration()
}

/**
 * Converts domain ActivityCancellationType to protobuf enum for local activities.
 */
private fun ActivityCancellationType.toLocalActivityProto(): WorkflowCommands.ActivityCancellationType =
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
 * Converts domain RetryPolicy to protobuf message.
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
