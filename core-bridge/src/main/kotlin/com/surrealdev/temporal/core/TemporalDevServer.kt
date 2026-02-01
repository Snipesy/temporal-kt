package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.EphemeralServerCallbackDispatcher
import com.surrealdev.temporal.core.internal.TemporalCoreEphemeralServer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An ephemeral Temporal development server.
 *
 * This server is useful for local development. It runs an in-memory Temporal
 * server that automatically downloads and starts the Temporal CLI dev server.
 * Time flows at real-time pace.
 *
 * For testing with time-skipping, use [TemporalTestServer] instead.
 *
 * Example usage:
 * ```kotlin
 * TemporalRuntime.create().use { runtime ->
 *     TemporalDevServer.start(runtime).use { server ->
 *         println("Dev server running at: ${server.targetUrl}")
 *     }
 * }
 * ```
 */
class TemporalDevServer private constructor(
    private val serverPtr: MemorySegment,
    private val runtimePtr: MemorySegment,
    private val arena: Arena,
    private val callbackArena: Arena,
    private val dispatcher: EphemeralServerCallbackDispatcher,
    override val targetUrl: String,
) : EphemeralServer {
    @Volatile
    private var closed = false

    companion object {
        /**
         * Starts a new development server.
         *
         * Uses reusable callback stubs via the dispatcher for better performance.
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
        suspend fun start(
            runtime: TemporalRuntime,
            namespace: String = "default",
            ip: String = "127.0.0.1",
            existingPath: String? = null,
            downloadVersion: String? = BuildConfig.TEMPORAL_CLI_VERSION,
            downloadTtlSeconds: Long = 0,
        ): TemporalDevServer {
            runtime.ensureOpen()

            val arena = Arena.ofShared()
            val callbackArena = Arena.ofShared()
            val dispatcher = EphemeralServerCallbackDispatcher(callbackArena, runtime.handle)

            return try {
                val (serverPtr, targetUrl) =
                    suspendCancellableCoroutine { continuation ->
                        var contextId: Long = 0
                        val contextPtr =
                            TemporalCoreEphemeralServer.startDevServer(
                                runtimePtr = runtime.handle,
                                arena = arena,
                                dispatcher = dispatcher,
                                namespace = namespace,
                                ip = ip,
                                existingPath = existingPath,
                                downloadVersion = downloadVersion,
                                downloadTtlSeconds = downloadTtlSeconds,
                            ) { serverPtr, targetUrl, error ->
                                try {
                                    if (error != null) {
                                        continuation.resumeWithException(TemporalCoreException(error))
                                    } else if (serverPtr == null || targetUrl == null) {
                                        continuation.resumeWithException(
                                            TemporalCoreException("Server start returned null without error"),
                                        )
                                    } else {
                                        continuation.resume(Pair(serverPtr, targetUrl))
                                    }
                                } catch (_: IllegalStateException) {
                                    // Continuation already resumed, ignore
                                }
                            }
                        contextId = dispatcher.getContextId(contextPtr)
                        continuation.invokeOnCancellation {
                            dispatcher.cancelStart(contextId)
                        }
                    }

                TemporalDevServer(
                    serverPtr = serverPtr,
                    runtimePtr = runtime.handle,
                    arena = arena,
                    callbackArena = callbackArena,
                    dispatcher = dispatcher,
                    targetUrl = targetUrl,
                )
            } catch (e: Exception) {
                dispatcher.close()
                callbackArena.close()
                arena.close()
                when (e) {
                    is TemporalCoreException -> throw e
                    is java.util.concurrent.TimeoutException ->
                        throw TemporalCoreException("Server start timed out")
                    else -> throw TemporalCoreException("Server start failed: ${e.message}", cause = e)
                }
            }
        }

        /**
         * Starts a new development server asynchronously.
         *
         * Uses reusable callback stubs via the dispatcher for better performance.
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
            val callbackArena = Arena.ofShared()
            val dispatcher = EphemeralServerCallbackDispatcher(callbackArena, runtime.handle)
            val future = CompletableFuture<TemporalDevServer>()

            // Track whether ownership was transferred to the server
            val ownershipTransferred =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)

            TemporalCoreEphemeralServer.startDevServer(
                runtimePtr = runtime.handle,
                arena = arena,
                dispatcher = dispatcher,
                namespace = namespace,
                ip = ip,
                existingPath = existingPath,
                downloadVersion = downloadVersion,
                downloadTtlSeconds = downloadTtlSeconds,
            ) { serverPtr, targetUrl, error ->
                // Don't close arenas in callback - they will be closed when future completes exceptionally
                // or transferred to the server on success
                try {
                    if (error != null) {
                        future.completeExceptionally(TemporalCoreException(error))
                    } else if (serverPtr == null || targetUrl == null) {
                        future.completeExceptionally(
                            TemporalCoreException("Dev server start returned null without error"),
                        )
                    } else {
                        ownershipTransferred.set(true)
                        future.complete(
                            TemporalDevServer(
                                serverPtr,
                                runtime.handle,
                                arena,
                                callbackArena,
                                dispatcher,
                                targetUrl,
                            ),
                        )
                    }
                } catch (_: Exception) {
                    // Callback already completed, ignore
                }
            }

            // Close arenas if the future completes exceptionally (ownership wasn't transferred)
            future.whenComplete { _, throwable ->
                if (throwable != null && !ownershipTransferred.get()) {
                    dispatcher.close()
                    callbackArena.close()
                    arena.close()
                }
            }

            return future
        }
    }

    /**
     * Checks if this server has been closed.
     */
    override fun isClosed(): Boolean = closed

    /**
     * Shuts down and closes this dev server.
     *
     * This method blocks until the server is fully shut down.
     * Uses reusable callback stubs via the dispatcher.
     */
    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true

            val shutdownFuture = CompletableFuture<Unit>()

            TemporalCoreEphemeralServer.shutdownServer(
                serverPtr = serverPtr,
                dispatcher = dispatcher,
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

            // Close dispatcher first to cancel any pending callbacks
            // Late-firing callbacks will see null and just free Rust memory
            dispatcher.close()
            TemporalCoreEphemeralServer.freeServer(serverPtr)
            arena.close()
            callbackArena.close()
        }
    }
}
