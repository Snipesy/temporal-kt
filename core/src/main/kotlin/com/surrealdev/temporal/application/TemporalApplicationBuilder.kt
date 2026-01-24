package com.surrealdev.temporal.application

import com.surrealdev.temporal.annotation.TemporalDsl
import kotlinx.coroutines.CoroutineDispatcher
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
    private var dispatcherOverride: CoroutineDispatcher? = null
    private var shutdownConfig: ShutdownConfig = ShutdownConfig()

    /**
     * Sets the base dispatcher for this application.
     *
     * This dispatcher becomes the foundation for all coroutine operations in the application.
     * Task queues can override with their own dispatchers via [TaskQueueBuilder.workflowDispatcher]
     * and [TaskQueueBuilder.activityDispatcher].
     *
     * Default: The dispatcher from [parentCoroutineContext], or [Dispatchers.Default] if none.
     *
     * Example:
     * ```kotlin
     * TemporalApplication {
     *     dispatcher = Dispatchers.Default.limitedParallelism(4)
     *     connection { ... }
     * }
     * ```
     */
    var dispatcher: CoroutineDispatcher
        get() =
            dispatcherOverride
                ?: (parentCoroutineContext[CoroutineDispatcher] ?: Dispatchers.Default)
        set(value) {
            dispatcherOverride = value
        }

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

    /**
     * Configure shutdown behavior.
     *
     * Usage:
     * ```kotlin
     * shutdown {
     *     gracePeriodMs = 5000
     *     forceTimeoutMs = 2000
     * }
     * ```
     */
    fun shutdown(block: ShutdownConfigBuilder.() -> Unit) {
        shutdownConfig = ShutdownConfigBuilder().apply(block).build()
    }

    internal fun build(): TemporalApplication {
        // Apply dispatcher override if set
        val effectiveContext =
            dispatcherOverride?.let {
                parentCoroutineContext + it
            } ?: parentCoroutineContext

        val config =
            TemporalApplicationConfig(
                connection = connectionConfig,
                deployment = deploymentOptions,
                shutdown = shutdownConfig,
            )
        val application = TemporalApplication(config, effectiveContext)

        return application
    }
}

/**
 * Builder for [ShutdownConfig] that allows DSL-style configuration.
 */
@TemporalDsl
class ShutdownConfigBuilder internal constructor() {
    /**
     * Grace period to wait for workers to complete gracefully.
     * After this timeout, workers will be force-cancelled.
     * Default: 10 seconds.
     */
    var gracePeriodMs: Long = 10_000L

    /**
     * Additional timeout after force cancellation to wait for cleanup.
     * Default: 5 seconds.
     */
    var forceTimeoutMs: Long = 5_000L

    internal fun build() =
        ShutdownConfig(
            gracePeriodMs = gracePeriodMs,
            forceTimeoutMs = forceTimeoutMs,
        )
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
