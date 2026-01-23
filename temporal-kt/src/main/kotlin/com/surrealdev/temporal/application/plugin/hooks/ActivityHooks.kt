package com.surrealdev.temporal.application.plugin.hooks

import com.surrealdev.temporal.application.plugin.Hook
import coresdk.activity_task.ActivityTaskOuterClass.ActivityTask
import kotlin.time.Duration

/**
 * Hook called before dispatching an activity task.
 *
 * This hook is fired in [com.surrealdev.temporal.application.worker.ManagedWorker.pollActivityTasks]
 * before the task is dispatched to the activity dispatcher.
 *
 * Use this hook to:
 * - Track activity execution
 * - Initialize activity-scoped resources (e.g., dependency injection scopes, HTTP clients)
 * - Record metrics or logs
 * - Implement activity middleware
 */
object ActivityTaskStarted : Hook<suspend (ActivityTaskContext) -> Unit> {
    override val name = "ActivityTaskStarted"
}

/**
 * Context provided to [ActivityTaskStarted] hook handlers.
 *
 * @property task The activity task received from Core SDK
 * @property activityType The activity type name
 * @property activityId The activity ID
 * @property workflowId The workflow ID that scheduled this activity
 * @property runId The workflow run ID
 * @property taskQueue The task queue name
 * @property namespace The namespace
 */
data class ActivityTaskContext(
    val task: ActivityTask,
    val activityType: String,
    val activityId: String,
    val workflowId: String,
    val runId: String,
    val taskQueue: String,
    val namespace: String,
)

/**
 * Hook called after an activity task completes successfully.
 *
 * This hook is fired in [com.surrealdev.temporal.application.worker.ManagedWorker.pollActivityTasks]
 * after the activity dispatcher returns a successful completion.
 *
 * Use this hook to:
 * - Clean up activity-scoped resources
 * - Record metrics or performance data
 * - Implement activity middleware
 */
object ActivityTaskCompleted : Hook<suspend (ActivityTaskCompletedContext) -> Unit> {
    override val name = "ActivityTaskCompleted"
}

/**
 * Context provided to [ActivityTaskCompleted] hook handlers.
 *
 * @property task The activity task that was processed
 * @property activityType The activity type name
 * @property duration The time taken to execute the activity
 */
data class ActivityTaskCompletedContext(
    val task: ActivityTask,
    val activityType: String,
    val duration: Duration,
)

/**
 * Hook called when an activity task fails.
 *
 * This hook is fired in [com.surrealdev.temporal.application.worker.ManagedWorker.pollActivityTasks]
 * when an exception is thrown during activity dispatch.
 *
 * Use this hook to:
 * - Log errors
 * - Clean up activity-scoped resources
 * - Record error metrics
 */
object ActivityTaskFailed : Hook<suspend (ActivityTaskFailedContext) -> Unit> {
    override val name = "ActivityTaskFailed"
}

/**
 * Context provided to [ActivityTaskFailed] hook handlers.
 *
 * @property task The activity task that failed
 * @property activityType The activity type name
 * @property error The exception that was thrown
 */
data class ActivityTaskFailedContext(
    val task: ActivityTask,
    val activityType: String,
    val error: Throwable,
)

/**
 * Hook called when an activity heartbeat is sent.
 *
 * This hook is fired in [com.surrealdev.temporal.application.worker.ManagedWorker.recordActivityHeartbeat]
 * when an activity sends a heartbeat to the Temporal server.
 *
 * Use this hook to:
 * - Track activity liveness
 * - Record heartbeat metrics
 * - Monitor long-running activities
 */
object HeartbeatSent : Hook<suspend (HeartbeatContext) -> Unit> {
    override val name = "HeartbeatSent"
}

/**
 * Context provided to [HeartbeatSent] hook handlers.
 *
 * @property taskToken The task token for the activity
 * @property details The heartbeat details payload
 * @property activityType The activity type name
 */
data class HeartbeatContext(
    val taskToken: ByteArray,
    val details: ByteArray?,
    val activityType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HeartbeatContext

        if (!taskToken.contentEquals(other.taskToken)) return false
        if (details != null) {
            if (other.details == null) return false
            if (!details.contentEquals(other.details)) return false
        } else if (other.details != null) {
            return false
        }
        if (activityType != other.activityType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = taskToken.contentHashCode()
        result = 31 * result + (details?.contentHashCode() ?: 0)
        result = 31 * result + activityType.hashCode()
        return result
    }
}
