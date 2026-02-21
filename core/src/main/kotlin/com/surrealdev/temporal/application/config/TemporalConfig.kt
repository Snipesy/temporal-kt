package com.surrealdev.temporal.application.config

import com.surrealdev.temporal.application.ConnectionConfig
import com.surrealdev.temporal.core.TlsConfig
import java.io.File

/**
 * Root configuration class for Hoplite-based YAML configuration.
 *
 * Example YAML:
 * ```yaml
 * temporal:
 *   connection:
 *     target: "https://myns.abc123.tmprl.cloud:7233"
 *     namespace: "myns.abc123"
 *     tls:
 *       clientCertPath: "/path/to/client.pem"
 *       clientKeyPath: "/path/to/client-key.pem"
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
 * TLS configuration for YAML with file paths.
 *
 * Example YAML:
 * ```yaml
 * tls:
 *   clientCertPath: "/path/to/client.pem"
 *   clientKeyPath: "/path/to/client-key.pem"
 *   serverCaCertPath: "/path/to/ca.pem"  # optional
 *   domain: "temporal.example.com"        # optional
 * ```
 *
 * Paths can be absolute or use environment variable substitution via Hoplite.
 */
data class TlsYamlConfig(
    /** Path to the client certificate PEM file. */
    val clientCertPath: String? = null,
    /** Path to the client private key PEM file. */
    val clientKeyPath: String? = null,
    /** Path to the server CA certificate PEM file. */
    val serverCaCertPath: String? = null,
    /** Domain override for server certificate verification. */
    val domain: String? = null,
) {
    /**
     * Converts this YAML config to a [TlsConfig] by loading certificate files.
     *
     * @throws java.io.FileNotFoundException if any specified file doesn't exist
     */
    fun toTlsConfig(): TlsConfig? {
        // If no paths are specified and no domain, return null
        if (clientCertPath == null && clientKeyPath == null && serverCaCertPath == null && domain == null) {
            return null
        }

        return TlsConfig(
            clientCert = clientCertPath?.let { loadFile(it) },
            clientPrivateKey = clientKeyPath?.let { loadFile(it) },
            serverRootCaCert = serverCaCertPath?.let { loadFile(it) },
            domain = domain,
        )
    }

    private fun loadFile(path: String): ByteArray = File(path).readBytes()
}

/**
 * Connection configuration for YAML.
 *
 * Example YAML:
 * ```yaml
 * connection:
 *   target: "https://myns.abc123.tmprl.cloud:7233"
 *   namespace: "myns.abc123"
 *   tls:
 *     clientCertPath: "/path/to/client.pem"
 *     clientKeyPath: "/path/to/client-key.pem"
 * ```
 *
 * Or with API key:
 * ```yaml
 * connection:
 *   target: "https://myns.abc123.tmprl.cloud:7233"
 *   namespace: "myns.abc123"
 *   apiKey: ${TEMPORAL_API_KEY}
 * ```
 */
data class ConnectionYamlConfig(
    /** Target address (e.g., "http://localhost:7233" or "https://my-namespace.tmprl.cloud:7233"). */
    val target: String = "http://localhost:7233",
    /** Namespace to use. */
    val namespace: String = "default",
    /** TLS configuration with file paths. */
    val tls: TlsYamlConfig? = null,
    /** API key for Temporal Cloud authentication (alternative to mTLS). */
    val apiKey: String? = null,
) {
    /**
     * Converts this YAML config to a [ConnectionConfig] by loading TLS certificate files.
     */
    fun toConnectionConfig(): ConnectionConfig =
        ConnectionConfig(
            target = target,
            namespace = namespace,
            tls = tls?.toTlsConfig(),
            apiKey = apiKey,
        )
}

/**
 * Root temporal configuration containing connection settings and module declarations.
 */
data class TemporalRootConfig(
    /** Connection configuration for the Temporal service. */
    val connection: ConnectionYamlConfig = ConnectionYamlConfig(),
    /** Deployment configuration for worker versioning. */
    val deployment: DeploymentConfig? = null,
    /** List of fully-qualified module function names to load. */
    val modules: List<String> = emptyList(),
)
