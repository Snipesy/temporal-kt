package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.client.TemporalClientConfig
import com.surrealdev.temporal.util.AttributeKey

/**
 * Builder for declaratively configuring plugins with hooks and interceptors.
 *
 * The [PluginBuilder] provides a DSL for:
 * - Accessing the application and plugin configuration
 * - Registering lifecycle [Hook]s via observer pattern (fan-out to all handlers)
 * - Registering [InterceptorHook]s via chain-of-responsibility pattern (with `proceed`)
 * - Building the plugin instance
 *
 * Both hooks and interceptors are registered via the same unified [on] method, since
 * [InterceptorHook] extends [Hook]. The DSL builders ([WorkflowHookBuilder],
 * [ActivityHookBuilder], [ClientHookBuilder]) provide convenient `onXxx` methods for both.
 *
 * **Usage:**
 * ```kotlin
 * val plugin = createApplicationPlugin<Unit>("MyPlugin") {
 *     application {
 *         onSetup { ctx -> ... }
 *         onShutdown { ctx -> ... }
 *     }
 *
 *     workflow {
 *         // Interceptors (chain-of-responsibility with proceed)
 *         onExecute { input, proceed -> proceed(input) }
 *         onHandleSignal { input, proceed -> proceed(input) }
 *
 *         // Observer hooks (fan-out to all handlers)
 *         onTaskStarted { ctx -> ... }
 *     }
 *
 *     activity {
 *         onExecute { input, proceed -> proceed(input) }
 *         onTaskStarted { ctx -> ... }
 *     }
 *
 *     client {
 *         onStartWorkflow { input, proceed -> proceed(input) }
 *     }
 *
 *     Unit
 * }
 * ```
 */
@TemporalDsl
abstract class PluginBuilder<PluginConfig : Any> internal constructor(
    val key: AttributeKey<*>,
) {
    /**
     * The pipeline this plugin is being installed into.
     */
    abstract val pipeline: PluginPipeline

    /**
     * The application this plugin is being installed into.
     *
     * For application-level plugins, this is the pipeline itself.
     * For task-queue-level plugins, this resolves to the parent application.
     */
    val application: TemporalApplication
        get() =
            when (val p = pipeline) {
                is TemporalApplication -> {
                    p
                }

                is TaskQueueBuilder -> {
                    p.parentApplication
                        ?: error("TaskQueueBuilder has no parent application")
                }

                else -> {
                    error(
                        "Cannot access application from ${p::class.simpleName} pipeline. " +
                            "The application property is only available when installed into a TemporalApplication.",
                    )
                }
            }

    /**
     * The plugin's configuration.
     */
    abstract val pluginConfig: PluginConfig

    /**
     * Internal unified registry for both hooks and interceptors registered by this plugin.
     */
    internal val registry = HookRegistryImpl()

    /**
     * Registers a handler for the given hook.
     *
     * Works for both lifecycle hooks and interceptor hooks (since [InterceptorHook] extends [Hook]).
     *
     * @param hook The hook to register for
     * @param handler The handler function
     */
    fun <HookHandler> on(
        hook: Hook<HookHandler>,
        handler: HookHandler,
    ) {
        registry.register(hook, handler)
    }

    // ==================== Structured DSL Blocks ====================

    /**
     * Configures application-level hooks (setup, shutdown, worker lifecycle).
     *
     * ```kotlin
     * application {
     *     onSetup { ctx -> ... }
     *     onShutdown { ctx -> ... }
     *     onWorkerStarted { ctx -> ... }
     *     onWorkerStopped { ctx -> ... }
     * }
     * ```
     */
    fun application(block: ApplicationHookBuilder.() -> Unit) {
        ApplicationHookBuilder(this).apply(block)
    }

    /**
     * Configures workflow interceptors and observer hooks.
     *
     * ```kotlin
     * workflow {
     *     // Interceptors (chain-of-responsibility)
     *     onExecute { input, proceed -> proceed(input) }
     *     onHandleSignal { input, proceed -> proceed(input) }
     *
     *     // Observer hooks
     *     onTaskStarted { ctx -> ... }
     * }
     * ```
     */
    fun workflow(block: WorkflowHookBuilder.() -> Unit) {
        WorkflowHookBuilder(this).apply(block)
    }

    /**
     * Configures activity interceptors and observer hooks.
     *
     * ```kotlin
     * activity {
     *     // Interceptors (chain-of-responsibility)
     *     onExecute { input, proceed -> proceed(input) }
     *     onHeartbeat { input, proceed -> proceed(input) }
     *
     *     // Observer hooks
     *     onTaskStarted { ctx -> ... }
     * }
     * ```
     */
    fun activity(block: ActivityHookBuilder.() -> Unit) {
        ActivityHookBuilder(this).apply(block)
    }

    /**
     * Configures client interceptors.
     *
     * ```kotlin
     * client {
     *     onStartWorkflow { input, proceed -> proceed(input) }
     *     onSignalWorkflow { input, proceed -> proceed(input) }
     * }
     * ```
     */
    fun client(block: ClientHookBuilder.() -> Unit) {
        ClientHookBuilder(this).apply(block)
    }
}

