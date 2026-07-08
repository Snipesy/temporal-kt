package com.surrealdev.temporal.core

import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import com.surrealdev.temporal.core.internal.ClientCallbackDispatcher
import com.surrealdev.temporal.core.internal.ClientTlsOptions
import com.surrealdev.temporal.core.internal.FactoryArenaScope
import com.surrealdev.temporal.core.internal.nativeCallbackException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.surrealdev.temporal.core.internal.TemporalCoreClient as InternalClient

/**
 * Options for configuring a Temporal client connection.
 */
data class ClientOptions(
    val clientName: String = "temporal-kotlin",
    val clientVersion: String = BuildConfig.SDK_VERSION,
    val identity: String? = null,
)

private fun TlsConfig.toClientTlsOptions(): ClientTlsOptions =
    ClientTlsOptions(
        serverRootCaCert = serverRootCaCert,
        domain = domain,
        clientCert = clientCert,
        clientPrivateKey = clientPrivateKey,
    )

/**
 * A high-level wrapper for the Temporal Core client.
 *
 * This class manages the lifecycle of a client connection to a Temporal server.
 * It wraps the low-level FFM bindings and provides a coroutine-friendly API.
 *
 * Example usage:
 * ```kotlin
 * TemporalRuntime.create().use { runtime ->
 *     val client = TemporalCoreClient.connect(runtime, "localhost:7233", "default")
 *     try {
 *         // Use the client...
 *     } finally {
 *         client.close()
 *     }
 * }
 * ```
 */
