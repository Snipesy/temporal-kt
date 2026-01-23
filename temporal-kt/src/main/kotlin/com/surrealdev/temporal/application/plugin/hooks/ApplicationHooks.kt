package com.surrealdev.temporal.application.plugin.hooks

import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.Hook
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.core.TemporalRuntime

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
