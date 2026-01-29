package com.surrealdev.temporal.workflow

/**
 * Base handle interface for scheduled or running activities.
 *
 * This is the common interface shared by both [RemoteActivityHandle] (server-scheduled activities)
 * and [LocalActivityHandle] (local activities that run in the same worker process).
 *
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
     * - WAIT_CANCELLATION_COMPLETED: Sends cancel request, waits for acknowledgment
     * - ABANDON: Does nothing (activity continues, result() returns immediately with cancellation)
     *
     * In all cases, this method returns immediately. The difference is when [result] returns.
     *
     * Calling cancel() multiple times is idempotent.
     *
     * @param reason Optional reason for cancellation (for debugging)
     */
    fun cancel(reason: String = "Cancelled by workflow")
}

/**
 * Handle to a scheduled or running remote (server-scheduled) activity.
 *
 * Obtain a handle by calling [WorkflowContext.startActivityWithPayloads] or related extension functions.
 * Use [result] to await the activity result, or [cancel] to request cancellation.
 *
 * Remote activities are scheduled through the Temporal server, which manages:
 * - Task queue routing to workers
 * - Retry policies and backoff
 * - Heartbeat timeout monitoring
 * - Activity history persistence
 *
 * For short operations that don't need server-side management, consider using
 * [LocalActivityHandle] via `startLocalActivity()` instead.
 *
 * @param R The result type of the activity
 * @see ActivityHandle for common functionality
 * @see LocalActivityHandle for local activities
 */
interface RemoteActivityHandle<R> : ActivityHandle<R>

/**
 * Handle to a scheduled or running local activity.
 *
 * Obtain a handle by calling [WorkflowContext.startLocalActivityWithPayloads] or related extension functions.
 * Use [result] to await the activity result, or [cancel] to request cancellation.
 *
 * Local activities differ from remote activities in that they:
 * - Run in the same worker process as the workflow
 * - Don't support heartbeats (operations should be short)
 * - Use markers for replay instead of re-execution
 * - May receive backoff signals that cause the workflow to schedule a timer before retrying
 *
 * The [result] method handles backoff transparently - if Core SDK signals that retry backoff
 * exceeds the local retry threshold, this handle will automatically schedule a timer,
 * wait, and reschedule the activity. This is hidden from the caller.
 *
 * @param R The result type of the local activity
 * @see ActivityHandle for common functionality
 * @see RemoteActivityHandle for server-scheduled activities
 */
interface LocalActivityHandle<R> : ActivityHandle<R>
