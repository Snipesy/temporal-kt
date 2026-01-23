package com.surrealdev.temporal.workflow

/**
 * Handle to a scheduled or running activity.
 *
 * Obtain a handle by calling [WorkflowContext.startActivityWithPayloads] or related extension functions.
 * Use [result] to await the activity result, or [cancel] to request cancellation.
 *
 * @param R The result type of the activity
 */
interface ActivityHandle<R> {
    /**
     * The activity ID assigned to this activity.
     */
    val activityId: String

    /**
     * Whether the activity has completed (successfully, failed, or cancelled).
     * Once true, [result] will return immediately and [exceptionOrNull] can be checked.
     */
    val isDone: Boolean

    /**
     * Returns true if a cancellation has been requested for this activity.
     * Note: This doesn't mean the activity is cancelled, just that cancellation was requested.
     */
    val isCancellationRequested: Boolean

    /**
     * Waits for the activity to complete and returns its result.
     *
     * @return The result of the activity
     * @throws ActivityFailureException if the activity failed
     * @throws ActivityCancelledException if the activity was cancelled
     * @throws ActivityTimeoutException if the activity timed out
     */
    suspend fun result(): R

    /**
     * Returns the exception if the activity completed exceptionally, or null if
     * the activity succeeded or is still running.
     *
     * Use [isDone] first to check if the activity has completed.
     *
     * @return The exception if failed/cancelled/timed out, or null otherwise
     */
    fun exceptionOrNull(): ActivityException?

    /**
     * Requests cancellation of this activity.
     *
     * The behavior depends on the [ActivityCancellationType] set in options:
     * - TRY_CANCEL: Sends cancel request, Core SDK immediately resolves with cancelled status
     * - WAIT_CANCELLATION_COMPLETED: Sends cancel request, Core SDK waits for ActivityTaskCanceled
     *   event before resolving - so [result] will block until activity acknowledges cancellation
     * - ABANDON: Does nothing (activity continues, result() returns immediately with cancellation)
     *
     * In all cases, this method returns immediately. The difference is when [result] returns:
     * - TRY_CANCEL: result() throws immediately when Core SDK resolves
     * - WAIT_CANCELLATION_COMPLETED: result() blocks until activity heartbeats and acknowledges
     * - ABANDON: result() throws immediately (local cancellation)
     *
     * Calling cancel() multiple times is idempotent.
     *
     * @param reason Optional reason for cancellation (for debugging)
     */
    fun cancel(reason: String = "Cancelled by workflow")
}
