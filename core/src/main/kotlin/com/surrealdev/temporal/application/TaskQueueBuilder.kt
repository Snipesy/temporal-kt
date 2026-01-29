package com.surrealdev.temporal.application

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.HookRegistry
import com.surrealdev.temporal.application.plugin.HookRegistryImpl
import com.surrealdev.temporal.application.plugin.PluginPipeline
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Builder for configuring a task queue with workflows and activities.
 *
 * TaskQueueBuilder supports installing plugins that can override application-level plugins.
 * When looking up a plugin, the task queue's local registry is checked first, then
 * the parent application's registry is used as fallback.
 *
 * Usage:
 * ```kotlin
 * taskQueue("my-task-queue") {
 *     // Override application-level plugin with task-queue-specific config
 *     install(MyPlugin) {
 *         // Task-queue-specific configuration
 *     }
 *
 *     workflow(MyWorkflowImpl())
 *     activity(MyActivityImpl())
 *
 *     // Or with explicit type names
 *     workflow(MyWorkflowImpl(), workflowType = "CustomWorkflowName")
 *     activity(MyActivityImpl(), activityType = "CustomActivityName")
 * }
 * ```
 */
@TemporalDsl
class TaskQueueBuilder internal constructor(
    private val name: String,
    /**
     * Parent application for hierarchical plugin/attribute lookup.
     * Plugins/attributes installed at the application level can be overridden at the task queue level.
     */
    internal val parentApplication: TemporalApplication? = null,
) : PluginPipeline {
    // New plugin framework - attributes with parent scope for hierarchical lookup
    override val attributes: Attributes = Attributes(concurrent = false)
    override val parentScope: AttributeScope? = parentApplication
    internal val hookRegistry: HookRegistry = HookRegistryImpl()

    /**
     * Optional namespace override for this task queue.
     * If null, the application's default namespace is used.
     */
    var namespace: String? = null

    /**
     * Maximum number of concurrent workflow executions.
     * This is a logical limit enforced via semaphore.
     */
    var maxConcurrentWorkflows: Int = 200

    /**
     * Maximum number of concurrent activity executions.
     * This is a logical limit enforced via semaphore.
     */
    var maxConcurrentActivities: Int = 200

    /**
     * Dispatcher for workflow activations on this task queue.
     *
     * When set, workflow processing (including the deterministic scheduler's work)
     * runs on this dispatcher. The [WorkflowCoroutineDispatcher] still handles
     * deterministic scheduling within this thread context.
     *
     * Default: null (inherits from application's coroutine context)
     */
    var workflowDispatcher: CoroutineDispatcher? = null

    /**
     * Dispatcher for activity execution on this task queue.
     *
     * When set, activities are wrapped with `withContext(dispatcher)` before execution.
     * This allows controlling the thread pool for activity work (e.g., using
     * [Dispatchers.IO] for I/O-bound activities).
     *
     * Default: null (inherits from application's coroutine context)
     */
    var activityDispatcher: CoroutineDispatcher? = null

    /**
     * Grace period for shutdown to wait for polling jobs to complete gracefully.
     * After this timeout, polling jobs will be force-cancelled.
     *
     * Default: 10,000ms (10 seconds)
     */
    var shutdownGracePeriodMs: Long = 10_000L

    /**
     * Additional timeout after force cancellation to wait for cleanup.
     *
     * Default: 5,000ms (5 seconds)
     */
    var shutdownForceTimeoutMs: Long = 5_000L

    /**
     * Maximum interval for throttling activity heartbeats.
     * Heartbeats will be throttled to at most this interval.
     *
     * Default: 60,000ms (60 seconds)
     */
    var maxHeartbeatThrottleIntervalMs: Long = 60_000L

    /**
     * Default interval for throttling activity heartbeats when no heartbeat timeout is set.
     * When a heartbeat timeout is configured, throttling uses 80% of that timeout instead.
     *
     * Default: 30,000ms (30 seconds)
     */
    var defaultHeartbeatThrottleIntervalMs: Long = 30_000L

    @PublishedApi
    internal val workflows = mutableListOf<WorkflowRegistration>()

    @PublishedApi
    internal val activities = mutableListOf<ActivityRegistration>()

    /**
     * Registers a workflow implementation.
     *
     * @param implementation The workflow implementation instance
     * @param workflowType The workflow type name. Defaults to the simple class name.
     */
    inline fun <reified T : Any> workflow(
        implementation: T,
        workflowType: String = T::class.simpleName ?: error("Cannot determine workflow type name"),
    ) {
        workflows.add(
            WorkflowRegistration(
                workflowType = workflowType,
                implementation = implementation,
            ),
        )
    }

    /**
     * Registers an activity implementation.
     *
     * @param implementation The activity implementation instance
     * @param activityType The activity type name. Defaults to the simple class name.
     */
    inline fun <reified T : Any> activity(
        implementation: T,
        activityType: String = T::class.simpleName ?: error("Cannot determine activity type name"),
    ) {
        activities.add(
            ActivityRegistration(
                activityType = activityType,
                implementation = implementation,
            ),
        )
    }

    internal fun build(): TaskQueueConfig =
        TaskQueueConfig(
            name = name,
            namespace = namespace,
            workflows = workflows.toList(),
            activities = activities.toList(),
            maxConcurrentWorkflows = maxConcurrentWorkflows,
            maxConcurrentActivities = maxConcurrentActivities,
            attributes = attributes,
            hookRegistry = hookRegistry,
            workflowDispatcher = workflowDispatcher,
            activityDispatcher = activityDispatcher,
            shutdownGracePeriodMs = shutdownGracePeriodMs,
            shutdownForceTimeoutMs = shutdownForceTimeoutMs,
            maxHeartbeatThrottleIntervalMs = maxHeartbeatThrottleIntervalMs,
            defaultHeartbeatThrottleIntervalMs = defaultHeartbeatThrottleIntervalMs,
        )
}
