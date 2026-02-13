package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetup
import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetupContext
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdown
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdownContext
import com.surrealdev.temporal.application.plugin.hooks.WorkerStarted
import com.surrealdev.temporal.application.plugin.hooks.WorkerStartedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkerStopped
import com.surrealdev.temporal.application.plugin.hooks.WorkerStoppedContext

/**
 * DSL builder for application-level hooks.
 *
 * Accessed via the `application {}` block in plugin configuration:
 * ```kotlin
 * val MyPlugin = createApplicationPlugin("MyPlugin") {
 *     application {
 *         onSetup { ctx -> ... }
 *         onShutdown { ctx -> ... }
 *         onWorkerStarted { ctx -> ... }
 *         onWorkerStopped { ctx -> ... }
 *     }
 * }
 * ```
 */
@TemporalDsl
class ApplicationHookBuilder internal constructor(
    private val pluginBuilder: PluginBuilder<*>,
) {
    /**
     * Registers a handler for application setup.
     *
     * Called after the runtime and core client are created but before workers start.
     */
    fun onSetup(handler: suspend (ApplicationSetupContext) -> Unit) {
        pluginBuilder.on(ApplicationSetup, handler)
    }

    /**
     * Registers a handler for application shutdown.
     *
     * Called at the start of the shutdown process before workers are stopped.
     */
    fun onShutdown(handler: suspend (ApplicationShutdownContext) -> Unit) {
        pluginBuilder.on(ApplicationShutdown, handler)
    }

    /**
     * Registers a handler for when a worker starts.
     *
     * Called after each worker successfully starts.
     */
    fun onWorkerStarted(handler: suspend (WorkerStartedContext) -> Unit) {
        pluginBuilder.on(WorkerStarted, handler)
    }

    /**
     * Registers a handler for when a worker stops.
     *
     * Called after each worker's stop completes.
     */
    fun onWorkerStopped(handler: suspend (WorkerStoppedContext) -> Unit) {
        pluginBuilder.on(WorkerStopped, handler)
    }
}
