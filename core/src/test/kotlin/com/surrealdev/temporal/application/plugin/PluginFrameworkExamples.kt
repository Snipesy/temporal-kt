package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.util.AttributeKey

/**
 * Examples demonstrating the new plugin framework.
 *
 * These examples are not meant to be run, but to illustrate usage patterns.
 */
@Suppress("unused")
object PluginFrameworkExamples {
    /**
     * Example 1: Simple plugin with configuration
     */
    class LoggingPlugin(
        val config: LoggingConfig,
    ) {
        companion object : ApplicationPlugin<LoggingConfig, LoggingPlugin> {
            override val key = AttributeKey<LoggingPlugin>(name = "Logging")

            override fun install(
                pipeline: TemporalApplication,
                configure: LoggingConfig.() -> Unit,
            ): LoggingPlugin {
                val config = LoggingConfig().apply(configure)
                val plugin = LoggingPlugin(config)

                // Create builder for registering hooks
                val builder = createPluginBuilder(pipeline, config, key)

                // Register lifecycle hooks
                builder.application {
                    onSetup { context ->
                        if (config.logStartup) {
                            println("Application started: ${context.application}")
                        }
                    }

                    onWorkerStarted { context ->
                        if (config.logWorkerEvents) {
                            println("Worker started: ${context.taskQueue}")
                        }
                    }
                }

                builder.workflow {
                    onTaskStarted { context ->
                        if (config.logWorkflowTasks) {
                            println("Workflow task started: ${context.runId}")
                        }
                    }
                }

                builder.activity {
                    onTaskStarted { context ->
                        if (config.logActivityTasks) {
                            println("Activity task started: ${context.activityType}")
                        }
                    }
                }

                // Install hooks and interceptors
                installHandlers(builder, pipeline)

                return plugin
            }
        }
    }

    @TemporalDsl
    data class LoggingConfig(
        var logStartup: Boolean = true,
        var logWorkerEvents: Boolean = true,
        var logWorkflowTasks: Boolean = false,
        var logActivityTasks: Boolean = false,
    )

    /**
     * Example 2: Metrics plugin using createApplicationPlugin DSL
     */
    fun metricsPluginExample() {
        data class MetricsConfig(
            var enabled: Boolean = true,
            var port: Int = 9090,
        )

        class MetricsPlugin(
            val config: MetricsConfig,
        ) {
            val workflowCount = mutableMapOf<String, Int>()
            val activityCount = mutableMapOf<String, Int>()

            fun recordWorkflow(type: String) {
                workflowCount[type] = (workflowCount[type] ?: 0) + 1
            }

            fun recordActivity(type: String) {
                activityCount[type] = (activityCount[type] ?: 0) + 1
            }
        }

        val metricsPluginFactory =
            createApplicationPlugin<MetricsPlugin, MetricsConfig>(
                name = "Metrics",
                createConfiguration = { MetricsConfig() },
            ) { config ->
                val plugin = MetricsPlugin(config)

                if (config.enabled) {
                    application {
                        onSetup { _ ->
                            println("Metrics server starting on port ${config.port}")
                        }
                    }

                    workflow {
                        onTaskStarted { context ->
                            context.workflowType?.let { plugin.recordWorkflow(it) }
                        }
                    }

                    activity {
                        onTaskStarted { context ->
                            plugin.recordActivity(context.activityType)
                        }
                    }
                }

                plugin
            }

        // Usage:
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "default"
                }
            }

        app.install(metricsPluginFactory) {
            enabled = true
            port = 9090
        }
    }

    /**
     * Example 3: Using the plugin in your application
     */
    fun usageExample() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "default"
                }
            }

        // Install logging plugin
        app.install(LoggingPlugin) {
            logStartup = true
            logWorkerEvents = true
            logWorkflowTasks = true
            logActivityTasks = false
        }

        // Install SerializationPlugin using new framework
        app.install(com.surrealdev.temporal.serialization.SerializationPlugin) {
            json {
                prettyPrint = false
                ignoreUnknownKeys = true
            }
        }

        // Retrieve plugin later if needed
        val loggingPlugin = app.plugin(LoggingPlugin)
        println("Logging plugin config: ${loggingPlugin.config}")

        // Or check if plugin is installed
        val maybePlugin = app.pluginOrNull(LoggingPlugin)
        if (maybePlugin != null) {
            println("Logging is enabled")
        }
    }

    /**
     * Example 4: Plugin with state management
     */
    class CachePlugin(
        val config: CacheConfig,
    ) {
        private val cache = mutableMapOf<String, Any>()

        fun <T : Any> get(key: String): T? {
            @Suppress("UNCHECKED_CAST")
            return cache[key] as? T
        }

        fun <T : Any> put(
            key: String,
            value: T,
        ) {
            cache[key] = value
        }

        fun clear() {
            cache.clear()
        }

        companion object : ApplicationPlugin<CacheConfig, CachePlugin> {
            override val key = AttributeKey<CachePlugin>(name = "Cache")

            override fun install(
                pipeline: TemporalApplication,
                configure: CacheConfig.() -> Unit,
            ): CachePlugin {
                val config = CacheConfig().apply(configure)
                val plugin = CachePlugin(config)

                val builder = createPluginBuilder(pipeline, config, key)

                // Clear cache on shutdown
                builder.application {
                    onShutdown { _ ->
                        plugin.clear()
                    }
                }

                installHandlers(builder, pipeline)

                return plugin
            }
        }
    }

    @TemporalDsl
    data class CacheConfig(
        var maxSize: Int = 1000,
        var ttlSeconds: Long = 3600,
    )

    /**
     * Example 5: Accessing application hooks directly
     */
    fun directHookUsageExample() {
        val app =
            TemporalApplication {
                connection {
                    target = "http://localhost:7233"
                    namespace = "default"
                }
            }

        // You can also register hooks directly without a plugin
        app.hookRegistry.register(
            com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskStarted,
        ) { context ->
            println("Direct hook: Workflow ${context.runId} started")
        }
    }
}
