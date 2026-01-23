package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.workflow.ActivityCancellationType
import com.surrealdev.temporal.workflow.ActivityCancelledException
import com.surrealdev.temporal.workflow.ActivityException
import com.surrealdev.temporal.workflow.ActivityFailureException
import com.surrealdev.temporal.workflow.ActivityHandle
import com.surrealdev.temporal.workflow.ActivityRetryState
import com.surrealdev.temporal.workflow.ActivityTimeoutException
import com.surrealdev.temporal.workflow.ActivityTimeoutType
import com.surrealdev.temporal.workflow.ApplicationFailure
import coresdk.activity_result.ActivityResult
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.reflect.KType

/**
 * Internal implementation of ActivityHandle that manages the lifecycle of an activity invocation.
 *
 * This class handles:
 * - Async result awaiting via CompletableDeferred
 * - Thread-safe cancellation with idempotency
 * - Result deserialization with proper type handling
 * - Exception mapping from proto failures to Kotlin exceptions
 *
 * Thread Safety:
 * - Designed for single workflow coroutine scope
 * - cancel() uses AtomicBoolean for thread-safe idempotency
 * - cachedException field is volatile (written once, read multiple times)
 */
internal class ActivityHandleImpl<R>(
    override val activityId: String,
    internal val seq: Int,
    private val activityType: String,
    private val state: WorkflowState,
    private val serializer: PayloadSerializer,
    private val returnType: KType,
    private val cancellationType: ActivityCancellationType,
) : ActivityHandle<R> {
    companion object {
        private val logger = Logger.getLogger(ActivityHandleImpl::class.java.name)
    }

    /** Deferred that completes when the activity resolves. */
    internal val resultDeferred = CompletableDeferred<Payload?>()

    /** Whether cancellation has been requested. */
    private val cancellationRequested = AtomicBoolean(false)

    /** Cached exception from resolution (if any). */
    @Volatile
    private var cachedException: ActivityException? = null

    override val isDone: Boolean
        get() = resultDeferred.isCompleted

    override val isCancellationRequested: Boolean
        get() = cancellationRequested.get()

    @Suppress("UNCHECKED_CAST")
    override suspend fun result(): R {
        logger.fine("Awaiting result for activity: type=$activityType, id=$activityId, seq=$seq")

        // This may throw if activity failed/cancelled (via resolve())
        val payload = resultDeferred.await()

        // Check if we resolved with an exception
        cachedException?.let { throw it }

        // Deserialize the result
        return deserializeResult(payload)
    }

    /**
     * Deserializes the result payload to the expected type R.
     * Handles Unit and null results correctly.
     */
    @Suppress("UNCHECKED_CAST")
    private fun deserializeResult(payload: Payload?): R =
        if (payload == null || payload == Payload.getDefaultInstance() || payload.data.isEmpty) {
            // Empty payload means Unit or null
            if (returnType.classifier == Unit::class) {
                Unit as R
            } else {
                null as R
            }
        } else {
            // Deserialize using stored type info
            serializer.deserialize(
                returnType,
                payload,
            ) as R
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

    override fun exceptionOrNull(): ActivityException? = cachedException

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
    internal fun resolve(result: ActivityResult.ActivityResolution) {
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
                    ActivityCancelledException(
                        activityType = activityType,
                        activityId = activityId,
                        message = "Activity was cancelled",
                        cause =
                            if (cancelledFailure != null && cancelledFailure.hasCause()) {
                                buildCause(cancelledFailure)
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
                message = failure.message ?: "Activity timed out",
                cause = if (failure.hasCause()) buildCause(failure.cause) else null,
            )
        }

        // For all other failures, continue with ActivityFailureException
        // Extract failure type
        val failureType =
            when {
                failure.hasApplicationFailureInfo() -> "ApplicationFailure"
                failure.hasCanceledFailureInfo() -> "CanceledFailure"
                failure.hasTerminatedFailureInfo() -> "TerminatedFailure"
                failure.hasServerFailureInfo() -> "ServerFailure"
                failure.hasResetWorkflowFailureInfo() -> "ResetWorkflowFailure"
                failure.hasActivityFailureInfo() -> "ActivityFailure"
                failure.hasChildWorkflowExecutionFailureInfo() -> "ChildWorkflowExecutionFailure"
                else -> "UnknownFailure"
            }

        // Extract retry state
        val retryState =
            if (failure.hasActivityFailureInfo()) {
                val activityFailureInfo = failure.activityFailureInfo
                mapRetryState(activityFailureInfo.retryState)
            } else {
                ActivityRetryState.UNSPECIFIED
            }

        // Extract application failure details if present
        // Check both the failure itself and its cause chain
        val applicationFailure = extractApplicationFailure(failure)

        return ActivityFailureException(
            activityType = activityType,
            activityId = activityId,
            failureType = failureType,
            retryState = retryState,
            applicationFailure = applicationFailure,
            message = failure.message ?: "Activity failed",
            cause = if (failure.hasCause()) buildCause(failure.cause) else null,
        )
    }

    /**
     * Maps proto RetryState to Kotlin enum.
     */
    private fun mapRetryState(protoState: io.temporal.api.enums.v1.RetryState): ActivityRetryState =
        when (protoState) {
            io.temporal.api.enums.v1.RetryState.RETRY_STATE_UNSPECIFIED -> {
                ActivityRetryState.UNSPECIFIED
            }

            io.temporal.api.enums.v1.RetryState.RETRY_STATE_IN_PROGRESS -> {
                ActivityRetryState.IN_PROGRESS
            }

            io.temporal.api.enums.v1.RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE -> {
                ActivityRetryState.NON_RETRYABLE_FAILURE
            }

            io.temporal.api.enums.v1.RetryState.RETRY_STATE_TIMEOUT -> {
                ActivityRetryState.TIMEOUT
            }

            io.temporal.api.enums.v1.RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED -> {
                ActivityRetryState.MAXIMUM_ATTEMPTS_REACHED
            }

            io.temporal.api.enums.v1.RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET -> {
                ActivityRetryState.RETRY_POLICY_NOT_SET
            }

            io.temporal.api.enums.v1.RetryState.RETRY_STATE_INTERNAL_SERVER_ERROR -> {
                ActivityRetryState.INTERNAL_SERVER_ERROR
            }

            io.temporal.api.enums.v1.RetryState.RETRY_STATE_CANCEL_REQUESTED -> {
                ActivityRetryState.CANCEL_REQUESTED
            }

            else -> {
                ActivityRetryState.UNSPECIFIED
            }
        }

    /**
     * Maps proto TimeoutType to Kotlin enum.
     */
    private fun mapTimeoutType(protoType: io.temporal.api.enums.v1.TimeoutType): ActivityTimeoutType =
        when (protoType) {
            io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START -> {
                ActivityTimeoutType.SCHEDULE_TO_START
            }

            io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE -> {
                ActivityTimeoutType.START_TO_CLOSE
            }

            io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE -> {
                ActivityTimeoutType.SCHEDULE_TO_CLOSE
            }

            io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_HEARTBEAT -> {
                ActivityTimeoutType.HEARTBEAT
            }

            else -> {
                ActivityTimeoutType.START_TO_CLOSE
            } // Default fallback
        }

    /**
     * Extracts ApplicationFailure from the failure or its cause chain.
     * Temporal wraps application failures: ActivityFailureInfo contains ApplicationFailureInfo in cause.
     */
    private fun extractApplicationFailure(
        failure: io.temporal.api.failure.v1.Failure,
        depth: Int = 0,
    ): ApplicationFailure? {
        val maxDepth = 10
        if (depth >= maxDepth) return null

        // Check this level
        if (failure.hasApplicationFailureInfo()) {
            val appInfo = failure.applicationFailureInfo
            return ApplicationFailure(
                type = appInfo.type ?: "UnknownApplicationFailure",
                message = failure.message,
                nonRetryable = appInfo.nonRetryable,
                details = appInfo.details?.toByteArray(),
            )
        }

        // Check nested cause
        return if (failure.hasCause()) {
            extractApplicationFailure(failure.cause, depth + 1)
        } else {
            null
        }
    }

    /**
     * Recursively builds cause exceptions from proto Failure.
     * Limits recursion depth to prevent stack overflow.
     */
    private fun buildCause(
        failure: io.temporal.api.failure.v1.Failure,
        depth: Int = 0,
    ): Throwable {
        val maxDepth = 20
        if (depth >= maxDepth) {
            return RuntimeException(failure.message ?: "Cause failure (max depth reached)")
        }

        val nestedCause =
            if (failure.hasCause()) {
                buildCause(failure.cause, depth + 1)
            } else {
                null
            }

        return RuntimeException(failure.message ?: "Cause failure", nestedCause)
    }
}
