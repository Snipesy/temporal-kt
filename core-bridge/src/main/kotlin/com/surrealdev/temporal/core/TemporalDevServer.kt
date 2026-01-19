package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.TemporalCoreEphemeralServer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * An ephemeral Temporal development server.
 *
 * This server is useful for testing and local development. It runs an
 * in-memory Temporal server that automatically downloads and starts
 * the Temporal CLI dev server.
 *
 * Example usage:
 * ```kotlin
 * TemporalRuntime.create().use { runtime ->
 *     TemporalDevServer.start(runtime).use { server ->
 *         println("Dev server running at: ${server.targetUrl}")
 *         // Connect clients to server.targetUrl
 *     }
 * }
 * ```
 */
class TemporalDevServer private constructor(
    private val serverPtr: MemorySegment,
    private val runtimePtr: MemorySegment,
    private val arena: Arena,
    val targetUrl: String,
) : AutoCloseable {
    @Volatile
    private var closed = false

    companion object {
        /**
         * Starts a new development server.
         *
         * This method blocks until the server is ready or an error occurs.
         *
         * @param runtime The Temporal runtime to use
         * @param namespace The namespace to create (default: "default")
         * @param ip The IP address to bind to (default: "127.0.0.1")
         * @param existingPath Path to existing Temporal CLI binary (optional, will download if not set)
         * @param downloadVersion Version to download (semver like "1.3.0", "latest", or "default"). Ignored if existingPath is set. Defaults to version from BuildConfig.
         * @param downloadTtlSeconds Cache duration for downloads in seconds (0 = no TTL, indefinite cache)
         * @param timeoutSeconds Maximum time to wait for server start
         * @return A running dev server instance
         * @throws TemporalCoreException if the server fails to start
         */
        fun start(
            runtime: TemporalRuntime,
            namespace: String = "default",
            ip: String = "127.0.0.1",
            existingPath: String? = null,
            downloadVersion: String? = BuildConfig.TEMPORAL_CLI_VERSION,
            downloadTtlSeconds: Long = 0,
            timeoutSeconds: Long = 120,
        ): TemporalDevServer {
            runtime.ensureOpen()

            val arena = Arena.ofShared()
            val future = CompletableFuture<TemporalDevServer>()

            try {
                TemporalCoreEphemeralServer.startDevServer(
                    runtimePtr = runtime.handle,
                    arena = arena,
                    namespace = namespace,
                    ip = ip,
                    existingPath = existingPath,
                    downloadVersion = downloadVersion,
                    downloadTtlSeconds = downloadTtlSeconds,
                ) { serverPtr, targetUrl, error ->
                    if (error != null) {
                        future.completeExceptionally(TemporalCoreException(error))
                    } else if (serverPtr == null || targetUrl == null) {
                        future.completeExceptionally(
                            TemporalCoreException("Dev server start returned null without error"),
                        )
                    } else {
                        future.complete(
                            TemporalDevServer(serverPtr, runtime.handle, arena, targetUrl),
                        )
                    }
                }

                return future.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: Exception) {
                arena.close()
                when (e) {
                    is TemporalCoreException -> {
                        throw e
                    }

                    is java.util.concurrent.TimeoutException -> {
                        throw TemporalCoreException("Dev server start timed out after ${timeoutSeconds}s")
                    }

                    else -> {
                        throw TemporalCoreException("Dev server start failed: ${e.message}", cause = e)
                    }
                }
            }
        }

        /**
         * Starts a new development server asynchronously.
         *
         * @param runtime The Temporal runtime to use
         * @param namespace The namespace to create (default: "default")
         * @param ip The IP address to bind to (default: "127.0.0.1")
         * @param existingPath Path to existing Temporal CLI binary (optional, will download if not set)
         * @param downloadVersion Version to download (semver like "1.3.0", "latest", or "default"). Ignored if existingPath is set. Defaults to version from BuildConfig.
         * @param downloadTtlSeconds Cache duration for downloads in seconds (0 = no TTL, indefinite cache)
         * @return A CompletableFuture that completes with the running server
         */
        fun startAsync(
            runtime: TemporalRuntime,
            namespace: String = "default",
            ip: String = "127.0.0.1",
            existingPath: String? = null,
            downloadVersion: String? = BuildConfig.TEMPORAL_CLI_VERSION,
            downloadTtlSeconds: Long = 0,
        ): CompletableFuture<TemporalDevServer> {
            runtime.ensureOpen()

            val arena = Arena.ofShared()
            val future = CompletableFuture<TemporalDevServer>()

            TemporalCoreEphemeralServer.startDevServer(
                runtimePtr = runtime.handle,
                arena = arena,
                namespace = namespace,
                ip = ip,
                existingPath = existingPath,
                downloadVersion = downloadVersion,
                downloadTtlSeconds = downloadTtlSeconds,
            ) { serverPtr, targetUrl, error ->
                if (error != null) {
                    arena.close()
                    future.completeExceptionally(TemporalCoreException(error))
                } else if (serverPtr == null || targetUrl == null) {
                    arena.close()
                    future.completeExceptionally(
                        TemporalCoreException("Dev server start returned null without error"),
                    )
                } else {
                    future.complete(
                        TemporalDevServer(serverPtr, runtime.handle, arena, targetUrl),
                    )
                }
            }

            return future
        }
    }

    /**
     * Checks if this server has been closed.
     */
    fun isClosed(): Boolean = closed

    /**
     * Shuts down and closes this dev server.
     *
     * This method blocks until the server is fully shut down.
     */
    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true

            val shutdownFuture = CompletableFuture<Unit>()

            TemporalCoreEphemeralServer.shutdownServer(
                serverPtr = serverPtr,
                arena = arena,
                runtimePtr = runtimePtr,
            ) { error ->
                if (error != null) {
                    shutdownFuture.completeExceptionally(TemporalCoreException(error))
                } else {
                    shutdownFuture.complete(Unit)
                }
            }

            try {
                shutdownFuture.get(30, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Ignore shutdown errors
            }

            TemporalCoreEphemeralServer.freeServer(serverPtr)
            arena.close()
        }
    }
}
