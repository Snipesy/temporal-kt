package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.EphemeralServerCallbackDispatcher
import com.surrealdev.temporal.core.internal.FactoryArenaScope
import com.surrealdev.temporal.core.internal.TemporalCoreEphemeralServer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

            return FactoryArenaScope.create(runtime.handle, ::EphemeralServerCallbackDispatcher).createResource {
                val (serverPtr, targetUrl) =
                    suspendCancellableCoroutine { continuation ->
                        val contextPtr =
                            TemporalCoreEphemeralServer.startDevServer(
                                runtimePtr = runtime.handle,
                                arena = resourceArena,
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
                        val contextId = dispatcher.getContextId(contextPtr)
                        continuation.invokeOnCancellation {
                            dispatcher.cancelStart(contextId)
                        }
                    }

                TemporalDevServer(
                    serverPtr = serverPtr,
                    runtimePtr = runtime.handle,
                    arena = resourceArena,
                    callbackArena = callbackArena,
                    dispatcher = dispatcher,
                    targetUrl = targetUrl,
                )
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

            val scope = FactoryArenaScope.create(runtime.handle, ::EphemeralServerCallbackDispatcher)
            val future = CompletableFuture<TemporalDevServer>()
            val ownershipTransferred = AtomicBoolean(false)

            TemporalCoreEphemeralServer.startDevServer(
                runtimePtr = runtime.handle,
                arena = scope.resourceArena,
                dispatcher = scope.dispatcher,
                namespace = namespace,
                ip = ip,
                existingPath = existingPath,
                downloadVersion = downloadVersion,
                downloadTtlSeconds = downloadTtlSeconds,
            ) { serverPtr, targetUrl, error ->
                try {
                    if (error != null) {
                        future.completeExceptionally(TemporalCoreException(error))
                    } else if (serverPtr == null || targetUrl == null) {
                        future.completeExceptionally(
                            TemporalCoreException("Dev server start returned null without error"),
                        )
                    } else {
                        ownershipTransferred.set(true)
                        scope.transferOwnership()
                        future.complete(
                            TemporalDevServer(
                                serverPtr,
                                runtime.handle,
                                scope.resourceArena,
                                scope.callbackArena,
                                scope.dispatcher,
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
                    scope.close()
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
