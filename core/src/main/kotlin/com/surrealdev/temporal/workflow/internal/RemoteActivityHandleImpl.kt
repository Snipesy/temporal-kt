package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.WorkflowActivityCancelledException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityTimeoutException
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.safeDecodeSingle
import com.surrealdev.temporal.workflow.ActivityCancellationType
import com.surrealdev.temporal.workflow.RemoteActivityHandle
import coresdk.activity_result.ActivityResult
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Internal implementation of ActivityHandle that manages the lifecycle of an activity invocation.
 *
 * This class handles:
 * - Async result awaiting via CompletableDeferred
 * - Thread-safe cancellation with idempotency
 * - Raw payload results (deserialization happens via extension function)
 * - Exception mapping from proto failures to Kotlin exceptions
 *
 * Thread Safety:
 * - Designed for single workflow coroutine scope
 * - cancel() uses AtomicBoolean for thread-safe idempotency
 * - cachedException field is volatile (written once, read multiple times)
 */
internal class RemoteActivityHandleImpl(
    override val activityId: String,
    internal val seq: Int,
    internal val activityType: String,
    private val state: WorkflowState,
    override val serializer: PayloadSerializer,
    private val codec: PayloadCodec,
    private val cancellationType: ActivityCancellationType,
) : RemoteActivityHandle {
    companion object {
        private val logger = Logger.getLogger(RemoteActivityHandleImpl::class.java.name)
    }

    /** Deferred that completes when the activity resolves. */
    internal val resultDeferred = CompletableDeferred<Payload?>()

    /** Whether cancellation has been requested. */
    private val cancellationRequested = AtomicBoolean(false)

    /** Cached exception from resolution (if any). */
    @Volatile
    private var cachedException: WorkflowActivityException? = null

    override val isDone: Boolean
        get() = resultDeferred.isCompleted

    override val isCancellationRequested: Boolean
        get() = cancellationRequested.get()

    override suspend fun resultPayload(): TemporalPayload? {
        logger.fine("Awaiting result for activity: type=$activityType, id=$activityId, seq=$seq")

        // This may throw if activity failed/cancelled (via resolve())
        val payload = resultDeferred.await()

        // Check if we resolved with an exception
        cachedException?.let { throw it }

        // Decode through codec, then return
        return payload?.let {
            codec.safeDecodeSingle(it)
        }
    }

    override fun cancel(reason: String) {
        // Check if already done
        if (isDone) {
            logger.fine("Activity already done, cancel is no-op: id=$activityId, seq=$seq")
            return
        }

        // Use compareAndSet for thread-safe idempotency
        if (!cancellationRequested.compareAndSet(false, true)) {
            logger.fine("Activity cancellation already requested: id=$activityId, seq=$seq")
            return
        }

        logger.info(
            "Requesting activity cancellation: type=$activityType, id=$activityId, seq=$seq, " +
                "cancellationType=$cancellationType, reason=\"$reason\"",
        )

        // Handle different cancellation types
        when (cancellationType) {
            ActivityCancellationType.TRY_CANCEL -> {
                // Send cancel command - Core SDK resolves immediately
                sendCancelCommand()
            }

            ActivityCancellationType.WAIT_CANCELLATION_COMPLETED -> {
                // Send cancel command - Core SDK waits for ActivityTaskCanceled event
                // The difference from TRY_CANCEL is in Core SDK behavior, not our behavior
                sendCancelCommand()
            }

            ActivityCancellationType.ABANDON -> {
                // Don't send cancel command - just mark as cancelled locally
                // Activity continues running but workflow doesn't wait for it
                logger.info("Abandoning activity without sending cancel command: id=$activityId, seq=$seq")
                // Note: We don't send a command, cancellation is local-only
            }
        }
    }

    /**
     * Sends RequestCancelActivity command to Core SDK.
     */
    private fun sendCancelCommand() {
        val command =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setRequestCancelActivity(
                    WorkflowCommands.RequestCancelActivity
                        .newBuilder()
                        .setSeq(seq),
                ).build()

        state.addCommand(command)
    }

    override fun exceptionOrNull(): WorkflowActivityException? = cachedException

    /**
     * Resolves the activity with the given result.
     * Called by WorkflowState when a ResolveActivity job is received.
     *
     * Handles four resolution types:
     * - Completed: Complete deferred with payload
     * - Failed: Build exception and complete exceptionally
     * - Cancelled: Build cancellation exception and complete exceptionally
     * - Backoff: Throw IllegalStateException (should never happen for regular activities)
     */
    internal suspend fun resolve(result: ActivityResult.ActivityResolution) {
        when {
            result.hasCompleted() -> {
                logger.fine("Activity completed: type=$activityType, id=$activityId, seq=$seq")
                val payload = result.completed.result
                resultDeferred.complete(payload)
            }

            result.hasFailed() -> {
                logger.warning("Activity failed: type=$activityType, id=$activityId, seq=$seq")
                val exception = buildFailureException(result.failed.failure)
                cachedException = exception
                resultDeferred.completeExceptionally(exception)
            }

            result.hasCancelled() -> {
                logger.warning("Activity cancelled: type=$activityType, id=$activityId, seq=$seq")
                val cancelledFailure = result.cancelled.failure
                val exception =
                    WorkflowActivityCancelledException(
                        activityType = activityType,
                        activityId = activityId,
                        message = "Activity was cancelled",
                        cause =
                            if (cancelledFailure != null && cancelledFailure.hasCause()) {
                                buildCause(cancelledFailure, codec)
                            } else {
                                null
                            },
                    )
                cachedException = exception
                resultDeferred.completeExceptionally(exception)
            }

            result.hasBackoff() -> {
                // Per CTO review: Regular activities NEVER receive DoBackoff (only local activities)
                // Core SDK's activity_state_machine.rs only produces: Completed, Failed, Cancelled
                logger.severe(
                    "INVALID STATE: Regular activity received backoff resolution: type=$activityType, id=$activityId, seq=$seq",
                )
                throw IllegalStateException(
                    "Regular activity received DoBackoff resolution (invalid - only local activities use backoff). " +
                        "activityType=$activityType, activityId=$activityId, seq=$seq",
                )
            }

            else -> {
                logger.severe("Unknown activity resolution status: type=$activityType, id=$activityId, seq=$seq")
                throw IllegalStateException("Unknown activity resolution status for seq=$seq")
            }
        }
    }

    /**
     * Builds a WorkflowActivityException from a proto Failure.
     * Returns WorkflowActivityTimeoutException for timeouts, WorkflowActivityFailureException for other failures.
     */
    private suspend fun buildFailureException(failure: io.temporal.api.failure.v1.Failure): WorkflowActivityException {
        // Check for timeout first - should return WorkflowActivityTimeoutException
        if (failure.hasTimeoutFailureInfo()) {
            val timeoutInfo = failure.timeoutFailureInfo
            return WorkflowActivityTimeoutException(
                activityType = activityType,
                activityId = activityId,
                timeoutType = mapTimeoutType(timeoutInfo.timeoutType),
                message = failure.message ?: "Activity timed out",
                cause = if (failure.hasCause()) buildCause(failure.cause, codec) else null,
            )
        }

        // Build the cause chain. When the root failure has ApplicationFailureInfo,
        // create an ApplicationFailure exception as the cause (with any nested causes).
        // This ensures applicationFailure is always findable in the cause chain.
        val cause: Throwable? =
            if (failure.hasApplicationFailureInfo()) {
                val nestedCause = if (failure.hasCause()) buildCause(failure.cause, codec) else null
                buildApplicationFailureFromProto(failure, codec, cause = nestedCause)
            } else if (failure.hasCause()) {
                buildCause(failure.cause, codec)
            } else {
                null
            }

        return WorkflowActivityFailureException(
            activityType = activityType,
            activityId = activityId,
            failureType = determineFailureType(failure),
            retryState = extractRetryState(failure),
            message = failure.message ?: "Activity failed",
            cause = cause,
        )
    }
}
