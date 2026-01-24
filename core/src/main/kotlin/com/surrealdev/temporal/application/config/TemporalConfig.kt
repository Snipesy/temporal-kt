package com.surrealdev.temporal.application.config

import com.surrealdev.temporal.application.ConnectionConfig

/**
 * Root configuration class for Hoplite-based YAML configuration.
 *
 * Example YAML:
 * ```yaml
 * temporal:
 *   connection:
 *     target: "http://localhost:7233"
 *     namespace: "my-namespace"
 *   deployment:
 *     deploymentName: "llm_srv"
 *     buildId: "1.0"
 *     useWorkerVersioning: true
 *   modules:
 *     - com.example.modules.OrdersModuleKt.ordersModule
 * ```
 */
data class TemporalConfig(
    val temporal: TemporalRootConfig = TemporalRootConfig(),
)

/**
 * Deployment configuration for worker versioning from YAML.
 *
 * @property deploymentName Name of the deployment (e.g., "llm_srv", "payment-service")
 * @property buildId Build ID within the deployment (e.g., "1.0", "v2.3.5")
 * @property useWorkerVersioning If true, worker participates in versioned task routing (default: true)
 * @property defaultVersioningBehavior Default behavior for workflows: "UNSPECIFIED", "PINNED", or "AUTO_UPGRADE"
 */
data class DeploymentConfig(
    val deploymentName: String = "",
    val buildId: String = "",
    val useWorkerVersioning: Boolean = true,
    val defaultVersioningBehavior: String = "UNSPECIFIED",
)

/**
 * Root temporal configuration containing connection settings and module declarations.
 */
data class TemporalRootConfig(
    /** Connection configuration for the Temporal service. */
    val connection: ConnectionConfig = ConnectionConfig(),
    /** Deployment configuration for worker versioning. */
    val deployment: DeploymentConfig? = null,
    /** List of fully-qualified module function names to load. */
    val modules: List<String> = emptyList(),
)
