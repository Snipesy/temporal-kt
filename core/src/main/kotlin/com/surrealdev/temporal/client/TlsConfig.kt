package com.surrealdev.temporal.client

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * TLS configuration for secure connections to Temporal servers.
 *
 * Supports both server-only TLS (verifying the server's certificate) and mutual TLS (mTLS)
 * where the client also presents a certificate. This configuration is used when connecting
 * to Temporal Cloud or self-hosted Temporal servers with TLS enabled.
 *
 * ## Temporal Cloud Connection (Most Common)
 *
 * Temporal Cloud requires mTLS authentication. You need client certificates obtained from
 * your Temporal Cloud namespace:
 *
 * ```kotlin
 * val app = TemporalApplication {
 *     connection {
 *         target = "https://myns.abc123.tmprl.cloud:7233"
 *         namespace = "myns.abc123"
 *         tls {
 *             fromFiles(
 *                 clientCertPath = "/path/to/client.pem",
 *                 clientPrivateKeyPath = "/path/to/client-key.pem",
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * ## Self-Hosted Temporal with Custom CA
 *
 * For self-hosted Temporal servers using a private CA:
 *
 * ```kotlin
 * val app = TemporalApplication {
 *     connection {
 *         target = "https://temporal.internal:7233"
 *         namespace = "default"
 *         tls {
 *             fromFiles(
 *                 serverRootCaCertPath = "/path/to/ca.pem",
 *                 clientCertPath = "/path/to/client.pem",
 *                 clientPrivateKeyPath = "/path/to/client-key.pem",
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * ## Server-Only TLS (No Client Certificate)
 *
 * For servers that only require TLS verification without client authentication:
 *
 * ```kotlin
 * val tlsConfig = TlsConfig(
 *     serverRootCaCert = caCertBytes
 * )
 * ```
 *
 * ## Domain Override
 *
 * Use [domain] when the server certificate's CN/SAN doesn't match the connection address.
 * This is common when connecting via IP address or through a proxy:
 *
 * ```kotlin
 * val app = TemporalApplication {
 *     connection {
 *         target = "https://10.0.0.50:7233"  // Connecting via IP
 *         namespace = "default"
 *         tls {
 *             domain = "temporal.mycompany.com"  // Certificate's CN
 *             fromFiles(serverRootCaCertPath = "/path/to/ca.pem")
 *         }
 *     }
 * }
 * ```
 *
 * ## Loading Certificates Programmatically
 *
 * For secrets management integration (Vault, AWS Secrets Manager, etc.):
 *
 * ```kotlin
 * val tlsConfig = TlsConfig(
 *     clientCert = secretsManager.getSecret("temporal-client-cert").toByteArray(),
 *     clientPrivateKey = secretsManager.getSecret("temporal-client-key").toByteArray(),
 * )
 * ```
 *
 * ## Loading from Files
 *
 * The [fromFiles] factory methods support both [String] paths and [java.nio.file.Path] objects:
 *
 * ```kotlin
 * // Using string paths
 * val config1 = TlsConfig.fromFiles(
 *     serverRootCaCertPath = "/path/to/ca.pem",
 *     clientCertPath = "/path/to/client.pem",
 *     clientPrivateKeyPath = "/path/to/client-key.pem",
 * )
 *
 * // Using Path objects
 * val config2 = TlsConfig.fromFiles(
 *     serverRootCaCertPath = Path.of("/path/to/ca.pem"),
 *     clientCertPath = Path.of("/path/to/client.pem"),
 *     clientPrivateKeyPath = Path.of("/path/to/client-key.pem"),
 * )
 * ```
 *
 * ## Certificate Format
 *
 * All certificates and keys must be in PEM format. Example PEM file structure:
 *
 * ```
 * -----BEGIN CERTIFICATE-----
 * MIIBkTCB+wIJAKHBfpEgcM...
 * -----END CERTIFICATE-----
 * ```
 *
 * For private keys:
 * ```
 * -----BEGIN PRIVATE KEY-----
 * MIIEvgIBADANBgkqhkiG9w...
 * -----END PRIVATE KEY-----
 * ```
 *
 * ## Troubleshooting
 *
 * ### "Certificate verify failed" or "unknown CA"
 * - Ensure [serverRootCaCert] contains the CA that signed the server's certificate
 * - For Temporal Cloud, this is usually not needed (public CA is used)
 * - Check certificate expiration dates
 *
 * ### "Handshake failed"
 * - Verify the target URL uses `https://` prefix
 * - Ensure client certificate and key are a matching pair
 * - Check that the certificate is authorized for the namespace (Temporal Cloud)
 *
 * ### "Permission denied" on Temporal Cloud
 * - Verify the namespace name matches your Temporal Cloud namespace
 * - Ensure certificate filters are properly configured in Cloud UI
 *
 * ## Worker TLS
 *
 * Workers automatically inherit TLS configuration from the client connection.
 * There is no separate TLS configuration for workers - once the client connects
 * with TLS, all worker polling uses the same secure connection.
 *
 * @property serverRootCaCert PEM-encoded root CA certificate for verifying the server.
 *                            If null, the system's default trust store is used.
 *                            Required for self-hosted Temporal with private CAs.
 *                            Usually not needed for Temporal Cloud (uses public CAs).
 * @property domain Domain name to use for server certificate verification (SNI override).
 *                  Useful when connecting via IP address or when the certificate's
 *                  CN/SAN doesn't match the target host. Leave null to use the
 *                  target hostname from the connection URL.
 * @property clientCert PEM-encoded client certificate for mutual TLS authentication.
 *                      Required for Temporal Cloud and mTLS-enabled self-hosted servers.
 *                      Must be provided together with [clientPrivateKey].
 * @property clientPrivateKey PEM-encoded private key for the client certificate.
 *                            Must be provided together with [clientCert].
 * @see [fromFiles] for loading certificates from the filesystem
 */
data class TlsConfig(
    val serverRootCaCert: ByteArray? = null,
    val domain: String? = null,
    val clientCert: ByteArray? = null,
    val clientPrivateKey: ByteArray? = null,
) {
    init {
        require((clientCert == null) == (clientPrivateKey == null)) {
            "Must provide both clientCert and clientPrivateKey for mTLS, or neither"
        }
    }

    /**
     * Returns true if this configuration uses mutual TLS (client certificate authentication).
     */
    val isMtls: Boolean
        get() = clientCert != null && clientPrivateKey != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TlsConfig

        if (serverRootCaCert != null) {
            if (other.serverRootCaCert == null) return false
            if (!serverRootCaCert.contentEquals(other.serverRootCaCert)) return false
        } else if (other.serverRootCaCert != null) {
            return false
        }

        if (domain != other.domain) return false

        if (clientCert != null) {
            if (other.clientCert == null) return false
            if (!clientCert.contentEquals(other.clientCert)) return false
        } else if (other.clientCert != null) {
            return false
        }

        if (clientPrivateKey != null) {
            if (other.clientPrivateKey == null) return false
            if (!clientPrivateKey.contentEquals(other.clientPrivateKey)) return false
        } else if (other.clientPrivateKey != null) {
            return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = serverRootCaCert?.contentHashCode() ?: 0
        result = 31 * result + (domain?.hashCode() ?: 0)
        result = 31 * result + (clientCert?.contentHashCode() ?: 0)
        result = 31 * result + (clientPrivateKey?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Creates a TlsConfig by loading certificates from file paths.
         *
         * @param serverRootCaCertPath Path to PEM-encoded CA certificate file, or null to use system defaults.
         * @param domain Domain name for server certificate verification, or null.
         * @param clientCertPath Path to PEM-encoded client certificate file, or null.
         * @param clientPrivateKeyPath Path to PEM-encoded client private key file, or null.
         * @return A TlsConfig with the loaded certificates.
         * @throws java.io.IOException if any specified file cannot be read.
         */
        @JvmStatic
        fun fromFiles(
            serverRootCaCertPath: String? = null,
            domain: String? = null,
            clientCertPath: String? = null,
            clientPrivateKeyPath: String? = null,
        ): TlsConfig =
            TlsConfig(
                serverRootCaCert = serverRootCaCertPath?.let { readFile(it) },
                domain = domain,
                clientCert = clientCertPath?.let { readFile(it) },
                clientPrivateKey = clientPrivateKeyPath?.let { readFile(it) },
            )

        /**
         * Creates a TlsConfig by loading certificates from Path objects.
         *
         * @param serverRootCaCertPath Path to PEM-encoded CA certificate file, or null to use system defaults.
         * @param domain Domain name for server certificate verification, or null.
         * @param clientCertPath Path to PEM-encoded client certificate file, or null.
         * @param clientPrivateKeyPath Path to PEM-encoded client private key file, or null.
         * @return A TlsConfig with the loaded certificates.
         * @throws java.io.IOException if any specified file cannot be read.
         */
        @JvmStatic
        fun fromFiles(
            serverRootCaCertPath: Path? = null,
            domain: String? = null,
            clientCertPath: Path? = null,
            clientPrivateKeyPath: Path? = null,
        ): TlsConfig =
            TlsConfig(
                serverRootCaCert = serverRootCaCertPath?.let { Files.readAllBytes(it) },
                domain = domain,
                clientCert = clientCertPath?.let { Files.readAllBytes(it) },
                clientPrivateKey = clientPrivateKeyPath?.let { Files.readAllBytes(it) },
            )

        private fun readFile(path: String): ByteArray = File(path).readBytes()
    }
}
