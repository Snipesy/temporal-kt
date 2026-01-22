package com.surrealdev.temporal.application

import com.surrealdev.temporal.annotation.TemporalDsl
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
    private var deploymentOptions: WorkerDeploymentOptions? = null

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

    /**
     * Configures worker deployment versioning for all workers.
     *
     * Usage:
     * ```kotlin
     * deployment(WorkerDeploymentVersion("llm_srv", "1.0"))
     * ```
     *
     * @param version The deployment version identifying this worker
     * @param useVersioning If true (default), worker participates in versioned task routing
     * @param defaultVersioningBehavior Default behavior for workflows that don't specify their own
     */
    fun deployment(
        version: WorkerDeploymentVersion,
        useVersioning: Boolean = true,
        defaultVersioningBehavior: VersioningBehavior = VersioningBehavior.UNSPECIFIED,
    ) {
        deploymentOptions = WorkerDeploymentOptions(version, useVersioning, defaultVersioningBehavior)
    }

    /**
     * Configures worker deployment versioning using a builder pattern.
     *
     * Usage:
     * ```kotlin
     * deployment {
     *     deploymentName = "llm_srv"
     *     buildId = "1.0"
     *     useWorkerVersioning = true
     * }
     * ```
     */
    fun deployment(configure: DeploymentConfigBuilder.() -> Unit) {
        deploymentOptions = DeploymentConfigBuilder().apply(configure).build()
    }

    internal fun build(): TemporalApplication {
        val config =
            TemporalApplicationConfig(
                connection = connectionConfig,
                deployment = deploymentOptions,
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

/**
 * Builder for [WorkerDeploymentOptions] that allows DSL-style configuration.
 */
@TemporalDsl
class DeploymentConfigBuilder internal constructor() {
    /** Name of the deployment (e.g., "llm_srv", "payment-service"). */
    var deploymentName: String = ""

    /** Build ID within the deployment (e.g., "1.0", "v2.3.5"). */
    var buildId: String = ""

    /** If true, worker participates in versioned task routing. */
    var useWorkerVersioning: Boolean = true

    /**
     * Default versioning behavior for workflows that don't specify their own.
     * When [useWorkerVersioning] is true and this is [VersioningBehavior.UNSPECIFIED],
     * workflows MUST specify their own versioning behavior or they will fail at registration.
     */
    var defaultVersioningBehavior: VersioningBehavior = VersioningBehavior.UNSPECIFIED

    internal fun build(): WorkerDeploymentOptions {
        require(deploymentName.isNotBlank()) { "deploymentName must be set" }
        require(buildId.isNotBlank()) { "buildId must be set" }
        return WorkerDeploymentOptions(
            version = WorkerDeploymentVersion(deploymentName, buildId),
            useWorkerVersioning = useWorkerVersioning,
            defaultVersioningBehavior = defaultVersioningBehavior,
        )
    }
}
