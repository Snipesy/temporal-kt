package com.surrealdev.temporal.application

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.application.plugin.HookRegistry
import com.surrealdev.temporal.application.plugin.HookRegistryImpl
import com.surrealdev.temporal.application.plugin.PluginPipeline
import com.surrealdev.temporal.internal.ZombieEvictionConfig
import com.surrealdev.temporal.serialization.payloadCodecOrNull
import com.surrealdev.temporal.serialization.payloadSerializer
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes
import io.temporal.api.common.v1.Payload

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
 *     workflow<MyWorkflow>()
 *     activity(MyActivityImpl())
 *
 *     // Or with explicit type names
 *     workflow<MyWorkflow>(workflowType = "CustomWorkflowName")
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

    /**
     * Timeout in milliseconds for detecting workflow deadlocks.
     * If a workflow activation doesn't complete within this time, a WorkflowDeadlockException is thrown.
     * Set to 0 to disable deadlock detection.
     *
     * Default: 2000ms (2 seconds)
     */
    var workflowDeadlockTimeoutMs: Long = 2000L

    /**
     * Configuration for zombie thread eviction.
     * Zombies are threads that don't respond to interrupt - typically due to
     * non-interruptible blocking operations (busy loops, certain native calls).
     */
    var zombieEviction: ZombieEvictionConfig = ZombieEvictionConfig()

    @PublishedApi
    internal val workflows = mutableListOf<WorkflowRegistration>()

    @PublishedApi
    internal val activities = mutableListOf<ActivityRegistration>()

    @PublishedApi
    internal var dynamicActivityHandler: DynamicActivityHandler? = null

    /**
     * Registers a workflow class.
     *
     * A new instance is created for each workflow execution using the no-arg constructor.
     * This aligns with Temporal's execution model where workflows must be replayable.
     *
     * The workflow type name is resolved in this order:
     * 1. Explicitly provided [workflowType] parameter
     * 2. Name from @Workflow annotation on the class
     * 3. Simple class name
     *
     * @param workflowType The workflow type name. If not provided, uses the @Workflow annotation name or class name.
     * @throws IllegalArgumentException if the workflow class doesn't have a no-arg constructor
     * @throws IllegalArgumentException if the workflow type name starts with '__temporal_' (reserved)
     */
    inline fun <reified T : Any> workflow(workflowType: String? = null) {
        val klass = T::class

        // Verify the class has a no-arg constructor
        val hasNoArgConstructor = klass.constructors.any { it.parameters.isEmpty() }
        require(hasNoArgConstructor) {
            "Workflow class ${klass.qualifiedName ?: klass.simpleName} must have a no-arg constructor"
        }

        // Resolve workflow type: explicit param > @Workflow annotation > class name
        val resolvedType =
            workflowType
                ?: klass.annotations
                    .filterIsInstance<Workflow>()
                    .firstOrNull()
                    ?.name
                    ?.takeIf { it.isNotBlank() }
                ?: klass.simpleName
                ?: error("Cannot determine workflow type name for ${klass.qualifiedName}")

        // Validate reserved prefix (used internally by Temporal)
        require(!resolvedType.startsWith("__temporal_")) {
            "Workflow type name '$resolvedType' cannot start with '__temporal_' (reserved for internal use)"
        }

        workflows.add(
            WorkflowRegistration(
                workflowType = resolvedType,
                workflowClass = klass,
            ),
        )
    }

    /**
     * Registers all @Activity annotated methods from an instance.
     *
     * Scans the instance for methods annotated with @Activity and registers each one.
     * Activity instances are singletons - the same instance handles all activity executions.
     *
     * @param instance The activity instance containing @Activity annotated methods
     */
    fun <T : Any> activity(instance: T) {
        activities.add(ActivityRegistration.InstanceRegistration(instance))
    }

    /**
     * Registers a specific activity function.
     *
     * Use this to register individual activity methods or top-level functions:
     * ```kotlin
     * // Bound method reference from an instance
     * val myActivity = MyActivity()
     * activity(myActivity::greet)
     * activity(myActivity::farewell, activityType = "CustomFarewell")
     *
     * // Top-level function (no class needed)
     * activity(::processOrder)
     * ```
     *
     * The activity type name is resolved in this order:
     * 1. Explicitly provided [activityType] parameter
     * 2. Name from @Activity annotation on the method (if present)
     * 3. Function name
     *
     * @param function A function reference (e.g., `instance::method` or `::topLevelFunction`)
     * @param activityType Optional override for the activity type name
     * @throws IllegalArgumentException if the activity type name starts with '__temporal_' (reserved)
     */
    fun <R> activity(
        function: kotlin.reflect.KFunction<R>,
        activityType: String? = null,
    ) {
        // Resolve activity type: explicit param > @Activity annotation > function name
        val resolvedType =
            activityType
                ?: function.annotations
                    .filterIsInstance<com.surrealdev.temporal.annotation.Activity>()
                    .firstOrNull()
                    ?.name
                    ?.takeIf { it.isNotBlank() }
                ?: function.name

        // Validate reserved prefix
        require(!resolvedType.startsWith("__temporal_")) {
            "Activity type name '$resolvedType' cannot start with '__temporal_' (reserved for internal use)"
        }

        activities.add(
            ActivityRegistration.FunctionRegistration(
                activityType = resolvedType,
                method = function,
            ),
        )
    }

    /**
     * Registers a dynamic activity handler as a fallback for unregistered activity types.
     *
     * When an activity task arrives for an unregistered activity type, this handler will be
     * invoked instead of returning an error. The handler receives the activity type name
     * and encoded payloads, allowing runtime dispatch to arbitrary implementations.
     *
     * The handler must return a [Payload] (or null) since type information is not available
     * at compile time. Use [ActivityContext.serializer] to serialize the result.
     *
     * Only one dynamic activity handler per task queue is allowed.
     *
     * Example:
     * ```kotlin
     * taskQueue("my-queue") {
     *     workflow<MyWorkflow>()
     *     activity(MyActivities())
     *
     *     // Dynamic activity fallback - called for unregistered activity types
     *     dynamicActivity { activityType, payloads ->
     *         // `this` is ActivityContext - can heartbeat, check cancellation, etc.
     *         when (activityType) {
     *             "httpGet" -> {
     *                 val result = httpClient.get(payloads.decode<String>(0))
     *                 serializer.serialize<String>(result)
     *             }
     *             else -> throw IllegalArgumentException("Unknown: $activityType")
     *         }
     *     }
     * }
     * ```
     *
     * @param handler The handler function to invoke for unregistered activity types.
     *                Within the handler, `this` is [ActivityContext].
     * @throws IllegalArgumentException if a dynamic activity handler is already registered
     */
    fun dynamicActivity(handler: DynamicActivityHandler) {
        require(dynamicActivityHandler == null) {
            "Only one dynamic activity handler per task queue is allowed"
        }
        dynamicActivityHandler = handler
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
            shutdownGracePeriodMs = shutdownGracePeriodMs,
            maxHeartbeatThrottleIntervalMs = maxHeartbeatThrottleIntervalMs,
            defaultHeartbeatThrottleIntervalMs = defaultHeartbeatThrottleIntervalMs,
            workflowDeadlockTimeoutMs = workflowDeadlockTimeoutMs,
            zombieEviction = zombieEviction,
            dynamicActivityHandler = dynamicActivityHandler,
            serializer = payloadSerializer(),
            codec = payloadCodecOrNull(),
        )
}
