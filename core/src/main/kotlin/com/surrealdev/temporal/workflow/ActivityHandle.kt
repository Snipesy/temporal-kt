package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.common.exceptions.WorkflowActivityException
import com.surrealdev.temporal.serialization.PayloadSerializer

/**
 * Base handle interface for scheduled or running activities.
 *
 * This is the common interface shared by both [RemoteActivityHandle] (server-scheduled activities)
 * and [LocalActivityHandle] (local activities that run in the same worker process).
 *
 * Use [resultPayload] to await the activity result as a raw payload, or use the typed
 * [result] extension function to deserialize to a specific type. Use [cancel] to request cancellation.
 */
interface ActivityHandle {
    /**
     * The activity ID assigned to this activity.
     */
    val activityId: String

    /**
     * Serializer associated with this activity handle.
     * Used for converting values to/from Temporal Payloads.
     */
    val serializer: PayloadSerializer

    /**
     * Whether the activity has completed (successfully, failed, or cancelled).
     * Once true, [resultPayload] will return immediately and [exceptionOrNull] can be checked.
     */
    val isDone: Boolean

    /**
     * Returns true if a cancellation has been requested for this activity.
     * Note: This doesn't mean the activity is cancelled, just that cancellation was requested.
     */
    val isCancellationRequested: Boolean

    /**
     * Waits for the activity to complete and returns its raw result payload.
     *
     * For typed results, use the [result] extension function instead:
     * ```kotlin
     * val result: String = handle.result()
     * ```
     *
     * @return The raw payload result of the activity, or null if empty
     * @throws com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException if the activity failed
     * @throws com.surrealdev.temporal.common.exceptions.WorkflowActivityCancelledException if the activity was cancelled
     * @throws com.surrealdev.temporal.common.exceptions.WorkflowActivityTimeoutException if the activity timed out
     */
    suspend fun resultPayload(): com.surrealdev.temporal.common.TemporalPayload?

    /**
     * Returns the exception if the activity completed exceptionally, or null if
     * the activity succeeded or is still running.
     *
     * Use [isDone] first to check if the activity has completed.
     *
     * @return The exception if failed/cancelled/timed out, or null otherwise
     */
    fun exceptionOrNull(): WorkflowActivityException?

    /**
     * Requests cancellation of this activity.
     *
     * The behavior depends on the [ActivityCancellationType] set in options:
     * - TRY_CANCEL: Sends cancel request, Core SDK immediately resolves with cancelled status
     * - WAIT_CANCELLATION_COMPLETED: Sends cancel request, waits for acknowledgment
     * - ABANDON: Does nothing (activity continues, result() returns immediately with cancellation)
     *
     * In all cases, this method returns immediately. The difference is when [resultPayload] returns.
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
 * Use [resultPayload] to await the raw result, or the typed [result] extension function, or [cancel] to request cancellation.
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
 * @see ActivityHandle for common functionality
 * @see LocalActivityHandle for local activities
 */
interface RemoteActivityHandle : ActivityHandle

/**
 * Handle to a scheduled or running local activity.
 *
 * Obtain a handle by calling [WorkflowContext.startLocalActivityWithPayloads] or related extension functions.
 * Use [resultPayload] to await the raw result, or the typed [result] extension function, or [cancel] to request cancellation.
 *
 * Local activities differ from remote activities in that they:
 * - Run in the same worker process as the workflow
 * - Don't support heartbeats (operations should be short)
 * - Use markers for replay instead of re-execution
 * - May receive backoff signals that cause the workflow to schedule a timer before retrying
 *
 * The [resultPayload] method handles backoff transparently - if Core SDK signals that retry backoff
 * exceeds the local retry threshold, this handle will automatically schedule a timer,
 * wait, and reschedule the activity. This is hidden from the caller.
 *
 * @see ActivityHandle for common functionality
 * @see RemoteActivityHandle for server-scheduled activities
 */
interface LocalActivityHandle : ActivityHandle
