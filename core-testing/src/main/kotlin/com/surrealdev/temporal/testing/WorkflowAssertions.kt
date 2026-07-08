package com.surrealdev.temporal.testing

import com.surrealdev.temporal.client.WorkflowHandle
import com.surrealdev.temporal.client.history.WorkflowHistory
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * DSL for making assertions about workflow execution history.
 *
 * Example usage:
 * ```kotlin
 * handle.assertHistory {
 *     completed()
 *     hasTimerStarted()
 *     hasTimerFired()
 *     timerCount(1)
 *     noFailedActivities()
 * }
 * ```
 */
class HistoryAssertionScope(
    val history: WorkflowHistory,
) {
    private val errors = mutableListOf<String>()

    /**
     * Asserts that the workflow completed successfully.
     */
    fun completed() {
        if (!history.isCompleted) {
            errors.add("Expected workflow to be completed, but it was not")
        }
    }

    /**
     * Asserts that the workflow failed.
     */
    fun failed() {
        if (!history.isFailed) {
            errors.add("Expected workflow to be failed, but it was not")
        }
    }

    /**
     * Asserts that the workflow was canceled.
     */
    fun canceled() {
        if (!history.isCanceled) {
            errors.add("Expected workflow to be canceled, but it was not")
        }
    }

    /**
     * Asserts that the workflow was terminated.
     */
    fun terminated() {
        if (!history.isTerminated) {
            errors.add("Expected workflow to be terminated, but it was not")
        }
    }

    /**
     * Asserts that the workflow timed out.
     */
    fun timedOut() {
        if (!history.isTimedOut) {
            errors.add("Expected workflow to be timed out, but it was not")
        }
    }

    /**
     * Asserts that at least one timer was started.
     */
    fun hasTimerStarted() {
        if (!history.hasTimerStarted()) {
            errors.add("Expected at least one timer to be started, but none were")
        }
    }

    /**
     * Asserts that at least one timer has fired.
     */
    fun hasTimerFired() {
        if (!history.hasTimerFired()) {
            errors.add("Expected at least one timer to have fired, but none did")
        }
    }

    /**
     * Asserts the exact number of timers started.
     */
    fun timerCount(expected: Int) {
        val actual = history.timerCount()
        if (actual != expected) {
            errors.add("Expected $expected timer(s) to be started, but found $actual")
        }
    }

    /**
     * Asserts that at least one activity was scheduled.
     */
    fun hasActivityScheduled() {
        if (!history.hasActivityScheduled()) {
            errors.add("Expected at least one activity to be scheduled, but none were")
        }
    }

    /**
     * Asserts that at least one activity completed successfully.
     */
    fun hasActivityCompleted() {
        if (!history.hasActivityCompleted()) {
            errors.add("Expected at least one activity to have completed, but none did")
        }
    }

    /**
     * Asserts that an activity of the specified type completed.
     */
    fun hasActivityCompleted(activityType: String) {
        if (!history.hasActivityCompleted(activityType)) {
            errors.add("Expected activity '$activityType' to have completed, but it did not")
        }
    }

    /**
     * Asserts the exact number of activities scheduled.
     */
    fun activityCount(expected: Int) {
        val actual = history.activityCount()
        if (actual != expected) {
            errors.add("Expected $expected activity(ies) to be scheduled, but found $actual")
        }
    }

    /**
     * Asserts that no activities failed.
     */
    fun noFailedActivities() {
        if (!history.noFailedActivities()) {
            val failedCount = history.activityFailedEvents().size
            errors.add("Expected no activities to fail, but $failedCount failed")
        }
    }

    /**
     * Asserts that at least one activity failed.
     */
    fun hasActivityFailed() {
        if (!history.hasActivityFailed()) {
            errors.add("Expected at least one activity to have failed, but none did")
        }
    }

    /**
     * Asserts that at least one signal was received.
     */
    fun hasSignal() {
        if (!history.hasSignal()) {
            errors.add("Expected at least one signal to have been received, but none were")
        }
    }

    /**
     * Asserts that a signal with the specified name was received.
     */
    fun hasSignal(signalName: String) {
        if (!history.hasSignal(signalName)) {
            errors.add("Expected signal '$signalName' to have been received, but it was not")
        }
    }

    /**
     * Asserts that at least one child workflow was started.
     */
    fun hasChildWorkflowStarted() {
        if (!history.hasChildWorkflowStarted()) {
            errors.add("Expected at least one child workflow to be started, but none were")
        }
    }

    /**
     * Asserts that at least one child workflow completed.
     */
    fun hasChildWorkflowCompleted() {
        if (!history.hasChildWorkflowCompleted()) {
            errors.add("Expected at least one child workflow to have completed, but none did")
        }
    }

    /**
     * Custom assertion on the history.
     */
    fun check(
        message: String,
        predicate: (WorkflowHistory) -> Boolean,
    ) {
        if (!predicate(history)) {
            errors.add(message)
        }
    }

    /**
     * Validates all assertions and throws if any failed.
     */
    internal fun validate() {
        if (errors.isNotEmpty()) {
            throw AssertionError(
                "Workflow history assertions failed:\n" +
                    errors.joinToString("\n") { "  - $it" },
            )
        }
    }
}

