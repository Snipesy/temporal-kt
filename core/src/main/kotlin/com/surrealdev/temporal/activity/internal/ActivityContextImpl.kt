package com.surrealdev.temporal.activity.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.activity.ActivityCancelledException
import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.ActivityInfo
import com.surrealdev.temporal.activity.ActivityWorkflowInfo
import com.surrealdev.temporal.activity.HeartbeatDetails
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.ExecutionScope
import coresdk.activity_task.ActivityTaskOuterClass.Start
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Implementation of [ActivityContext] for activity execution.
 *
 * This context is created for each activity task and provides access to
 * activity information and operations like heartbeating.
 */
internal class ActivityContextImpl(
    private val start: Start,
    private val taskToken: ByteString,
    private val taskQueue: String,
    override val serializer: PayloadSerializer,
    private val heartbeatFn: suspend (ByteArray, ByteArray?) -> Unit,
    override val parentScope: AttributeScope,
    private val parentCoroutineContext: CoroutineContext,
) : ActivityContext,
    ExecutionScope {
    // Activity executions have their own attributes (currently empty, for future use)
    override val attributes: Attributes = Attributes(concurrent = false)
    override val isWorkflowContext: Boolean = false

    // CoroutineScope implementation - uses parent context + this element
    // This allows activity code to launch child coroutines that inherit the activity's job
    override val coroutineContext: CoroutineContext = parentCoroutineContext + this

    @Volatile
    private var _isCancellationRequested = false

    override val info: ActivityInfo by lazy {
        buildActivityInfo()
    }

    override suspend fun heartbeatWithPayload(details: io.temporal.api.common.v1.Payload?) {
        // Check cancellation before heartbeating
        if (_isCancellationRequested) {
            throw ActivityCancelledException("Activity cancellation was requested")
        }

        heartbeatFn(taskToken.toByteArray(), details?.toByteArray())

        // Check cancellation after heartbeating (in case it was set during the call)
        if (_isCancellationRequested) {
            throw ActivityCancelledException("Activity cancellation was requested")
        }
    }

    override val isCancellationRequested: Boolean
        get() = _isCancellationRequested

    override fun ensureNotCancelled() {
        if (_isCancellationRequested) {
            throw ActivityCancelledException()
        }
    }

    /**
     * Marks the activity as cancelled.
     * Called when a cancellation task is received.
     */
    internal fun markCancelled() {
        _isCancellationRequested = true
    }

    private fun buildActivityInfo(): ActivityInfo {
        val workflowExecution = start.workflowExecution
        val startedTime =
            if (start.hasStartedTime()) {
                start.startedTime.toKotlinInstant()
            } else {
                java.time.Instant
                    .now()
                    .toKotlinInstant()
            }

        // Calculate deadline from start time + timeout
        val deadline =
            if (start.hasStartToCloseTimeout()) {
                val timeout = start.startToCloseTimeout
                val timeoutDuration = timeout.seconds.seconds + timeout.nanos.nanoseconds
                startedTime + timeoutDuration
            } else {
                null
            }

        // Wrap heartbeat details from previous attempt for lazy deserialization
        val heartbeatDetails: HeartbeatDetails? =
            start.heartbeatDetailsList.firstOrNull()?.let { payload ->
                HeartbeatDetails(payload, serializer)
            }

        return ActivityInfo(
            activityId = start.activityId,
            activityType = start.activityType,
            taskQueue = taskQueue,
            attempt = start.attempt,
            startTime = startedTime,
            deadline = deadline,
            heartbeatDetails = heartbeatDetails,
            workflowInfo =
                ActivityWorkflowInfo(
                    workflowId = workflowExecution.workflowId,
                    runId = workflowExecution.runId,
                    workflowType = start.workflowType,
                    namespace = start.workflowNamespace,
                ),
        )
    }
}

/**
 * Extension to convert protobuf Timestamp to Kotlin Instant.
 */
private fun com.google.protobuf.Timestamp.toKotlinInstant(): Instant {
    val javaInstant = java.time.Instant.ofEpochSecond(seconds, nanos.toLong())
    return javaInstant.toKotlinInstant()
}
