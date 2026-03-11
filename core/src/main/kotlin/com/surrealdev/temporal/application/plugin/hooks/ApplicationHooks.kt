package com.surrealdev.temporal.application.plugin.hooks

import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.Hook
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.core.TemporalRuntime

/**
 * Hook called at the very start of [TemporalApplication.start], before the
 * runtime is created or any connection to the Temporal server is attempted.
 *
 * Use this hook for work that must be available as early as possible, such as
 * starting an HTTP health-check server so that K8s startup probes can connect
 * while the application is still initialising.
 */
object ApplicationPreStartup : Hook<suspend (ApplicationPreStartupContext) -> Unit> {
    override val name = "ApplicationPreStartup"
}

/**
 * Context provided to [ApplicationPreStartup] hook handlers.
 *
 * @property application The [TemporalApplication] instance
 */
data class ApplicationPreStartupContext(
    val application: TemporalApplication,
)

/**
 * Hook called after the application's runtime and core client are created.
 *
 * This hook is fired in [TemporalApplication.start] after creating the runtime
 * and connecting to the Temporal server, but before starting any workers.
 *
 * Use this hook to:
 * - Initialize plugin state that depends on the runtime or client
 * - Set up shared resources needed by workers
 * - Perform application-level setup
 */
object ApplicationSetup : Hook<suspend (ApplicationSetupContext) -> Unit> {
    override val name = "ApplicationSetup"
}

/**
 * Context provided to [ApplicationSetup] hook handlers.
 *
 * @property application The [TemporalApplication] instance
 * @property runtime The [TemporalRuntime] instance
 * @property coreClient The [TemporalCoreClient] instance
 */
data class ApplicationSetupContext(
    val application: TemporalApplication,
    val runtime: TemporalRuntime,
    val coreClient: TemporalCoreClient,
)

/**
 * Hook called when [TemporalApplication.start] fails with an exception.
 *
 * This hook fires before the exception is re-thrown, giving plugins a chance to
 * clean up resources they allocated during [ApplicationPreStartup] or
 * [ApplicationSetup]. For example, the health-check plugin can stop its HTTP
 * server so the port is released.
 */
object ApplicationStartupFailed : Hook<suspend (ApplicationStartupFailedContext) -> Unit> {
    override val name = "ApplicationStartupFailed"
}

/**
 * Context provided to [ApplicationStartupFailed] hook handlers.
 *
 * @property application The [TemporalApplication] instance
 * @property cause The exception that caused startup to fail
 */
data class ApplicationStartupFailedContext(
    val application: TemporalApplication,
    val cause: Throwable,
)

/**
 * Hook called at the start of application shutdown.
 *
 * This hook is fired in [TemporalApplication.close] before stopping any workers
 * or closing the core client.
 *
 * Use this hook to:
 * - Clean up plugin resources
 * - Flush pending data
 * - Perform graceful shutdown operations
 */
object ApplicationShutdown : Hook<suspend (ApplicationShutdownContext) -> Unit> {
    override val name = "ApplicationShutdown"
}

/**
 * Context provided to [ApplicationShutdown] hook handlers.
 *
 * @property application The [TemporalApplication] instance
 */
data class ApplicationShutdownContext(
    val application: TemporalApplication,
)
