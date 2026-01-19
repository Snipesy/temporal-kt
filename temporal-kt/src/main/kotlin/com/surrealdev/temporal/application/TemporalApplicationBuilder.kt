package com.surrealdev.temporal.application

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Builder for configuring a [TemporalApplication].
 *
 * @param parentCoroutineContext The parent coroutine context for the application.
 *                               Defaults to [Dispatchers.Default].
 */
@TemporalDsl
class TemporalApplicationBuilder internal constructor(
    private val parentCoroutineContext: CoroutineContext = Dispatchers.Default,
) {
    private var connectionConfig = ConnectionConfig()

    /**
     * Sets the connection configuration directly.
     */
    fun connection(config: ConnectionConfig) {
        connectionConfig = config
    }

    /**
     * Configures the connection to the Temporal service using a builder pattern.
     *
     * Usage:
     * ```kotlin
     * connection {
     *     target = "http://localhost:7233"
     *     namespace = "my-namespace"
     * }
     * ```
     */
    fun connection(configure: ConnectionConfigBuilder.() -> Unit) {
        connectionConfig = ConnectionConfigBuilder(connectionConfig).apply(configure).build()
    }

    internal fun build(): TemporalApplication {
        val config =
            TemporalApplicationConfig(
                connection = connectionConfig,
            )
        val application = TemporalApplication(config, parentCoroutineContext)

        return application
    }
}

/**
 * Factory for creating plugins with configuration.
 */
interface TemporalPluginFactory<TConfig : Any, TPlugin : TemporalPlugin> {
    fun create(configure: TConfig.() -> Unit): TPlugin
}

/**
 * DSL marker for Temporal configuration builders.
 */
@DslMarker
annotation class TemporalDsl

/**
 * Builder for [ConnectionConfig] that allows DSL-style configuration.
 */
@TemporalDsl
class ConnectionConfigBuilder internal constructor(
    base: ConnectionConfig = ConnectionConfig(),
) {
    /** Target address (e.g., "http://localhost:7233" or "https://my-namespace.tmprl.cloud:7233"). */
    var target: String = base.target

    /** Namespace to use. */
    var namespace: String = base.namespace

    /** Whether to use TLS. */
    var useTls: Boolean = base.useTls

    /** Path to TLS client certificate (for mTLS). */
    var tlsCertPath: String? = base.tlsCertPath

    /** Path to TLS client key (for mTLS). */
    var tlsKeyPath: String? = base.tlsKeyPath

    internal fun build(): ConnectionConfig =
        ConnectionConfig(
            target = target,
            namespace = namespace,
            useTls = useTls,
            tlsCertPath = tlsCertPath,
            tlsKeyPath = tlsKeyPath,
        )
}
