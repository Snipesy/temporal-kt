package com.surrealdev.temporal.client.history

import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.safeDecode
import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.HistoryEvent
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Kotlin wrapper for workflow history events.
 *
 * A sealed class hierarchy where each event type is represented as a concrete subclass
 * with typed, Kotlin-friendly fields extracted from the underlying protobuf event.
 *
 * @property eventId The unique sequential ID of this event within the workflow history.
 * @property eventType The type of this history event.
 * @property timestamp The timestamp of this event in epoch milliseconds.
 */
sealed class TemporalHistoryEvent {
    abstract val eventId: Long
    abstract val eventType: TemporalEventType
    abstract val timestamp: Long

    // ========== Workflow Execution Lifecycle ==========

    data class WorkflowExecutionStarted(
        override val eventId: Long,
        override val timestamp: Long,
        val workflowType: String,
        val taskQueue: String,
        val input: TemporalPayloads?,
        val originalExecutionRunId: String,
        val firstExecutionRunId: String,
        val attempt: Int,
        val identity: String,
        val continuedExecutionRunId: String?,
        val parentWorkflowNamespace: String?,
        val parentWorkflowId: String?,
        val parentRunId: String?,
        val cronSchedule: String?,
        val workflowExecutionTimeout: Duration?,
        val workflowRunTimeout: Duration?,
        val workflowTaskTimeout: Duration?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_STARTED
    }

    data class WorkflowExecutionCompleted(
        override val eventId: Long,
        override val timestamp: Long,
        val result: TemporalPayloads?,
        val newExecutionRunId: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_COMPLETED
    }

