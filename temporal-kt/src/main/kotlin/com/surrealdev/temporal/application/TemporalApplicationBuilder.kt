package com.surrealdev.temporal.application

/**
 * Builder for configuring a [TemporalApplication].
 */
@TemporalDsl
class TemporalApplicationBuilder internal constructor() {
    private val connectionConfig = ConnectionConfig()
    private val taskQueues = mutableListOf<TaskQueueConfig>()
    private val plugins = mutableListOf<TemporalPlugin>()

    /**
     * Configures the connection to the Temporal service.
     */
    fun connection(configure: ConnectionConfig.() -> Unit) {
        connectionConfig.configure()
    }

    /**
     * Installs a plugin into the application.
     *
     * Usage:
     * ```kotlin
     * install(KotlinxSerialization) {
     *     json = Json { prettyPrint = true }
     * }
     * ```
     */
    fun <TConfig : Any, TPlugin : TemporalPlugin> install(
        plugin: TemporalPluginFactory<TConfig, TPlugin>,
        configure: TConfig.() -> Unit = {},
    ) {
        plugins.add(plugin.create(configure))
    }

    /**
     * Configures a task queue with workflows and activities.
     *
     * Usage:
     * ```kotlin
     * taskQueue("my-task-queue") {
     *     workflow(MyWorkflowImpl())
     *     activity(MyActivityImpl())
     * }
     * ```
     */
    fun taskQueue(
        name: String,
        configure: TaskQueueBuilder.() -> Unit,
    ) {
        val builder = TaskQueueBuilder(name)
        builder.configure()
        taskQueues.add(builder.build())
    }

    internal fun build(): TemporalApplication {
        val config =
            TemporalApplicationConfig(
                connection = connectionConfig,
                taskQueues = taskQueues.toList(),
                plugins = plugins.toList(),
            )
        return TemporalApplication(config)
    }
}

/**
 * Factory for creating plugins with configuration.
 */
interface TemporalPluginFactory<TConfig : Any, TPlugin : TemporalPlugin> {
    fun create(configure: TConfig.() -> Unit): TPlugin
}

/**
 * DSL marker for Temporal configuration builders.
 */
@DslMarker
annotation class TemporalDsl
