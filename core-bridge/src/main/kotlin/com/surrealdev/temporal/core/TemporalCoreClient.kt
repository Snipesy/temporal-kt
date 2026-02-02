package com.surrealdev.temporal.core

import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import com.surrealdev.temporal.core.internal.ClientCallbackDispatcher
import com.surrealdev.temporal.core.internal.ClientTlsOptions
import com.surrealdev.temporal.core.internal.PendingCallbacks
import kotlinx.coroutines.suspendCancellableCoroutine
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
    val clientVersion: String = "0.1.0",
    val identity: String? = null,
)

/**
 * TLS configuration for secure connections to Temporal servers.
 *
 * This is the core-bridge level TLS configuration that maps to the FFM bindings.
 *
 * @property serverRootCaCert PEM-encoded root CA certificate for verifying the server.
 *                            If null, the system's default trust store is used.
 * @property domain Domain name for server certificate verification.
 * @property clientCert PEM-encoded client certificate for mTLS.
 * @property clientPrivateKey PEM-encoded client private key for mTLS.
 */
data class TlsOptions(
    val serverRootCaCert: ByteArray? = null,
    val domain: String? = null,
    val clientCert: ByteArray? = null,
    val clientPrivateKey: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TlsOptions

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

    internal fun toInternal(): ClientTlsOptions =
        ClientTlsOptions(
            serverRootCaCert = serverRootCaCert,
            domain = domain,
            clientCert = clientCert,
            clientPrivateKey = clientPrivateKey,
        )
}

/**
 * A high-level wrapper for the Temporal Core client.
 *
 * This class manages the lifecycle of a client connection to a Temporal server.
 * It wraps the low-level FFM bindings and provides a coroutine-friendly API.
 *
 * Example usage:
 * ```kotlin
 * TemporalRuntime.create().use { runtime ->
 *     val client = TemporalCoreClient.connect(runtime, "http://localhost:7233", "default")
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
        /**
         * Connects to a Temporal server asynchronously.
         *
         * TLS is automatically enabled when the target URL uses the `https://` scheme,
         * or when an API key is provided.
         * For custom CA certificates, client certificates (mTLS), or domain overrides,
         * provide a [TlsOptions] instance.
         *
         * @param runtime The Temporal runtime to use
         * @param targetUrl The server URL (e.g., "http://localhost:7233" or "https://my-namespace.tmprl.cloud:7233")
         * @param namespace The namespace to use (default: "default")
         * @param options Additional client options
         * @param tls TLS configuration. If null and URL is https:// or apiKey is set, uses system CA certificates.
         * @param apiKey API key for Temporal Cloud authentication (alternative to mTLS)
         * @return A connected client instance
         * @throws TemporalCoreException if connection fails
         */
        suspend fun connect(
            runtime: TemporalRuntime,
            targetUrl: String,
            namespace: String = "default",
            options: ClientOptions = ClientOptions(),
            tls: TlsOptions? = null,
            apiKey: String? = null,
        ): TemporalCoreClient {
            runtime.ensureOpen()

            // Auto-enable TLS for https:// URLs or when API key is provided
            val effectiveTls =
                when {
                    tls != null -> tls.toInternal()

                    targetUrl.startsWith("https://", ignoreCase = true) -> ClientTlsOptions()

                    apiKey != null -> ClientTlsOptions()

                    // API key requires TLS
                    else -> null
                }

            val callbackArena = Arena.ofShared()
            val dispatcher = ClientCallbackDispatcher(callbackArena, runtime.handle)

            // Use dispatcher-based connect with reusable callback stub
            // The optionsArena is NOT registered with the callback because its lifetime
            // should match the client's lifetime (it contains options data)
            val optionsArena = Arena.ofShared()
            var ownershipTransferred = false

            return try {
                val clientPtr =
                    suspendCancellableCoroutine { continuation ->
                        val contextPtr =
                            InternalClient.connect(
                                runtimePtr = runtime.handle,
                                optionsArena = optionsArena,
                                dispatcher = dispatcher,
                                targetUrl = targetUrl,
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
                                            continuation.resumeWithException(TemporalCoreException(error))
                                        }

                                        clientPtr != null -> {
                                            ownershipTransferred = true
                                            continuation.resume(clientPtr)
                                        }

                                        else -> {
                                            continuation.resumeWithException(
                                                TemporalCoreException("Connect returned null without error"),
                                            )
                                        }
                                    }
                                } catch (_: IllegalStateException) {
                                    // Continuation already resumed, ignore
                                }
                            }

                        val contextId = PendingCallbacks.getContextId(contextPtr)
                        continuation.invokeOnCancellation {
                            dispatcher.cancelConnect(contextId)
                            // Close arena on cancellation since no client will be created
                            optionsArena.close()
                        }
                    }

                TemporalCoreClient(
                    handle = clientPtr,
                    arena = optionsArena,
                    callbackArena = callbackArena,
                    dispatcher = dispatcher,
                    targetUrl = targetUrl,
                    namespace = namespace,
                )
            } catch (e: Exception) {
                // On error, close the options arena only if ownership wasn't transferred
                if (!ownershipTransferred) {
                    try {
                        optionsArena.close()
                    } catch (_: IllegalStateException) {
                        // Arena already closed by cancellation handler
                    }
                }
                dispatcher.close()
                callbackArena.close()
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
        parser: (CodedInputStream) -> Resp,
    ): Resp = rpcCallInternal(InternalClient.RpcService.WORKFLOW, rpc, request, parser)

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
        parser: (CodedInputStream) -> Resp,
    ): Resp = rpcCallInternal(InternalClient.RpcService.TEST, rpc, request, parser)

    // ============================================================
    // Private RPC Helpers
    // ============================================================

    private suspend fun <Req : MessageLite, Resp : MessageLite> rpcCallInternal(
        service: InternalClient.RpcService,
        rpc: String,
        request: Req,
        parser: (CodedInputStream) -> Resp,
    ): Resp {
        ensureOpen()
        return dispatcher.withManagedArena { arena, continuation ->
            val contextPtr =
                InternalClient.rpcCall(
                    clientPtr = handle,
                    arena = arena,
                    dispatcher = dispatcher,
                    service = service,
                    rpc = rpc,
                    request = request,
                    parser = parser,
                ) { response, statusCode, failureMessage, _ ->
                    with(dispatcher) { continuation.resumeRpcResult(response, statusCode, failureMessage) }
                }
            val contextId = dispatcher.getContextId(contextPtr);
            { dispatcher.cancelRpc(contextId) }
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
            InternalClient.freeClient(handle)
            dispatcher.close()
            arena.close()
            callbackArena.close()
        }
    }
}