class TemporalCoreClient private constructor(
    internal val handle: MemorySegment,
    private val arena: Arena,
    private val callbackArena: Arena,
    private val dispatcher: ClientCallbackDispatcher,
    val targetUrl: String,
    val namespace: String,
) : AutoCloseable {
    @Volatile
    private var closed = false

    companion object {
        private val logger = LoggerFactory.getLogger(TemporalCoreClient::class.java)

        /**
         * Connects to a Temporal server asynchronously.
         *
         * TLS is automatically enabled when the target URL uses the `https://` scheme,
         * or when an API key is provided.
         * For custom CA certificates, client certificates (mTLS), or domain overrides,
         * provide a [TlsConfig] instance.
         *
         * @param runtime The Temporal runtime to use
         * @param targetUrl The server address (e.g., "localhost:7233" or "myns.tmprl.cloud:7233").
         *                  Scheme is optional — if omitted, `http://` or `https://` is inferred from TLS settings.
         * @param namespace The namespace to use (default: "default")
         * @param options Additional client options
         * @param tls TLS configuration. If null and URL is https:// or apiKey is set, uses system CA certificates.
         *            Provide a [TlsConfig] for custom CA certificates, client certificates (mTLS), or domain overrides.
         * @param apiKey API key for Temporal Cloud authentication (alternative to mTLS).
         *               When set, TLS is auto-enabled unless [tlsDisabled] is true.
         * @param tlsDisabled Explicitly disable TLS even when an API key is set. Useful for testing through proxies.
         * @return A connected client instance
         * @throws TemporalCoreException if connection fails
         */
        suspend fun connect(
            runtime: TemporalRuntime,
            targetUrl: String,
            namespace: String = "default",
            options: ClientOptions = ClientOptions(),
            tls: TlsConfig? = null,
            apiKey: String? = null,
            tlsDisabled: Boolean = false,
        ): TemporalCoreClient {
            runtime.ensureOpen()

            // Warn about contradictory TLS configurations
            if (tlsDisabled && tls != null) {
                logger.warn("tlsDisabled=true but explicit TLS config was provided. TLS will NOT be used.")
            }
            if (tlsDisabled && targetUrl.startsWith("https://", ignoreCase = true)) {
                logger.warn("tlsDisabled=true but target URL uses https:// scheme. TLS will NOT be used.")
            }

            // Determine effective TLS configuration
            val effectiveTls =
                when {
                    tlsDisabled -> null
                    tls != null -> tls.toClientTlsOptions()
                    targetUrl.startsWith("https://", ignoreCase = true) -> ClientTlsOptions()
                    apiKey != null -> ClientTlsOptions()
                    else -> null
                }

            // Normalize target URL — Rust Core SDK requires a scheme
            val normalizedUrl =
                when {
                    targetUrl.startsWith("http://", ignoreCase = true) ||
                        targetUrl.startsWith("https://", ignoreCase = true) -> targetUrl

                    effectiveTls != null -> "https://$targetUrl"

                    else -> "http://$targetUrl"
                }

            val scope = FactoryArenaScope.create(runtime.handle, ::ClientCallbackDispatcher)

            return try {
                val clientPtr =
                    suspendCancellableCoroutine { continuation ->
                        val contextPtr =
                            InternalClient.connect(
                                runtimePtr = runtime.handle,
                                optionsArena = scope.resourceArena,
                                dispatcher = scope.dispatcher,
                                targetUrl = normalizedUrl,
                                namespace = namespace,
                                clientName = options.clientName,
                                clientVersion = options.clientVersion,
                                identity = options.identity,
                                tls = effectiveTls,
                                apiKey = apiKey,
                            ) { clientPtr, error ->
                                try {
                                    when {
                                        error != null -> {
                                            continuation.resumeWithException(nativeCallbackException(error))
                                        }

                                        clientPtr != null -> {
                                            continuation.resume(clientPtr)
                                        }

                                        else -> {
                                            continuation.resumeWithException(
                                                nativeCallbackException("Connect returned null without error"),
                                            )
                                        }
                                    }
                                } catch (_: IllegalStateException) {
                                    // Continuation already resumed, ignore
                                }
                            }

                        // Note: We intentionally do NOT cancel on coroutine cancellation.
                        // The Rust callback will always fire, and we must wait for it to complete.
                    }

                scope.transferOwnership()
                TemporalCoreClient(
                    handle = clientPtr,
                    arena = scope.resourceArena,
                    callbackArena = scope.callbackArena,
                    dispatcher = scope.dispatcher,
                    targetUrl = normalizedUrl,
                    namespace = namespace,
                )
            } catch (e: Exception) {
                scope.close()
                throw e
            }
        }
    }

    /**
     * Checks if this client has been closed.
     */
    fun isClosed(): Boolean = closed

    /**
     * Ensures the client is not closed before performing an operation.
     * @throws IllegalStateException if the client is closed
     */
    internal fun ensureOpen() {
        if (closed) {
            throw IllegalStateException("Client has been closed")
        }
    }

    /**
     * Makes an RPC call to the Temporal workflow service with zero-copy protobuf serialization and parsing.
     *
     * Uses reusable callback stubs via the dispatcher for better performance.
     * Both request serialization and response parsing use zero-copy:
     * - Request is serialized directly to native memory without intermediate ByteArray
     * - Response is parsed directly from native memory without intermediate ByteArray copy
     *
     * @param rpc The RPC method name (e.g., "StartWorkflowExecution")
     * @param request The request protobuf message
     * @param parser Function that parses the CodedInputStream into the response type
     * @return The parsed response
     * @throws TemporalCoreException if the RPC call fails
     */
    suspend fun <Req : MessageLite, Resp : MessageLite> workflowServiceCall(
        rpc: String,
        request: Req,
        timeoutMillis: Int = 0,
        parser: (CodedInputStream) -> Resp,
    ): Resp = rpcCallInternal(InternalClient.RpcService.WORKFLOW, rpc, request, parser, timeoutMillis)

    /**
     * Makes an RPC call to the Temporal test service with zero-copy protobuf serialization and parsing.
     *
     * This is only available when connected to a test server with time-skipping enabled.
     * Uses reusable callback stubs via the dispatcher for better performance.
     * Both request serialization and response parsing use zero-copy:
     * - Request is serialized directly to native memory without intermediate ByteArray
     * - Response is parsed directly from native memory without intermediate ByteArray copy
     *
     * @param rpc The RPC method name (e.g., "LockTimeSkipping", "GetCurrentTime")
     * @param request The request protobuf message
     * @param parser Function that parses the CodedInputStream into the response type
     * @return The parsed response
     * @throws TemporalCoreException if the RPC call fails
     */
    suspend fun <Req : MessageLite, Resp : MessageLite> testServiceCall(
        rpc: String,
        request: Req,
        timeoutMillis: Int = 0,
        parser: (CodedInputStream) -> Resp,
    ): Resp = rpcCallInternal(InternalClient.RpcService.TEST, rpc, request, parser, timeoutMillis)

    // ============================================================
    // Private RPC Helpers
    // ============================================================

    private suspend fun <Req : MessageLite, Resp : MessageLite> rpcCallInternal(
        service: InternalClient.RpcService,
        rpc: String,
        request: Req,
        parser: (CodedInputStream) -> Resp,
        timeoutMillis: Int = 0,
    ): Resp {
        ensureOpen()
        return dispatcher.withManagedArena { arena, continuation ->
            InternalClient.rpcCall(
                clientPtr = handle,
                arena = arena,
                dispatcher = dispatcher,
                service = service,
                rpc = rpc,
                request = request,
                parser = parser,
                timeoutMillis = timeoutMillis,
            ) { response, statusCode, failureMessage, _ ->
                with(dispatcher) { continuation.resumeRpcResult(response, statusCode, failureMessage) }
            }
        }
    }

    /**
     * Closes this client and releases all associated resources.
     *
     * After calling this method, the client can no longer be used.
     */
    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true

            // MUST await BEFORE freeing - Tokio tasks hold references to Client
            val completed = dispatcher.awaitPendingCallbacks(timeoutSeconds = 60)
            if (!completed) {
                logger.warn(
                    "[TemporalCoreClient] Timeout waiting for pending callbacks during close(). " +
                        "Proceeding with cleanup anyway. This may indicate a Rust panic or stuck gRPC call.",
                )
            }

            // NOW safe to free (or as safe as we can make it)
            InternalClient.freeClient(handle)

            dispatcher.close()
            arena.close()
            callbackArena.close()
        }
    }
}
