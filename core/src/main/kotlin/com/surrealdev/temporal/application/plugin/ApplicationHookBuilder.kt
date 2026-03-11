package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.hooks.ApplicationPreStartup
import com.surrealdev.temporal.application.plugin.hooks.ApplicationPreStartupContext
import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetup
import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetupContext
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdown
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdownContext
import com.surrealdev.temporal.application.plugin.hooks.ApplicationStartupFailed
import com.surrealdev.temporal.application.plugin.hooks.ApplicationStartupFailedContext
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
 *         onPreStartup { ctx -> ... }
 *         onSetup { ctx -> ... }
 *         onStartupFailed { ctx -> ... }
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
     * Registers a handler for pre-startup, fired at the very beginning of
     * [TemporalApplication.start] before any connection to Temporal is attempted.
     *
     * Ideal for starting health-check servers or other infrastructure that must
     * be reachable while the application is still initialising.
     */
    fun onPreStartup(handler: suspend (ApplicationPreStartupContext) -> Unit) {
        pluginBuilder.on(ApplicationPreStartup, handler)
    }

    /**
     * Registers a handler for application setup.
     *
     * Called after the runtime and core client are created but before workers start.
     */
    fun onSetup(handler: suspend (ApplicationSetupContext) -> Unit) {
        pluginBuilder.on(ApplicationSetup, handler)
    }

    /**
     * Registers a handler for startup failure.
     *
     * Called when [TemporalApplication.start] fails, before the exception is
     * re-thrown. Use this to clean up resources allocated in [onPreStartup] or
     * [onSetup].
     */
    fun onStartupFailed(handler: suspend (ApplicationStartupFailedContext) -> Unit) {
        pluginBuilder.on(ApplicationStartupFailed, handler)
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
