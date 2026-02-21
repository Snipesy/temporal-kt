package com.surrealdev.temporal.core

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
 * @property serverRootCaCert PEM-encoded root CA certificate for verifying the server.
 *                            If null, the system's default trust store is used.
 * @property domain Domain name for server certificate verification (SNI override).
 * @property clientCert PEM-encoded client certificate for mutual TLS authentication.
 *                      Must be provided together with [clientPrivateKey].
 * @property clientPrivateKey PEM-encoded private key for the client certificate.
 *                            Must be provided together with [clientCert].
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
                serverRootCaCert = serverRootCaCertPath?.let { File(it).readBytes() },
                domain = domain,
                clientCert = clientCertPath?.let { File(it).readBytes() },
                clientPrivateKey = clientPrivateKeyPath?.let { File(it).readBytes() },
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
    }
}
