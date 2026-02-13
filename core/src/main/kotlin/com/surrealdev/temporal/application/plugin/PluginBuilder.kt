package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorRegistry
import com.surrealdev.temporal.client.TemporalClientConfig
import com.surrealdev.temporal.util.AttributeKey

/**
 * Builder for declaratively configuring plugins with hooks and interceptors.
 *
 * The [PluginBuilder] provides a DSL for:
 * - Accessing the application and plugin configuration
 * - Registering lifecycle hooks via observer pattern
 * - Registering interceptors via chain-of-responsibility pattern
 * - Building the plugin instance
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
 *         onExecute { input, proceed -> proceed(input) }
 *         onTaskStarted { ctx -> ... }
 *     }
 *
 *     activity {
 *         onExecute { input, proceed -> proceed(input) }
 *         onTaskStarted { ctx -> ... }
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
     * Internal list of hook handlers registered by this plugin.
     */
    val hooks = mutableListOf<HookHandler<*>>()

    /**
     * Internal interceptor registry for interceptors registered by this plugin.
     */
    internal val interceptorRegistry = InterceptorRegistry()

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
        WorkflowHookBuilder(this, interceptorRegistry).apply(block)
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
        ActivityHookBuilder(this, interceptorRegistry).apply(block)
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
        ClientHookBuilder(this, interceptorRegistry).apply(block)
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
 *             builder.hooks.forEach { it.install(pipeline.hookRegistry) }
 *             installInterceptors(builder, pipeline)
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

            // Register all hooks
            builder.hooks.forEach { hookHandler ->
                @Suppress("UNCHECKED_CAST")
                hookHandler.install(pipeline.hookRegistry)
            }

            // Merge interceptors into the pipeline's registry
            installInterceptors(builder, pipeline)

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
        else -> null
    }

/**
 * Resolves the [InterceptorRegistry] for the given pipeline, if available.
 */
@PublishedApi
internal fun resolveInterceptorRegistry(pipeline: PluginPipeline): InterceptorRegistry? =
    when (pipeline) {
        is TemporalApplication -> pipeline.interceptorRegistry
        is TaskQueueBuilder -> pipeline.interceptorRegistry
        is TemporalClientConfig -> pipeline.interceptorRegistry
        else -> null
    }

/**
 * Installs all hooks from a [PluginBuilder] into the given pipeline's hook registry.
 */
@PublishedApi
internal fun installHooks(
    builder: PluginBuilder<*>,
    pipeline: PluginPipeline,
) {
    val hookRegistry = resolveHookRegistry(pipeline) ?: return
    builder.hooks.forEach { hookHandler ->
        @Suppress("UNCHECKED_CAST")
        hookHandler.install(hookRegistry)
    }
}

/**
 * Merges interceptors from a [PluginBuilder] into the pipeline's interceptor registry.
 */
@PublishedApi
internal fun installInterceptors(
    builder: PluginBuilder<*>,
    pipeline: PluginPipeline,
) {
    val pipelineRegistry = resolveInterceptorRegistry(pipeline) ?: return
    val builderRegistry = builder.interceptorRegistry

    // Merge all interceptor lists from the builder into the pipeline registry
    pipelineRegistry.executeWorkflow.addAll(builderRegistry.executeWorkflow)
    pipelineRegistry.handleSignal.addAll(builderRegistry.handleSignal)
    pipelineRegistry.handleQuery.addAll(builderRegistry.handleQuery)
    pipelineRegistry.validateUpdate.addAll(builderRegistry.validateUpdate)
    pipelineRegistry.executeUpdate.addAll(builderRegistry.executeUpdate)

    pipelineRegistry.scheduleActivity.addAll(builderRegistry.scheduleActivity)
    pipelineRegistry.scheduleLocalActivity.addAll(builderRegistry.scheduleLocalActivity)
    pipelineRegistry.startChildWorkflow.addAll(builderRegistry.startChildWorkflow)
    pipelineRegistry.sleep.addAll(builderRegistry.sleep)
    pipelineRegistry.signalExternalWorkflow.addAll(builderRegistry.signalExternalWorkflow)
    pipelineRegistry.cancelExternalWorkflow.addAll(builderRegistry.cancelExternalWorkflow)
    pipelineRegistry.continueAsNew.addAll(builderRegistry.continueAsNew)

    pipelineRegistry.executeActivity.addAll(builderRegistry.executeActivity)
    pipelineRegistry.heartbeat.addAll(builderRegistry.heartbeat)

    // Client Outbound
    pipelineRegistry.startWorkflow.addAll(builderRegistry.startWorkflow)
    pipelineRegistry.signalWorkflow.addAll(builderRegistry.signalWorkflow)
    pipelineRegistry.queryWorkflow.addAll(builderRegistry.queryWorkflow)
    pipelineRegistry.startWorkflowUpdate.addAll(builderRegistry.startWorkflowUpdate)
    pipelineRegistry.cancelWorkflow.addAll(builderRegistry.cancelWorkflow)
    pipelineRegistry.terminateWorkflow.addAll(builderRegistry.terminateWorkflow)
    pipelineRegistry.describeWorkflow.addAll(builderRegistry.describeWorkflow)
    pipelineRegistry.listWorkflows.addAll(builderRegistry.listWorkflows)
    pipelineRegistry.countWorkflows.addAll(builderRegistry.countWorkflows)
    pipelineRegistry.fetchWorkflowResult.addAll(builderRegistry.fetchWorkflowResult)
    pipelineRegistry.fetchWorkflowHistory.addAll(builderRegistry.fetchWorkflowHistory)
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

            installHooks(builder, pipeline)
            installInterceptors(builder, pipeline)

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
