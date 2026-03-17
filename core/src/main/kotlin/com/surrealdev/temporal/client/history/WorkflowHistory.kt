package com.surrealdev.temporal.client.history

import io.temporal.api.history.v1.History

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
 *
 * // Access typed event data
 * val scheduled = history.activityScheduledEvents()
 * println(scheduled.first().activityType) // "ProcessPayment"
 * println(scheduled.first().taskQueue)    // "my-queue"
 * ```
 *
 * @property workflowId The workflow ID this history belongs to.
 * @property runId The run ID of the execution.
 * @property events The list of history events.
 */
class WorkflowHistory(
    val workflowId: String,
    val runId: String?,
    val events: List<TemporalHistoryEvent>,
) {
    companion object {
        /**
         * Creates a [WorkflowHistory] from a protobuf [History] message.
         */
        internal fun fromProto(
            workflowId: String,
            runId: String?,
            history: History,
        ): WorkflowHistory =
            WorkflowHistory(
                workflowId = workflowId,
                runId = runId,
                events = history.eventsList.map { TemporalHistoryEvent.fromProto(it) },
            )
    }

    /**
     * Filters events by their concrete sealed class type.
     *
     * Example:
     * ```kotlin
     * val timers = history.filterByType<TemporalHistoryEvent.TimerStarted>()
     * ```
     */
    inline fun <reified T : TemporalHistoryEvent> filterByType(): List<T> = events.filterIsInstance<T>()

    // ========== Completion State ==========

    val isCompleted: Boolean get() = completedEvent != null
    val isFailed: Boolean get() = failedEvent != null
    val isCanceled: Boolean get() = canceledEvent != null
    val isTerminated: Boolean get() = terminatedEvent != null
    val isTimedOut: Boolean get() = timedOutEvent != null
    val isContinuedAsNew: Boolean get() = continuedAsNewEvent != null

    val completedEvent: TemporalHistoryEvent.WorkflowExecutionCompleted?
        get() = events.filterIsInstance<TemporalHistoryEvent.WorkflowExecutionCompleted>().firstOrNull()

    val failedEvent: TemporalHistoryEvent.WorkflowExecutionFailed?
        get() = events.filterIsInstance<TemporalHistoryEvent.WorkflowExecutionFailed>().firstOrNull()

    val canceledEvent: TemporalHistoryEvent.WorkflowExecutionCanceled?
        get() = events.filterIsInstance<TemporalHistoryEvent.WorkflowExecutionCanceled>().firstOrNull()

    val terminatedEvent: TemporalHistoryEvent.WorkflowExecutionTerminated?
        get() = events.filterIsInstance<TemporalHistoryEvent.WorkflowExecutionTerminated>().firstOrNull()

    val timedOutEvent: TemporalHistoryEvent.WorkflowExecutionTimedOut?
        get() = events.filterIsInstance<TemporalHistoryEvent.WorkflowExecutionTimedOut>().firstOrNull()

    val continuedAsNewEvent: TemporalHistoryEvent.WorkflowExecutionContinuedAsNew?
        get() = events.filterIsInstance<TemporalHistoryEvent.WorkflowExecutionContinuedAsNew>().firstOrNull()

    // ========== Timer Events ==========

    fun timerStartedEvents(): List<TemporalHistoryEvent.TimerStarted> = filterByType()

    fun timerFiredEvents(): List<TemporalHistoryEvent.TimerFired> = filterByType()

    fun timerCanceledEvents(): List<TemporalHistoryEvent.TimerCanceled> = filterByType()

    fun hasTimerStarted(): Boolean = timerStartedEvents().isNotEmpty()

    fun hasTimerFired(): Boolean = timerFiredEvents().isNotEmpty()

    fun timerCount(): Int = timerStartedEvents().size

    // ========== Activity Events ==========

    fun activityScheduledEvents(): List<TemporalHistoryEvent.ActivityTaskScheduled> = filterByType()

    fun activityStartedEvents(): List<TemporalHistoryEvent.ActivityTaskStarted> = filterByType()

    fun activityCompletedEvents(): List<TemporalHistoryEvent.ActivityTaskCompleted> = filterByType()

    fun activityFailedEvents(): List<TemporalHistoryEvent.ActivityTaskFailed> = filterByType()

    fun activityTimedOutEvents(): List<TemporalHistoryEvent.ActivityTaskTimedOut> = filterByType()

    fun activityCanceledEvents(): List<TemporalHistoryEvent.ActivityTaskCanceled> = filterByType()

    fun hasActivityScheduled(): Boolean = activityScheduledEvents().isNotEmpty()

    fun hasActivityCompleted(): Boolean = activityCompletedEvents().isNotEmpty()

    fun hasActivityFailed(): Boolean = activityFailedEvents().isNotEmpty()

    fun noFailedActivities(): Boolean = activityFailedEvents().isEmpty()

    fun activityCount(): Int = activityScheduledEvents().size

    fun activityScheduledEventsByType(activityType: String): List<TemporalHistoryEvent.ActivityTaskScheduled> =
        activityScheduledEvents().filter { it.activityType == activityType }

    fun activityCompletedEventsByType(activityType: String): List<TemporalHistoryEvent.ActivityTaskCompleted> {
        val scheduledIds = activityScheduledEventsByType(activityType).map { it.eventId }.toSet()
        return activityCompletedEvents().filter { it.scheduledEventId in scheduledIds }
    }

    fun hasActivityCompleted(activityType: String): Boolean = activityCompletedEventsByType(activityType).isNotEmpty()

    // ========== Signal Events ==========

    fun signaledEvents(): List<TemporalHistoryEvent.WorkflowExecutionSignaled> = filterByType()

    fun signalsByName(signalName: String): List<TemporalHistoryEvent.WorkflowExecutionSignaled> =
        signaledEvents().filter { it.signalName == signalName }

    fun hasSignal(): Boolean = signaledEvents().isNotEmpty()

    fun hasSignal(signalName: String): Boolean = signalsByName(signalName).isNotEmpty()

    // ========== Child Workflow Events ==========

    fun childWorkflowStartedEvents(): List<TemporalHistoryEvent.ChildWorkflowExecutionStarted> = filterByType()

    fun childWorkflowCompletedEvents(): List<TemporalHistoryEvent.ChildWorkflowExecutionCompleted> = filterByType()

    fun childWorkflowFailedEvents(): List<TemporalHistoryEvent.ChildWorkflowExecutionFailed> = filterByType()

    fun hasChildWorkflowStarted(): Boolean = childWorkflowStartedEvents().isNotEmpty()

    fun hasChildWorkflowCompleted(): Boolean = childWorkflowCompletedEvents().isNotEmpty()

    // ========== Generic Filtering ==========

    /**
     * Filters events by one or more event types.
     */
    fun filterByType(vararg types: TemporalEventType): List<TemporalHistoryEvent> =
        events.filter { it.eventType in types }

    /**
     * Returns the first event, which is always the workflow execution started event.
     */
    val workflowExecutionStartedEvent: TemporalHistoryEvent.WorkflowExecutionStarted
        get() = events.filterIsInstance<TemporalHistoryEvent.WorkflowExecutionStarted>().first()

    /**
     * Returns the workflow type name from the started event.
     */
    val workflowType: String
        get() = workflowExecutionStartedEvent.workflowType

    /**
     * Returns the task queue name from the started event.
     */
    val taskQueue: String
        get() = workflowExecutionStartedEvent.taskQueue

    override fun toString(): String = "WorkflowHistory(workflowId=$workflowId, runId=$runId, events=${events.size})"
}
