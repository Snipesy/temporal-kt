package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.util.AttributeKey
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes

/**
 * Base interface for plugin pipelines.
 *
 * A plugin pipeline provides a context for plugin installation and stores
 * plugin instances using a type-safe [Attributes] map.
 *
 * Extends [AttributeScope] to support hierarchical attribute lookup across
 * the application -> task queue -> execution hierarchy.
 */
interface PluginPipeline : AttributeScope {
    /**
     * Attributes for storing plugin instances and other contextual data.
     */
    override val attributes: Attributes
}

/**
 * Base plugin interface for the new plugin framework.
 *
 * Plugins are installed into a [PluginPipeline] (such as [TemporalApplication] or [TaskQueueBuilder])
 * and provide extensibility through lifecycle hooks.
 *
 * @param TPipeline The type of pipeline this plugin can be installed into
 * @param TConfiguration The configuration type for the plugin
 * @param TPlugin The plugin instance type that is returned after installation
 */
interface Plugin<TPipeline : PluginPipeline, TConfiguration : Any, TPlugin : Any> {
    /**
     * Unique key for identifying this plugin.
     */
    val key: AttributeKey<TPlugin>

    /**
     * Installs the plugin into the pipeline.
     *
     * @param pipeline The pipeline to install into
     * @param configure Configuration block for the plugin
     * @return The installed plugin instance
     */
    fun install(
        pipeline: TPipeline,
        configure: TConfiguration.() -> Unit,
    ): TPlugin
}

/**
 * Plugin that can be installed at the application level.
 *
 * Application-level plugins have access to the full application lifecycle
 * and can register hooks for all workers, workflows, and activities.
 *
 * @param TConfiguration The configuration type for the plugin
 * @param TPlugin The plugin instance type
 */
interface ApplicationPlugin<TConfiguration : Any, TPlugin : Any> :
    Plugin<TemporalApplication, TConfiguration, TPlugin>

/**
 * Plugin that can be installed at the task queue level.
 *
 * Task-queue-scoped plugins are isolated to a specific task queue and
 * only receive hooks for workflows and activities in that queue.
 *
 * @param TConfiguration The configuration type for the plugin
 * @param TPlugin The plugin instance type
 */
interface TaskQueueScopedPlugin<TConfiguration : Any, TPlugin : Any> :
    Plugin<TaskQueueBuilder, TConfiguration, TPlugin>