    data class WorkflowExecutionFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val failureMessage: String?,
        val retryState: String,
        val newExecutionRunId: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_FAILED
    }

    data class WorkflowExecutionTimedOut(
        override val eventId: Long,
        override val timestamp: Long,
        val retryState: String,
        val newExecutionRunId: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_TIMED_OUT
    }

    data class WorkflowExecutionCanceled(
        override val eventId: Long,
        override val timestamp: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_CANCELED
    }

    data class WorkflowExecutionTerminated(
        override val eventId: Long,
        override val timestamp: Long,
        val reason: String?,
        val identity: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_TERMINATED
    }

    data class WorkflowExecutionContinuedAsNew(
        override val eventId: Long,
        override val timestamp: Long,
        val newExecutionRunId: String,
        val workflowType: String,
        val taskQueue: String,
        val workflowRunTimeout: Duration?,
        val workflowTaskTimeout: Duration?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_CONTINUED_AS_NEW
    }

    data class WorkflowExecutionSignaled(
        override val eventId: Long,
        override val timestamp: Long,
        val signalName: String,
        val identity: String,
        val externalWorkflowId: String?,
        val externalRunId: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_SIGNALED
    }

    data class WorkflowExecutionCancelRequested(
        override val eventId: Long,
        override val timestamp: Long,
        val cause: String,
        val identity: String,
        val externalWorkflowId: String?,
        val externalRunId: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_CANCEL_REQUESTED
    }

    data class WorkflowExecutionPaused(
        override val eventId: Long,
        override val timestamp: Long,
        val identity: String,
        val reason: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_PAUSED
    }

    data class WorkflowExecutionUnpaused(
        override val eventId: Long,
        override val timestamp: Long,
        val identity: String,
        val reason: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_UNPAUSED
    }

    data class WorkflowExecutionOptionsUpdated(
        override val eventId: Long,
        override val timestamp: Long,
        val identity: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_OPTIONS_UPDATED
    }

    // ========== Workflow Execution Updates ==========

    data class WorkflowExecutionUpdateAdmitted(
        override val eventId: Long,
        override val timestamp: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_UPDATE_ADMITTED
    }

    data class WorkflowExecutionUpdateAccepted(
        override val eventId: Long,
        override val timestamp: Long,
        val protocolInstanceId: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_UPDATE_ACCEPTED
    }

    data class WorkflowExecutionUpdateRejected(
        override val eventId: Long,
        override val timestamp: Long,
        val protocolInstanceId: String,
        val failureMessage: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_UPDATE_REJECTED
    }

    data class WorkflowExecutionUpdateCompleted(
        override val eventId: Long,
        override val timestamp: Long,
        val acceptedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_EXECUTION_UPDATE_COMPLETED
    }

    // ========== Workflow Task Events ==========

    data class WorkflowTaskScheduled(
        override val eventId: Long,
        override val timestamp: Long,
        val taskQueue: String,
        val attempt: Int,
        val startToCloseTimeout: Duration?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_TASK_SCHEDULED
    }

    data class WorkflowTaskStarted(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val identity: String,
        val requestId: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_TASK_STARTED
    }

    data class WorkflowTaskCompleted(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val startedEventId: Long,
        val identity: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_TASK_COMPLETED
    }

    data class WorkflowTaskTimedOut(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val startedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_TASK_TIMED_OUT
    }

    data class WorkflowTaskFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val startedEventId: Long,
        val cause: String,
        val failureMessage: String?,
        val identity: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_TASK_FAILED
    }

    // ========== Activity Task Events ==========

    data class ActivityTaskScheduled(
        override val eventId: Long,
        override val timestamp: Long,
        val activityId: String,
        val activityType: String,
        val taskQueue: String,
        val input: TemporalPayloads?,
        val scheduleToCloseTimeout: Duration?,
        val scheduleToStartTimeout: Duration?,
        val startToCloseTimeout: Duration?,
        val heartbeatTimeout: Duration?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.ACTIVITY_TASK_SCHEDULED
    }

    data class ActivityTaskStarted(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val identity: String,
        val requestId: String,
        val attempt: Int,
        val lastFailureMessage: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.ACTIVITY_TASK_STARTED
    }

    data class ActivityTaskCompleted(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val startedEventId: Long,
        val identity: String,
        val result: TemporalPayloads?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.ACTIVITY_TASK_COMPLETED
    }

    data class ActivityTaskFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val startedEventId: Long,
        val identity: String,
        val failureMessage: String?,
        val retryState: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.ACTIVITY_TASK_FAILED
    }

    data class ActivityTaskTimedOut(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val startedEventId: Long,
        val failureMessage: String?,
        val retryState: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.ACTIVITY_TASK_TIMED_OUT
    }

    data class ActivityTaskCancelRequested(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.ACTIVITY_TASK_CANCEL_REQUESTED
    }

    data class ActivityTaskCanceled(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val startedEventId: Long,
        val identity: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.ACTIVITY_TASK_CANCELED
    }

    // ========== Timer Events ==========

    data class TimerStarted(
        override val eventId: Long,
        override val timestamp: Long,
        val timerId: String,
        val startToFireTimeout: Duration?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.TIMER_STARTED
    }

    data class TimerFired(
        override val eventId: Long,
        override val timestamp: Long,
        val timerId: String,
        val startedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.TIMER_FIRED
    }

    data class TimerCanceled(
        override val eventId: Long,
        override val timestamp: Long,
        val timerId: String,
        val startedEventId: Long,
        val identity: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.TIMER_CANCELED
    }

    // ========== Child Workflow Events ==========

    data class StartChildWorkflowExecutionInitiated(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val workflowType: String,
        val taskQueue: String,
        val workflowExecutionTimeout: Duration?,
        val workflowRunTimeout: Duration?,
        val workflowTaskTimeout: Duration?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.START_CHILD_WORKFLOW_EXECUTION_INITIATED
    }

    data class StartChildWorkflowExecutionFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val workflowType: String,
        val cause: String,
        val initiatedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.START_CHILD_WORKFLOW_EXECUTION_FAILED
    }

    data class ChildWorkflowExecutionStarted(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String,
        val workflowType: String,
        val initiatedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.CHILD_WORKFLOW_EXECUTION_STARTED
    }

    data class ChildWorkflowExecutionCompleted(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String,
        val workflowType: String,
        val initiatedEventId: Long,
        val startedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.CHILD_WORKFLOW_EXECUTION_COMPLETED
    }

    data class ChildWorkflowExecutionFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String,
        val workflowType: String,
        val initiatedEventId: Long,
        val startedEventId: Long,
        val failureMessage: String?,
        val retryState: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.CHILD_WORKFLOW_EXECUTION_FAILED
    }

    data class ChildWorkflowExecutionCanceled(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String,
        val workflowType: String,
        val initiatedEventId: Long,
        val startedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.CHILD_WORKFLOW_EXECUTION_CANCELED
    }

    data class ChildWorkflowExecutionTerminated(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String,
        val workflowType: String,
        val initiatedEventId: Long,
        val startedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.CHILD_WORKFLOW_EXECUTION_TERMINATED
    }

    data class ChildWorkflowExecutionTimedOut(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String,
        val workflowType: String,
        val initiatedEventId: Long,
        val startedEventId: Long,
        val retryState: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.CHILD_WORKFLOW_EXECUTION_TIMED_OUT
    }

    // ========== External Workflow Events ==========

    data class SignalExternalWorkflowExecutionInitiated(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String?,
        val signalName: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED
    }

    data class SignalExternalWorkflowExecutionFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String?,
        val cause: String,
        val initiatedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED
    }

    data class ExternalWorkflowExecutionSignaled(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String?,
        val initiatedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.EXTERNAL_WORKFLOW_EXECUTION_SIGNALED
    }

    data class RequestCancelExternalWorkflowExecutionInitiated(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String?,
        val reason: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED
    }

    data class RequestCancelExternalWorkflowExecutionFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String?,
        val cause: String,
        val initiatedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED
    }

    data class ExternalWorkflowExecutionCancelRequested(
        override val eventId: Long,
        override val timestamp: Long,
        val namespace: String,
        val workflowId: String,
        val runId: String?,
        val initiatedEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED
    }

    // ========== Marker Events ==========

    data class MarkerRecorded(
        override val eventId: Long,
        override val timestamp: Long,
        val markerName: String,
        val failureMessage: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.MARKER_RECORDED
    }

    // ========== Search Attributes / Properties ==========

    data class UpsertWorkflowSearchAttributes(
        override val eventId: Long,
        override val timestamp: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.UPSERT_WORKFLOW_SEARCH_ATTRIBUTES
    }

    data class WorkflowPropertiesModified(
        override val eventId: Long,
        override val timestamp: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_PROPERTIES_MODIFIED
    }

    data class WorkflowPropertiesModifiedExternally(
        override val eventId: Long,
        override val timestamp: Long,
        val newTaskQueue: String?,
        val newWorkflowTaskTimeout: Duration?,
        val newWorkflowRunTimeout: Duration?,
        val newWorkflowExecutionTimeout: Duration?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.WORKFLOW_PROPERTIES_MODIFIED_EXTERNALLY
    }

    data class ActivityPropertiesModifiedExternally(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.ACTIVITY_PROPERTIES_MODIFIED_EXTERNALLY
    }

    // ========== Nexus Operation Events ==========

    data class NexusOperationScheduled(
        override val eventId: Long,
        override val timestamp: Long,
        val endpoint: String,
        val service: String,
        val operation: String,
        val requestId: String,
        val scheduleToCloseTimeout: Duration?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_SCHEDULED
    }

    data class NexusOperationStarted(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val requestId: String,
        val operationToken: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_STARTED
    }

    data class NexusOperationCompleted(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val requestId: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_COMPLETED
    }

    data class NexusOperationFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val failureMessage: String?,
        val requestId: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_FAILED
    }

    data class NexusOperationCanceled(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val failureMessage: String?,
        val requestId: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_CANCELED
    }

    data class NexusOperationTimedOut(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val failureMessage: String?,
        val requestId: String,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_TIMED_OUT
    }

    data class NexusOperationCancelRequested(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_CANCEL_REQUESTED
    }

    data class NexusOperationCancelRequestCompleted(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED
    }

    data class NexusOperationCancelRequestFailed(
        override val eventId: Long,
        override val timestamp: Long,
        val scheduledEventId: Long,
        val failureMessage: String?,
    ) : TemporalHistoryEvent() {
        override val eventType get() = TemporalEventType.NEXUS_OPERATION_CANCEL_REQUEST_FAILED
    }

    // ========== Unknown / Unmapped ==========

    data class Unknown(
        override val eventId: Long,
        override val eventType: TemporalEventType,
        override val timestamp: Long,
    ) : TemporalHistoryEvent()

    companion object {
        internal suspend fun fromProto(
            proto: HistoryEvent,
            codec: PayloadCodec,
        ): TemporalHistoryEvent {
            val eventId = proto.eventId
            val ts = proto.eventTime.seconds * 1000 + proto.eventTime.nanos / 1_000_000

            return when (proto.eventType) {
                // ---- Workflow Execution Lifecycle ----

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED -> {
                    val a = proto.workflowExecutionStartedEventAttributes
                    WorkflowExecutionStarted(
                        eventId = eventId,
                        timestamp = ts,
                        workflowType = a.workflowType.name,
                        taskQueue = a.taskQueue.name,
                        input =
                            if (a.hasInput()) {
                                codec.safeDecode(EncodedTemporalPayloads(a.input))
                            } else {
                                null
                            },
                        originalExecutionRunId = a.originalExecutionRunId,
                        firstExecutionRunId = a.firstExecutionRunId,
                        attempt = a.attempt,
                        identity = a.identity,
                        continuedExecutionRunId = a.continuedExecutionRunId.ifEmpty { null },
                        parentWorkflowNamespace = a.parentWorkflowNamespace.ifEmpty { null },
                        parentWorkflowId =
                            if (a.hasParentWorkflowExecution()) a.parentWorkflowExecution.workflowId else null,
                        parentRunId =
                            if (a.hasParentWorkflowExecution()) {
                                a.parentWorkflowExecution.runId.ifEmpty {
                                    null
                                }
                            } else {
                                null
                            },
                        cronSchedule = a.cronSchedule.ifEmpty { null },
                        workflowExecutionTimeout =
                            if (a.hasWorkflowExecutionTimeout()) {
                                a.workflowExecutionTimeout
                                    .toKt()
                            } else {
                                null
                            },
                        workflowRunTimeout = if (a.hasWorkflowRunTimeout()) a.workflowRunTimeout.toKt() else null,
                        workflowTaskTimeout = if (a.hasWorkflowTaskTimeout()) a.workflowTaskTimeout.toKt() else null,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED -> {
                    val a = proto.workflowExecutionCompletedEventAttributes
                    WorkflowExecutionCompleted(
                        eventId = eventId,
                        timestamp = ts,
                        result =
                            if (a.hasResult()) {
                                codec.safeDecode(EncodedTemporalPayloads(a.result))
                            } else {
                                null
                            },
                        newExecutionRunId = a.newExecutionRunId.ifEmpty { null },
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED -> {
                    val a = proto.workflowExecutionFailedEventAttributes
                    WorkflowExecutionFailed(
                        eventId = eventId,
                        timestamp = ts,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                        retryState = a.retryState.name,
                        newExecutionRunId = a.newExecutionRunId.ifEmpty { null },
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT -> {
                    val a = proto.workflowExecutionTimedOutEventAttributes
                    WorkflowExecutionTimedOut(
                        eventId = eventId,
                        timestamp = ts,
                        retryState = a.retryState.name,
                        newExecutionRunId = a.newExecutionRunId.ifEmpty { null },
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED -> {
                    WorkflowExecutionCanceled(eventId = eventId, timestamp = ts)
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED -> {
                    val a = proto.workflowExecutionTerminatedEventAttributes
                    WorkflowExecutionTerminated(
                        eventId = eventId,
                        timestamp = ts,
                        reason = a.reason.ifEmpty { null },
                        identity = a.identity,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW -> {
                    val a = proto.workflowExecutionContinuedAsNewEventAttributes
                    WorkflowExecutionContinuedAsNew(
                        eventId = eventId,
                        timestamp = ts,
                        newExecutionRunId = a.newExecutionRunId,
                        workflowType = a.workflowType.name,
                        taskQueue = a.taskQueue.name,
                        workflowRunTimeout = if (a.hasWorkflowRunTimeout()) a.workflowRunTimeout.toKt() else null,
                        workflowTaskTimeout = if (a.hasWorkflowTaskTimeout()) a.workflowTaskTimeout.toKt() else null,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED -> {
                    val a = proto.workflowExecutionSignaledEventAttributes
                    WorkflowExecutionSignaled(
                        eventId = eventId,
                        timestamp = ts,
                        signalName = a.signalName,
                        identity = a.identity,
                        externalWorkflowId =
                            if (a.hasExternalWorkflowExecution()) {
                                a.externalWorkflowExecution.workflowId
                                    .ifEmpty {
                                        null
                                    }
                            } else {
                                null
                            },
                        externalRunId =
                            if (a.hasExternalWorkflowExecution()) {
                                a.externalWorkflowExecution.runId.ifEmpty {
                                    null
                                }
                            } else {
                                null
                            },
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED -> {
                    val a = proto.workflowExecutionCancelRequestedEventAttributes
                    WorkflowExecutionCancelRequested(
                        eventId = eventId,
                        timestamp = ts,
                        cause = a.cause,
                        identity = a.identity,
                        externalWorkflowId =
                            if (a.hasExternalWorkflowExecution()) {
                                a.externalWorkflowExecution.workflowId
                                    .ifEmpty {
                                        null
                                    }
                            } else {
                                null
                            },
                        externalRunId =
                            if (a.hasExternalWorkflowExecution()) {
                                a.externalWorkflowExecution.runId.ifEmpty {
                                    null
                                }
                            } else {
                                null
                            },
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_PAUSED -> {
                    val a = proto.workflowExecutionPausedEventAttributes
                    WorkflowExecutionPaused(
                        eventId = eventId,
                        timestamp = ts,
                        identity = a.identity,
                        reason = a.reason,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UNPAUSED -> {
                    val a = proto.workflowExecutionUnpausedEventAttributes
                    WorkflowExecutionUnpaused(
                        eventId = eventId,
                        timestamp = ts,
                        identity = a.identity,
                        reason = a.reason,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED -> {
                    val a = proto.workflowExecutionOptionsUpdatedEventAttributes
                    WorkflowExecutionOptionsUpdated(
                        eventId = eventId,
                        timestamp = ts,
                        identity = a.identity,
                    )
                }

                // ---- Workflow Execution Updates ----

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED -> {
                    WorkflowExecutionUpdateAdmitted(eventId = eventId, timestamp = ts)
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED -> {
                    val a = proto.workflowExecutionUpdateAcceptedEventAttributes
                    WorkflowExecutionUpdateAccepted(
                        eventId = eventId,
                        timestamp = ts,
                        protocolInstanceId = a.protocolInstanceId,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_REJECTED -> {
                    val a = proto.workflowExecutionUpdateRejectedEventAttributes
                    WorkflowExecutionUpdateRejected(
                        eventId = eventId,
                        timestamp = ts,
                        protocolInstanceId = a.protocolInstanceId,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED -> {
                    val a = proto.workflowExecutionUpdateCompletedEventAttributes
                    WorkflowExecutionUpdateCompleted(
                        eventId = eventId,
                        timestamp = ts,
                        acceptedEventId = a.acceptedEventId,
                    )
                }

                // ---- Workflow Task Events ----

                EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED -> {
                    val a = proto.workflowTaskScheduledEventAttributes
                    WorkflowTaskScheduled(
                        eventId = eventId,
                        timestamp = ts,
                        taskQueue = a.taskQueue.name,
                        attempt = a.attempt,
                        startToCloseTimeout = if (a.hasStartToCloseTimeout()) a.startToCloseTimeout.toKt() else null,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED -> {
                    val a = proto.workflowTaskStartedEventAttributes
                    WorkflowTaskStarted(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        identity = a.identity,
                        requestId = a.requestId,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED -> {
                    val a = proto.workflowTaskCompletedEventAttributes
                    WorkflowTaskCompleted(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        startedEventId = a.startedEventId,
                        identity = a.identity,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT -> {
                    val a = proto.workflowTaskTimedOutEventAttributes
                    WorkflowTaskTimedOut(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        startedEventId = a.startedEventId,
                    )
                }

                EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED -> {
                    val a = proto.workflowTaskFailedEventAttributes
                    WorkflowTaskFailed(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        startedEventId = a.startedEventId,
                        cause = a.cause.name,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                        identity = a.identity,
                    )
                }

                // ---- Activity Task Events ----

                EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> {
                    val a = proto.activityTaskScheduledEventAttributes
                    ActivityTaskScheduled(
                        eventId = eventId,
                        timestamp = ts,
                        activityId = a.activityId,
                        activityType = a.activityType.name,
                        taskQueue = a.taskQueue.name,
                        input =
                            if (a.hasInput()) {
                                codec.safeDecode(EncodedTemporalPayloads(a.input))
                            } else {
                                null
                            },
                        scheduleToCloseTimeout =
                            if (a.hasScheduleToCloseTimeout()) a.scheduleToCloseTimeout.toKt() else null,
                        scheduleToStartTimeout =
                            if (a.hasScheduleToStartTimeout()) a.scheduleToStartTimeout.toKt() else null,
                        startToCloseTimeout =
                            if (a.hasStartToCloseTimeout()) a.startToCloseTimeout.toKt() else null,
                        heartbeatTimeout =
                            if (a.hasHeartbeatTimeout()) a.heartbeatTimeout.toKt() else null,
                    )
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED -> {
                    val a = proto.activityTaskStartedEventAttributes
                    ActivityTaskStarted(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        identity = a.identity,
                        requestId = a.requestId,
                        attempt = a.attempt,
                        lastFailureMessage = if (a.hasLastFailure()) a.lastFailure.message.ifEmpty { null } else null,
                    )
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED -> {
                    val a = proto.activityTaskCompletedEventAttributes
                    ActivityTaskCompleted(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        startedEventId = a.startedEventId,
                        identity = a.identity,
                        result =
                            if (a.hasResult()) {
                                codec.safeDecode(EncodedTemporalPayloads(a.result))
                            } else {
                                null
                            },
                    )
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED -> {
                    val a = proto.activityTaskFailedEventAttributes
                    ActivityTaskFailed(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        startedEventId = a.startedEventId,
                        identity = a.identity,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                        retryState = a.retryState.name,
                    )
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT -> {
                    val a = proto.activityTaskTimedOutEventAttributes
                    ActivityTaskTimedOut(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        startedEventId = a.startedEventId,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                        retryState = a.retryState.name,
                    )
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED -> {
                    val a = proto.activityTaskCancelRequestedEventAttributes
                    ActivityTaskCancelRequested(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                    )
                }

                EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED -> {
                    val a = proto.activityTaskCanceledEventAttributes
                    ActivityTaskCanceled(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        startedEventId = a.startedEventId,
                        identity = a.identity,
                    )
                }

                // ---- Timer Events ----

                EventType.EVENT_TYPE_TIMER_STARTED -> {
                    val a = proto.timerStartedEventAttributes
                    TimerStarted(
                        eventId = eventId,
                        timestamp = ts,
                        timerId = a.timerId,
                        startToFireTimeout = if (a.hasStartToFireTimeout()) a.startToFireTimeout.toKt() else null,
                    )
                }

                EventType.EVENT_TYPE_TIMER_FIRED -> {
                    val a = proto.timerFiredEventAttributes
                    TimerFired(
                        eventId = eventId,
                        timestamp = ts,
                        timerId = a.timerId,
                        startedEventId = a.startedEventId,
                    )
                }

                EventType.EVENT_TYPE_TIMER_CANCELED -> {
                    val a = proto.timerCanceledEventAttributes
                    TimerCanceled(
                        eventId = eventId,
                        timestamp = ts,
                        timerId = a.timerId,
                        startedEventId = a.startedEventId,
                        identity = a.identity,
                    )
                }

                // ---- Child Workflow Events ----

                EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED -> {
                    val a = proto.startChildWorkflowExecutionInitiatedEventAttributes
                    StartChildWorkflowExecutionInitiated(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowId,
                        workflowType = a.workflowType.name,
                        taskQueue = a.taskQueue.name,
                        workflowExecutionTimeout =
                            if (a.hasWorkflowExecutionTimeout()) {
                                a.workflowExecutionTimeout
                                    .toKt()
                            } else {
                                null
                            },
                        workflowRunTimeout = if (a.hasWorkflowRunTimeout()) a.workflowRunTimeout.toKt() else null,
                        workflowTaskTimeout = if (a.hasWorkflowTaskTimeout()) a.workflowTaskTimeout.toKt() else null,
                    )
                }

                EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED -> {
                    val a = proto.startChildWorkflowExecutionFailedEventAttributes
                    StartChildWorkflowExecutionFailed(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowId,
                        workflowType = a.workflowType.name,
                        cause = a.cause.name,
                        initiatedEventId = a.initiatedEventId,
                    )
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED -> {
                    val a = proto.childWorkflowExecutionStartedEventAttributes
                    ChildWorkflowExecutionStarted(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId,
                        workflowType = a.workflowType.name,
                        initiatedEventId = a.initiatedEventId,
                    )
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED -> {
                    val a = proto.childWorkflowExecutionCompletedEventAttributes
                    ChildWorkflowExecutionCompleted(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId,
                        workflowType = a.workflowType.name,
                        initiatedEventId = a.initiatedEventId,
                        startedEventId = a.startedEventId,
                    )
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED -> {
                    val a = proto.childWorkflowExecutionFailedEventAttributes
                    ChildWorkflowExecutionFailed(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId,
                        workflowType = a.workflowType.name,
                        initiatedEventId = a.initiatedEventId,
                        startedEventId = a.startedEventId,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                        retryState = a.retryState.name,
                    )
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED -> {
                    val a = proto.childWorkflowExecutionCanceledEventAttributes
                    ChildWorkflowExecutionCanceled(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId,
                        workflowType = a.workflowType.name,
                        initiatedEventId = a.initiatedEventId,
                        startedEventId = a.startedEventId,
                    )
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED -> {
                    val a = proto.childWorkflowExecutionTerminatedEventAttributes
                    ChildWorkflowExecutionTerminated(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId,
                        workflowType = a.workflowType.name,
                        initiatedEventId = a.initiatedEventId,
                        startedEventId = a.startedEventId,
                    )
                }

                EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT -> {
                    val a = proto.childWorkflowExecutionTimedOutEventAttributes
                    ChildWorkflowExecutionTimedOut(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId,
                        workflowType = a.workflowType.name,
                        initiatedEventId = a.initiatedEventId,
                        startedEventId = a.startedEventId,
                        retryState = a.retryState.name,
                    )
                }

                // ---- External Workflow Events ----

                EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED -> {
                    val a = proto.signalExternalWorkflowExecutionInitiatedEventAttributes
                    SignalExternalWorkflowExecutionInitiated(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId.ifEmpty { null },
                        signalName = a.signalName,
                    )
                }

                EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED -> {
                    val a = proto.signalExternalWorkflowExecutionFailedEventAttributes
                    SignalExternalWorkflowExecutionFailed(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId.ifEmpty { null },
                        cause = a.cause.name,
                        initiatedEventId = a.initiatedEventId,
                    )
                }

                EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED -> {
                    val a = proto.externalWorkflowExecutionSignaledEventAttributes
                    ExternalWorkflowExecutionSignaled(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId.ifEmpty { null },
                        initiatedEventId = a.initiatedEventId,
                    )
                }

                EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED -> {
                    val a = proto.requestCancelExternalWorkflowExecutionInitiatedEventAttributes
                    RequestCancelExternalWorkflowExecutionInitiated(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId.ifEmpty { null },
                        reason = a.reason.ifEmpty { null },
                    )
                }

                EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED -> {
                    val a = proto.requestCancelExternalWorkflowExecutionFailedEventAttributes
                    RequestCancelExternalWorkflowExecutionFailed(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId.ifEmpty { null },
                        cause = a.cause.name,
                        initiatedEventId = a.initiatedEventId,
                    )
                }

                EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED -> {
                    val a = proto.externalWorkflowExecutionCancelRequestedEventAttributes
                    ExternalWorkflowExecutionCancelRequested(
                        eventId = eventId,
                        timestamp = ts,
                        namespace = a.namespace,
                        workflowId = a.workflowExecution.workflowId,
                        runId = a.workflowExecution.runId.ifEmpty { null },
                        initiatedEventId = a.initiatedEventId,
                    )
                }

                // ---- Marker / Search Attributes / Properties ----

                EventType.EVENT_TYPE_MARKER_RECORDED -> {
                    val a = proto.markerRecordedEventAttributes
                    MarkerRecorded(
                        eventId = eventId,
                        timestamp = ts,
                        markerName = a.markerName,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                    )
                }

                EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES -> {
                    UpsertWorkflowSearchAttributes(eventId = eventId, timestamp = ts)
                }

                EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED -> {
                    WorkflowPropertiesModified(eventId = eventId, timestamp = ts)
                }

                EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED_EXTERNALLY -> {
                    val a = proto.workflowPropertiesModifiedExternallyEventAttributes
                    WorkflowPropertiesModifiedExternally(
                        eventId = eventId,
                        timestamp = ts,
                        newTaskQueue = a.newTaskQueue.ifEmpty { null },
                        newWorkflowTaskTimeout =
                            if (a.hasNewWorkflowTaskTimeout()) a.newWorkflowTaskTimeout.toKt() else null,
                        newWorkflowRunTimeout =
                            if (a.hasNewWorkflowRunTimeout()) a.newWorkflowRunTimeout.toKt() else null,
                        newWorkflowExecutionTimeout =
                            if (a.hasNewWorkflowExecutionTimeout()) {
                                a.newWorkflowExecutionTimeout
                                    .toKt()
                            } else {
                                null
                            },
                    )
                }

                EventType.EVENT_TYPE_ACTIVITY_PROPERTIES_MODIFIED_EXTERNALLY -> {
                    val a = proto.activityPropertiesModifiedExternallyEventAttributes
                    ActivityPropertiesModifiedExternally(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                    )
                }

                // ---- Nexus Operation Events ----

                EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED -> {
                    val a = proto.nexusOperationScheduledEventAttributes
                    NexusOperationScheduled(
                        eventId = eventId,
                        timestamp = ts,
                        endpoint = a.endpoint,
                        service = a.service,
                        operation = a.operation,
                        requestId = a.requestId,
                        scheduleToCloseTimeout =
                            if (a.hasScheduleToCloseTimeout()) a.scheduleToCloseTimeout.toKt() else null,
                    )
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED -> {
                    val a = proto.nexusOperationStartedEventAttributes
                    NexusOperationStarted(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        requestId = a.requestId,
                        operationToken = a.operationToken,
                    )
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED -> {
                    val a = proto.nexusOperationCompletedEventAttributes
                    NexusOperationCompleted(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        requestId = a.requestId,
                    )
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED -> {
                    val a = proto.nexusOperationFailedEventAttributes
                    NexusOperationFailed(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                        requestId = a.requestId,
                    )
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED -> {
                    val a = proto.nexusOperationCanceledEventAttributes
                    NexusOperationCanceled(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                        requestId = a.requestId,
                    )
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT -> {
                    val a = proto.nexusOperationTimedOutEventAttributes
                    NexusOperationTimedOut(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                        requestId = a.requestId,
                    )
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED -> {
                    val a = proto.nexusOperationCancelRequestedEventAttributes
                    NexusOperationCancelRequested(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                    )
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED -> {
                    val a = proto.nexusOperationCancelRequestCompletedEventAttributes
                    NexusOperationCancelRequestCompleted(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                    )
                }

                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_FAILED -> {
                    val a = proto.nexusOperationCancelRequestFailedEventAttributes
                    NexusOperationCancelRequestFailed(
                        eventId = eventId,
                        timestamp = ts,
                        scheduledEventId = a.scheduledEventId,
                        failureMessage = if (a.hasFailure()) a.failure.message.ifEmpty { null } else null,
                    )
                }

                // ---- Fallback ----

                else -> {
                    Unknown(
                        eventId = eventId,
                        eventType = TemporalEventType.fromProto(proto.eventType),
                        timestamp = ts,
                    )
                }
            }
        }
    }
}

private fun com.google.protobuf.Duration.toKt(): Duration =
    java.time.Duration
        .ofSeconds(this.seconds, this.nanos.toLong())
        .toKotlinDuration()