/**
 * Internal implementation of [PluginBuilder].
 */
class PluginBuilderImpl<PluginConfig : Any>(
    key: AttributeKey<*>,
    override val pipeline: PluginPipeline,
    override val pluginConfig: PluginConfig,
) : PluginBuilder<PluginConfig>(key)

/**
 * Creates an application-level plugin with the given configuration.
 *
 * For plugins that should be installable at any pipeline level (application or task queue),
 * use [createScopedPlugin] instead.
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
 *             builder.workflow {
 *                 onTaskStarted { context ->
 *                     // Handle workflow task
 *                 }
 *             }
 *             installHandlers(builder, pipeline)
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
    @Suppress("UNCHECKED_CAST") crossinline createConfiguration: () -> TConfig = { Unit as TConfig },
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

            // Register all hooks and interceptors into the pipeline
            installHandlers(builder, pipeline)

            return plugin
        }
    }

/**
 * Resolves the [HookRegistry] for the given pipeline, if available.
 */
@PublishedApi
internal fun resolveHookRegistry(pipeline: PluginPipeline): HookRegistry? =
    when (pipeline) {
        is TemporalApplication -> pipeline.hookRegistry
        is TaskQueueBuilder -> pipeline.hookRegistry
        is TemporalClientConfig -> pipeline.hookRegistry
        else -> null
    }

/**
 * Installs all hooks and interceptors from a [PluginBuilder] into the given pipeline's hook registry.
 */
@PublishedApi
internal fun installHandlers(
    builder: PluginBuilder<*>,
    pipeline: PluginPipeline,
) {
    val pipelineRegistry = resolveHookRegistry(pipeline) ?: return
    pipelineRegistry.addAllFrom(builder.registry)
}

/**
 * Creates a scoped plugin that can be installed at any pipeline level.
 *
 * This is the primary way to create plugins that work at both application
 * and task queue levels.
 *
 * @param name The plugin name (used for the attribute key)
 * @param createConfiguration Factory for creating the configuration instance
 * @param body Builder block for configuring hooks and creating the plugin instance
 * @return A [ScopedPlugin] instance
 */
inline fun <reified TPlugin : Any, TConfig : Any> createScopedPlugin(
    name: String,
    @Suppress("UNCHECKED_CAST") crossinline createConfiguration: () -> TConfig = { Unit as TConfig },
    crossinline body: PluginBuilder<TConfig>.(TConfig) -> TPlugin,
): ScopedPlugin<TConfig, TPlugin> =
    object : ScopedPlugin<TConfig, TPlugin> {
        override val key = AttributeKey<TPlugin>(name = name)

        override fun install(
            pipeline: PluginPipeline,
            configure: TConfig.() -> Unit,
        ): TPlugin {
            val config = createConfiguration().apply(configure)
            val builder = PluginBuilderImpl(key, pipeline, config)
            val plugin = body(builder, config)

            installHandlers(builder, pipeline)

            return plugin
        }
    }

/**
 * Creates a plugin builder for manual plugin creation.
 *
 * This is a lower-level API used when implementing plugins that need more control
 * over the installation process. Works with any [PluginPipeline] (application or task queue).
 *
 * @param pipeline The pipeline to install into (e.g. [TemporalApplication] or [TaskQueueBuilder])
 * @param config The plugin configuration
 * @param key The plugin key
 * @return A [PluginBuilder] for registering hooks
 */
fun <TConfig : Any> createPluginBuilder(
    pipeline: PluginPipeline,
    config: TConfig,
    key: AttributeKey<*>,
): PluginBuilder<TConfig> = PluginBuilderImpl(key, pipeline, config)
