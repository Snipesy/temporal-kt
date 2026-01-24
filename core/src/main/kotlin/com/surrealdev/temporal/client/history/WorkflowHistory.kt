package com.surrealdev.temporal.client.history

import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.HistoryEvent

/**
 * Wrapper around workflow execution history providing convenient query methods.
 *
 * This class is primarily useful for testing, allowing assertions about
 * what happened during workflow execution (timers fired, activities completed, etc.).
 *
 * Example usage:
 * ```kotlin
 * val history = handle.getHistory()
 *
 * // Check completion state
 * assertTrue(history.isCompleted)
 * assertFalse(history.isFailed)
 *
 * // Check for timers
 * assertTrue(history.hasTimerFired())
 * assertEquals(2, history.timerStartedEvents().size)
 *
 * // Check for activities
 * assertTrue(history.hasActivityCompleted("ProcessPayment"))
 * ```
 *
 * @property workflowId The workflow ID this history belongs to.
 * @property runId The run ID of the execution.
 * @property events The raw list of history events.
 */
class WorkflowHistory(
    val workflowId: String,
    val runId: String?,
    val events: List<HistoryEvent>,
) {
    companion object {
        /**
         * Creates a [WorkflowHistory] from a protobuf [History] message.
         */
        fun fromProto(
            workflowId: String,
            runId: String?,
            history: History,
        ): WorkflowHistory =
            WorkflowHistory(
                workflowId = workflowId,
                runId = runId,
                events = history.eventsList,
            )
    }

    // ========== Completion State ==========

    /**
     * Whether the workflow completed successfully.
     */
    val isCompleted: Boolean
        get() = completedEvent != null

    /**
     * Whether the workflow failed.
     */
    val isFailed: Boolean
        get() = failedEvent != null

    /**
     * Whether the workflow was canceled.
     */
    val isCanceled: Boolean
        get() = canceledEvent != null

    /**
     * Whether the workflow was terminated.
     */
    val isTerminated: Boolean
        get() = terminatedEvent != null

    /**
     * Whether the workflow timed out.
     */
    val isTimedOut: Boolean
        get() = timedOutEvent != null

    /**
     * Whether the workflow continued as new.
     */
    val isContinuedAsNew: Boolean
        get() = continuedAsNewEvent != null

    /**
     * The workflow completed event, if the workflow completed successfully.
     */
    val completedEvent: HistoryEvent?
        get() = events.find { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED }

    /**
     * The workflow failed event, if the workflow failed.
     */
    val failedEvent: HistoryEvent?
        get() = events.find { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED }

    /**
     * The workflow canceled event, if the workflow was canceled.
     */
    val canceledEvent: HistoryEvent?
        get() = events.find { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED }

    /**
     * The workflow terminated event, if the workflow was terminated.
     */
    val terminatedEvent: HistoryEvent?
        get() = events.find { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED }

    /**
     * The workflow timed out event, if the workflow timed out.
     */
    val timedOutEvent: HistoryEvent?
        get() = events.find { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT }

    /**
     * The continued as new event, if the workflow continued as new.
     */
    val continuedAsNewEvent: HistoryEvent?
        get() = events.find { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW }

    // ========== Timer Events ==========

    /**
     * Returns all timer started events.
     */
    fun timerStartedEvents(): List<HistoryEvent> = events.filter { it.eventType == EventType.EVENT_TYPE_TIMER_STARTED }

    /**
     * Returns all timer fired events.
     */
    fun timerFiredEvents(): List<HistoryEvent> = events.filter { it.eventType == EventType.EVENT_TYPE_TIMER_FIRED }

    /**
     * Returns all timer canceled events.
     */
    fun timerCanceledEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_TIMER_CANCELED }

    /**
     * Whether any timer has been started.
     */
    fun hasTimerStarted(): Boolean = timerStartedEvents().isNotEmpty()

    /**
     * Whether any timer has fired.
     */
    fun hasTimerFired(): Boolean = timerFiredEvents().isNotEmpty()

    /**
     * Returns the count of timer started events.
     */
    fun timerCount(): Int = timerStartedEvents().size

    // ========== Activity Events ==========

    /**
     * Returns all activity task scheduled events.
     */
    fun activityScheduledEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }

    /**
     * Returns all activity task started events.
     */
    fun activityStartedEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED }

    /**
     * Returns all activity task completed events.
     */
    fun activityCompletedEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED }

    /**
     * Returns all activity task failed events.
     */
    fun activityFailedEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED }

    /**
     * Returns all activity task timed out events.
     */
    fun activityTimedOutEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT }

    /**
     * Returns all activity task canceled events.
     */
    fun activityCanceledEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED }

    /**
     * Whether any activity has been scheduled.
     */
    fun hasActivityScheduled(): Boolean = activityScheduledEvents().isNotEmpty()

    /**
     * Whether any activity has completed successfully.
     */
    fun hasActivityCompleted(): Boolean = activityCompletedEvents().isNotEmpty()

    /**
     * Whether any activity has failed.
     */
    fun hasActivityFailed(): Boolean = activityFailedEvents().isNotEmpty()

    /**
     * Whether no activities have failed.
     */
    fun noFailedActivities(): Boolean = activityFailedEvents().isEmpty()

    /**
     * Returns the count of activity scheduled events.
     */
    fun activityCount(): Int = activityScheduledEvents().size

    /**
     * Returns activity scheduled events for a specific activity type.
     */
    fun activityScheduledEventsByType(activityType: String): List<HistoryEvent> =
        activityScheduledEvents().filter {
            it.activityTaskScheduledEventAttributes.activityType.name == activityType
        }

    /**
     * Returns activity completed events for activities of a specific type.
     * Note: This requires correlating completed events with their scheduled events via scheduledEventId.
     */
    fun activityCompletedEventsByType(activityType: String): List<HistoryEvent> {
        val scheduledIds = activityScheduledEventsByType(activityType).map { it.eventId }.toSet()
        return activityCompletedEvents().filter {
            it.activityTaskCompletedEventAttributes.scheduledEventId in scheduledIds
        }
    }

    /**
     * Whether an activity of the specified type has completed.
     */
    fun hasActivityCompleted(activityType: String): Boolean = activityCompletedEventsByType(activityType).isNotEmpty()

    // ========== Signal Events ==========

    /**
     * Returns all workflow execution signaled events.
     */
    fun signaledEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED }

    /**
     * Returns signaled events for a specific signal name.
     */
    fun signalsByName(signalName: String): List<HistoryEvent> =
        signaledEvents().filter {
            it.workflowExecutionSignaledEventAttributes.signalName == signalName
        }

    /**
     * Whether any signal has been received.
     */
    fun hasSignal(): Boolean = signaledEvents().isNotEmpty()

    /**
     * Whether a signal with the specified name has been received.
     */
    fun hasSignal(signalName: String): Boolean = signalsByName(signalName).isNotEmpty()

    // ========== Child Workflow Events ==========

    /**
     * Returns all child workflow execution started events.
     */
    fun childWorkflowStartedEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED }

    /**
     * Returns all child workflow execution completed events.
     */
    fun childWorkflowCompletedEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED }

    /**
     * Returns all child workflow execution failed events.
     */
    fun childWorkflowFailedEvents(): List<HistoryEvent> =
        events.filter { it.eventType == EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED }

    /**
     * Whether any child workflow has been started.
     */
    fun hasChildWorkflowStarted(): Boolean = childWorkflowStartedEvents().isNotEmpty()

    /**
     * Whether any child workflow has completed.
     */
    fun hasChildWorkflowCompleted(): Boolean = childWorkflowCompletedEvents().isNotEmpty()

    // ========== Generic Filtering ==========

    /**
     * Filters events by one or more event types.
     */
    fun filterByType(vararg types: EventType): List<HistoryEvent> = events.filter { it.eventType in types }

    /**
     * Returns the first event, which is always the workflow execution started event.
     */
    val workflowExecutionStartedEvent: HistoryEvent
        get() = events.first { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED }

    /**
     * Returns the workflow type name from the started event.
     */
    val workflowType: String
        get() = workflowExecutionStartedEvent.workflowExecutionStartedEventAttributes.workflowType.name

    /**
     * Returns the task queue name from the started event.
     */
    val taskQueue: String
        get() = workflowExecutionStartedEvent.workflowExecutionStartedEventAttributes.taskQueue.name

    override fun toString(): String = "WorkflowHistory(workflowId=$workflowId, runId=$runId, events=${events.size})"
}
