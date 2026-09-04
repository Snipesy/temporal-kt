package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.EphemeralServerCallbackDispatcher
import com.surrealdev.temporal.core.internal.FactoryArenaScope
import com.surrealdev.temporal.core.internal.TemporalCoreEphemeralServer
import com.surrealdev.temporal.core.internal.nativeCallbackException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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
         * @param searchAttributes Custom search attributes to register with the server. Each pair is (name, type).
         *                         Type must be one of: "Keyword", "Text", "Int", "Double", "Bool", "Datetime", "KeywordList"
         * @param extraArgs Additional CLI arguments to pass to the server
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
            searchAttributes: List<Pair<String, String>> = emptyList(),
            extraArgs: List<String> = emptyList(),
        ): TemporalDevServer {
            runtime.ensureOpen()

            // Build CLI args for search attributes: --search-attribute Name=Type
            val allArgs =
                buildList {
                    for ((name, type) in searchAttributes) {
                        add("--search-attribute")
                        add("$name=$type")
                    }
                    addAll(extraArgs)
                }

            val server =
                FactoryArenaScope.create(runtime.handle, ::EphemeralServerCallbackDispatcher).createResource {
                    // The native start MUST be awaited to completion even if our caller is cancelled:
                    // the Rust callback always fires, and it targets the upcall stub owned by this
                    // scope. Abandoning the suspension here would free that stub (use-after-free,
                    // observed as a JVM crash) and leak the server process (never shut down).
                    // suspendCoroutine is not cancellable, so cancellation is deferred until below.
                    val (serverPtr, targetUrl) =
                        suspendCoroutine { continuation ->
                            TemporalCoreEphemeralServer.startDevServer(
                                runtimePtr = runtime.handle,
                                arena = resourceArena,
                                dispatcher = dispatcher,
                                namespace = namespace,
                                ip = ip,
                                existingPath = existingPath,
                                downloadVersion = downloadVersion,
                                downloadTtlSeconds = downloadTtlSeconds,
                                extraArgs = allArgs,
                            ) { serverPtr, targetUrl, error ->
                                if (error != null) {
                                    continuation.resumeWithException(nativeCallbackException(error))
                                } else if (serverPtr == null || targetUrl == null) {
                                    continuation.resumeWithException(
                                        nativeCallbackException("Server start returned null without error"),
                                    )
                                } else {
                                    continuation.resume(Pair(serverPtr, targetUrl))
                                }
                            }
                        }

                    TemporalDevServer(
                        serverPtr = serverPtr,
                        runtimePtr = runtime.handle,
                        arena = resourceArena,
                        callbackArena = callbackArena,
                        dispatcher = dispatcher,
                        targetUrl = targetUrl,
                    ).also { EphemeralServers.register(it) }
                }

            // Caller was cancelled while the native start was in flight: nobody will close the
            // server we just produced, so do it here and honor the cancellation.
            return server.closeIfCancelled()
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
         * @param searchAttributes Custom search attributes to register with the server. Each pair is (name, type).
         *                         Type must be one of: "Keyword", "Text", "Int", "Double", "Bool", "Datetime", "KeywordList"
         * @param extraArgs Additional CLI arguments to pass to the server
         * @return A CompletableFuture that completes with the running server
         */
        fun startAsync(
            runtime: TemporalRuntime,
            namespace: String = "default",
            ip: String = "127.0.0.1",
            existingPath: String? = null,
            downloadVersion: String? = BuildConfig.TEMPORAL_CLI_VERSION,
            downloadTtlSeconds: Long = 0,
            searchAttributes: List<Pair<String, String>> = emptyList(),
            extraArgs: List<String> = emptyList(),
        ): CompletableFuture<TemporalDevServer> {
            runtime.ensureOpen()

            // Build CLI args for search attributes: --search-attribute Name=Type
            val allArgs =
                buildList {
                    for ((name, type) in searchAttributes) {
                        add("--search-attribute")
                        add("$name=$type")
                    }
                    addAll(extraArgs)
                }

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
                extraArgs = allArgs,
            ) { serverPtr, targetUrl, error ->
                try {
                    if (error != null) {
                        future.completeExceptionally(nativeCallbackException(error))
                    } else if (serverPtr == null || targetUrl == null) {
                        future.completeExceptionally(
                            nativeCallbackException("Dev server start returned null without error"),
                        )
                    } else {
                        ownershipTransferred.set(true)
                        scope.transferOwnership()
                        val server =
                            TemporalDevServer(
                                serverPtr,
                                runtime.handle,
                                scope.resourceArena,
                                scope.callbackArena,
                                scope.dispatcher,
                                targetUrl,
                            )
                        future.complete(server)
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

            // Register off the FFM upcall thread: the start callback above runs inline on a Rust
            // thread, where file I/O, logging and exception construction are unsafe (see
            // nativeCallbackException). thenApplyAsync hands the server to a pool thread.
            return future.thenApplyAsync { server ->
                EphemeralServers.register(server)
                server
            }
        }
    }

    /**
     * Checks if this server has been closed.
     */
    override fun isClosed(): Boolean = closed

    // Synchronized with close(): the native handle is freed at the end of close().
    override val pid: Long?
        get() = synchronized(this) { if (closed) null else TemporalCoreEphemeralServer.pid(serverPtr) }

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
            EphemeralServers.unregister(this)

            val shutdownFuture = CompletableFuture<Unit>()

            TemporalCoreEphemeralServer.shutdownServer(
                serverPtr = serverPtr,
                dispatcher = dispatcher,
            ) { error ->
                if (error != null) {
                    shutdownFuture.completeExceptionally(nativeCallbackException(error))
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
