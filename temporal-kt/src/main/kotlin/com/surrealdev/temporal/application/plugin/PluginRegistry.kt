package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.util.AttributeKey
import com.surrealdev.temporal.util.Attributes

/**
 * Key for storing the plugin registry in a pipeline's attributes.
 */
internal val pluginRegistryKey = AttributeKey<Attributes>(name = "TemporalPluginRegistry")

/**
 * Gets the plugin registry from a pipeline's attributes.
 *
 * The registry is created on first access and stored in the pipeline's attributes.
 */
val PluginPipeline.pluginRegistry: Attributes
    get() = attributes.computeIfAbsent(pluginRegistryKey) { Attributes(concurrent = true) }

/**
 * Gets a plugin instance from the pipeline.
 *
 * For [TaskQueueBuilder], this performs hierarchical lookup:
 * first checks the task queue's local registry, then falls back to the parent application.
 *
 * @throws MissingPluginException if the plugin is not installed at any level
 */
fun <P : PluginPipeline, F : Any> P.plugin(plugin: Plugin<*, *, F>): F {
    val installed = pluginOrNull(plugin) ?: throw MissingPluginException(plugin.key)
    return installed
}

/**
 * Gets a plugin instance from the pipeline, or null if not installed.
 *
 * For [TaskQueueBuilder], this performs hierarchical lookup:
 * first checks the task queue's local registry, then falls back to the parent application.
 */
fun <P : PluginPipeline, F : Any> P.pluginOrNull(plugin: Plugin<*, *, F>): F? {
    // First check local registry
    @Suppress("UNCHECKED_CAST")
    val localPlugin = pluginRegistry.getOrNull(plugin.key) as? F
    if (localPlugin != null) {
        return localPlugin
    }

    // For TaskQueueBuilder, fall back to parent application
    if (this is TaskQueueBuilder) {
        @Suppress("UNCHECKED_CAST")
        return parentApplication?.pluginRegistry?.getOrNull(plugin.key) as? F
    }

    return null
}

/**
 * Checks if a plugin is installed locally in this pipeline (not including parent).
 */
fun <P : PluginPipeline, F : Any> P.hasPluginLocally(plugin: Plugin<*, *, F>): Boolean = plugin.key in pluginRegistry

/**
 * Installs a plugin into the pipeline with the given configuration.
 *
 * For [TaskQueueBuilder], this allows installing a plugin that overrides an
 * application-level plugin. The task-queue-level plugin will take precedence
 * for lookups within that task queue.
 *
 * @param plugin The plugin to install
 * @param configure Configuration block for the plugin
 * @return The installed plugin instance
 * @throws DuplicatePluginException if the plugin is already installed at the same level
 */
fun <P : PluginPipeline, B : Any, F : Any> P.install(
    plugin: Plugin<P, B, F>,
    configure: B.() -> Unit = {},
): F {
    val registry = pluginRegistry

    // Check if already installed at this level (local registry only)
    if (plugin.key in registry) {
        throw DuplicatePluginException(plugin.key)
    }

    // Install the plugin
    val pluginInstance = plugin.install(this, configure)

    // Store in registry
    registry.put(plugin.key, pluginInstance)

    return pluginInstance
}
