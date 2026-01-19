package com.surrealdev.temporal.application

/**
 * Builder for configuring a task queue with workflows and activities.
 *
 * Usage:
 * ```kotlin
 * taskQueue("my-task-queue") {
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
) {
    /**
     * Optional namespace override for this task queue.
     * If null, the application's default namespace is used.
     */
    var namespace: String? = null

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
        )
}