/**
 * Asserts that the workflow history matches the given assertions.
 *
 * Example:
 * ```kotlin
 * val handle = client.startWorkflow<String>(...)
 * handle.result(timeout = 30.seconds)
 *
 * handle.assertHistory {
 *     completed()
 *     hasTimerStarted()
 *     hasTimerFired()
 *     timerCount(1)
 * }
 * ```
 */
suspend fun WorkflowHandle.assertHistory(assertions: HistoryAssertionScope.() -> Unit) {
    val history = getHistory()
    val scope = HistoryAssertionScope(history)
    scope.assertions()
    scope.validate()
}

/**
 * Gets the workflow history and runs assertions on it.
 * Returns the history for further inspection if needed.
 *
 * Example:
 * ```kotlin
 * val history = handle.assertHistoryAndReturn {
 *     completed()
 *     hasActivityCompleted()
 * }
 * // Can further inspect history if needed
 * println("Workflow type: ${history.workflowType}")
 * ```
 */
suspend fun WorkflowHandle.assertHistoryAndReturn(assertions: HistoryAssertionScope.() -> Unit): WorkflowHistory {
    val history = getHistory()
    val scope = HistoryAssertionScope(history)
    scope.assertions()
    scope.validate()
    return history
}

/**
 * Polls the workflow history until [predicate] matches, returning the matching history.
 *
 * Use this to await a non-terminal history condition (e.g. a WorkflowTaskFailed event
 * appearing while the workflow keeps running) where [WorkflowHandle.result]'s close-event
 * long poll doesn't apply.
 *
 * Example:
 * ```kotlin
 * val history = handle.awaitHistory(description = "a WorkflowTaskFailed event") {
 *     it.filterByType<TemporalHistoryEvent.WorkflowTaskFailed>().isNotEmpty()
 * }
 * ```
 *
 * @param timeout Maximum time to wait before failing the test
 * @param pollInterval Delay between history fetches
 * @param description Shown in the failure message when the timeout elapses
 * @throws AssertionError if the predicate doesn't match within [timeout]
 */
suspend fun WorkflowHandle.awaitHistory(
    timeout: Duration = 10.seconds,
    pollInterval: Duration = 200.milliseconds,
    description: String = "history condition",
    predicate: (WorkflowHistory) -> Boolean,
): WorkflowHistory {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    var last: WorkflowHistory? = null
    while (deadline.hasNotPassedNow()) {
        val history = getHistory()
        last = history
        if (predicate(history)) return history
        delay(pollInterval)
    }
    throw AssertionError(
        "Timed out after $timeout waiting for $description. " +
            "Last seen events: ${last?.events?.map { it::class.simpleName }}",
    )
}
