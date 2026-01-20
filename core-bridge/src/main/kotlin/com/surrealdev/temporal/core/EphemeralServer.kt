package com.surrealdev.temporal.core

/**
 * A sealed interface for ephemeral Temporal servers.
 *
 * This interface provides common functionality for both development servers
 * and test servers:
 * - [TemporalDevServer]: Real-time execution for local development
 * - [TemporalTestServer]: Time-skipping support for fast tests
 *
 * Both server types are useful for testing and local development, running
 * an in-memory Temporal server that automatically downloads and starts
 * the appropriate Temporal server binary.
 *
 * Example usage:
 * ```kotlin
 * TemporalRuntime.create().use { runtime ->
 *     // For local development (real-time)
 *     TemporalDevServer.start(runtime).use { server ->
 *         println("Dev server at: ${server.targetUrl}")
 *     }
 *
 *     // For tests with time skipping
 *     TemporalTestServer.start(runtime).use { server ->
 *         println("Test server at: ${server.targetUrl}")
 *         server.unlockTimeSkipping() // Enable auto time-skip
 *     }
 * }
 * ```
 */
sealed interface EphemeralServer : AutoCloseable {
    /**
     * The gRPC target URL for connecting to this server.
     *
     * This can be passed to [TemporalCoreClient.connect] to connect clients.
     */
    val targetUrl: String

    /**
     * Checks if this server has been closed.
     *
     * @return true if this server has been closed, false otherwise
     */
    fun isClosed(): Boolean
}
