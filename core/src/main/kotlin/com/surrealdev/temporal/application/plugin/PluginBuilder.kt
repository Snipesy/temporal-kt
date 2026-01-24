package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskContext
import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetupContext
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdownContext
import com.surrealdev.temporal.application.plugin.hooks.WorkerStartedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskContext
import com.surrealdev.temporal.util.AttributeKey

/**
 * Builder for declaratively configuring plugins with hooks.
 *
 * The [PluginBuilder] provides a DSL for:
 * - Accessing the application and plugin configuration
 * - Registering lifecycle hooks
 * - Building the plugin instance
 *
 * Usage:
 * ```kotlin
 * val plugin = createApplicationPlugin<MyPlugin>(
 *     name = "MyPlugin",
 *     createConfiguration = { MyConfig() }
 * ) {
 *     onApplicationSetup { context ->
 *         // Initialize plugin
 *     }
 *
 *     onWorkflowTaskStarted { context ->
 *         // Handle workflow task
 *     }
 * }
 * ```
 */
@TemporalDsl
abstract class PluginBuilder<PluginConfig : Any> internal constructor(
    val key: AttributeKey<*>,
) {
    /**
     * The application this plugin is being installed into.
     */
    abstract val application: TemporalApplication

    /**
     * The plugin's configuration.
     */
    abstract val pluginConfig: PluginConfig

    /**
     * Internal list of hook handlers registered by this plugin.
     */
    val hooks = mutableListOf<HookHandler<*>>()

    /**
     * Registers a handler for the given hook.
     *
     * @param hook The hook to register for
     * @param handler The handler function
     */
    fun <HookHandler> on(
        hook: Hook<HookHandler>,
        handler: HookHandler,
    ) {
        hooks.add(HookHandler(hook, handler))
    }

    // Convenience methods for common hooks

    /**
     * Registers a handler for application setup.
     *
     * Called after the runtime and core client are created but before workers start.
     */
    fun onApplicationSetup(handler: suspend (ApplicationSetupContext) -> Unit) {
        on(com.surrealdev.temporal.application.plugin.hooks.ApplicationSetup, handler)
    }

    /**
     * Registers a handler for application shutdown.
     *
     * Called at the start of the shutdown process before workers are stopped.
     */
    fun onApplicationShutdown(handler: suspend (ApplicationShutdownContext) -> Unit) {
        on(com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdown, handler)
    }

    /**
     * Registers a handler for when a worker starts.
     *
     * Called after each worker successfully starts.
     */
    fun onWorkerStarted(handler: suspend (WorkerStartedContext) -> Unit) {
        on(com.surrealdev.temporal.application.plugin.hooks.WorkerStarted, handler)
    }

    /**
     * Registers a handler for when a workflow task starts.
     *
     * Called before dispatching a workflow activation.
     */
    fun onWorkflowTaskStarted(handler: suspend (WorkflowTaskContext) -> Unit) {
        on(com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskStarted, handler)
    }

    /**
     * Registers a handler for when an activity task starts.
     *
     * Called before dispatching an activity task.
     */
    fun onActivityTaskStarted(handler: suspend (ActivityTaskContext) -> Unit) {
        on(com.surrealdev.temporal.application.plugin.hooks.ActivityTaskStarted, handler)
    }
}

/**
 * Internal implementation of [PluginBuilder].
 */
class PluginBuilderImpl<PluginConfig : Any>(
    key: AttributeKey<*>,
    override val application: TemporalApplication,
    override val pluginConfig: PluginConfig,
) : PluginBuilder<PluginConfig>(key)

/**
 * Creates an application-level plugin with the given configuration.
 *
 * This is the primary way to create custom plugins for temporal-kt.
 *
 * Example:
 * ```kotlin
 * data class MyPluginConfig(var enabled: Boolean = true)
 *
 * class MyPlugin(val config: MyPluginConfig) {
 *     companion object : ApplicationPlugin<MyPluginConfig, MyPlugin> {
 *         override val key = AttributeKey<MyPlugin>("MyPlugin")
 *
 *         override fun install(
 *             pipeline: TemporalApplication,
 *             configure: MyPluginConfig.() -> Unit
 *         ): MyPlugin {
 *             val config = MyPluginConfig().apply(configure)
 *             val plugin = MyPlugin(config)
 *
 *             val builder = createPluginBuilder(pipeline, config, key)
 *             builder.onWorkflowTaskStarted { context ->
 *                 // Handle workflow task
 *             }
 *             builder.hooks.forEach { it.install(pipeline.hookRegistry) }
 *
 *             return plugin
 *         }
 *     }
 * }
 * ```
 *
 * @param name The plugin name (used for the attribute key)
 * @param createConfiguration Factory for creating the configuration instance
 * @param body Builder block for configuring hooks and creating the plugin instance
 * @return An [ApplicationPlugin] instance
 */
inline fun <reified TPlugin : Any, TConfig : Any> createApplicationPlugin(
    name: String,
    crossinline createConfiguration: () -> TConfig = { Unit as TConfig },
    crossinline body: PluginBuilder<TConfig>.(TConfig) -> TPlugin,
): ApplicationPlugin<TConfig, TPlugin> =
    object : ApplicationPlugin<TConfig, TPlugin> {
        override val key = AttributeKey<TPlugin>(name = name)

        override fun install(
            pipeline: TemporalApplication,
            configure: TConfig.() -> Unit,
        ): TPlugin {
            val config = createConfiguration().apply(configure)
            val builder = PluginBuilderImpl(key, pipeline, config)
            val plugin = body(builder, config)

            // Register all hooks
            builder.hooks.forEach { hookHandler ->
                @Suppress("UNCHECKED_CAST")
                hookHandler.install(pipeline.hookRegistry)
            }

            return plugin
        }
    }

/**
 * Creates a plugin builder for manual plugin creation.
 *
 * This is a lower-level API used when implementing plugins that need more control
 * over the installation process.
 *
 * @param pipeline The application to install into
 * @param config The plugin configuration
 * @param key The plugin key
 * @return A [PluginBuilder] for registering hooks
 */
fun <TConfig : Any> createPluginBuilder(
    pipeline: TemporalApplication,
    config: TConfig,
    key: AttributeKey<*>,
): PluginBuilder<TConfig> = PluginBuilderImpl(key, pipeline, config)
