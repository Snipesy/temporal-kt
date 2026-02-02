package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreByteArrayRefArray
import io.temporal.sdkbridge.TemporalCoreClientOptions
import io.temporal.sdkbridge.TemporalCoreClientTlsOptions
import io.temporal.sdkbridge.TemporalCoreRpcCallOptions
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

/**
 * TLS options for client connection (internal bridge representation).
 *
 * @property serverRootCaCert PEM-encoded root CA certificate
 * @property domain Domain name for server certificate verification
 * @property clientCert PEM-encoded client certificate for mTLS
 * @property clientPrivateKey PEM-encoded client private key for mTLS
 */
internal data class ClientTlsOptions(
    val serverRootCaCert: ByteArray? = null,
    val domain: String? = null,
    val clientCert: ByteArray? = null,
    val clientPrivateKey: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ClientTlsOptions

        if (!serverRootCaCert.contentEquals(other.serverRootCaCert)) return false
        if (domain != other.domain) return false
        if (!clientCert.contentEquals(other.clientCert)) return false
        if (!clientPrivateKey.contentEquals(other.clientPrivateKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = serverRootCaCert?.contentHashCode() ?: 0
        result = 31 * result + (domain?.hashCode() ?: 0)
        result = 31 * result + (clientCert?.contentHashCode() ?: 0)
        result = 31 * result + (clientPrivateKey?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * FFM bridge for Temporal Core client operations.
 *
 * The client provides connectivity to the Temporal server for starting
 * workflows, sending signals, and other operations.
 *
 * Uses jextract-generated bindings for direct function calls.
 */
internal object TemporalCoreClient {
    init {
        // Ensure native library is loaded before using generated bindings
        TemporalCoreFfmUtil.ensureLoaded()
    }

    // ============================================================
    // RPC Service Types
    // ============================================================

    /**
     * Temporal RPC service types.
     */
    enum class RpcService(
        val value: Int,
    ) {
        WORKFLOW(CoreBridge.Workflow()),
        OPERATOR(CoreBridge.Operator()),
        CLOUD(CoreBridge.Cloud()),
        TEST(CoreBridge.Test()),
        HEALTH(CoreBridge.Health()),
    }

    // ============================================================
    // Callback Interfaces
    // ============================================================

    /**
     * Callback interface for client connection.
     */
    fun interface ConnectCallback {
        fun onComplete(
            clientPtr: MemorySegment?,
            error: String?,
        )
    }

    /**
     * Typed callback interface for RPC calls with zero-copy protobuf parsing.
     * The response message is parsed directly from native memory without intermediate ByteArray copy.
     */
    fun interface TypedRpcCallback<T> {
        fun onComplete(
            response: T?,
            statusCode: Int,
            failureMessage: String?,
            failureDetails: ByteArray?,
        )
    }

    // ============================================================
    // Cancellation Token API
    // ============================================================

    /**
     * Creates a new cancellation token.
     *
     * @return Pointer to the cancellation token
     */
    fun createCancellationToken(): MemorySegment = CoreBridge.temporal_core_cancellation_token_new()

    /**
     * Cancels a cancellation token.
     *
     * @param tokenPtr Pointer to the cancellation token
     */
    fun cancelToken(tokenPtr: MemorySegment) {
        CoreBridge.temporal_core_cancellation_token_cancel(tokenPtr)
    }

    /**
     * Frees a cancellation token.
     *
     * @param tokenPtr Pointer to the cancellation token
     */
    fun freeToken(tokenPtr: MemorySegment) {
        CoreBridge.temporal_core_cancellation_token_free(tokenPtr)
    }

    // ============================================================
    // Client Connection API
    // ============================================================

    /**
     * Connects to a Temporal server using a reusable callback dispatcher.
     *
     * Note: For connect operations, the arena is NOT registered with the callback because
     * the arena's lifetime should match the client's lifetime (it contains options data
     * that may be referenced by the native client). The caller is responsible for
     * managing the arena's lifecycle.
     *
     * @param runtimePtr Pointer to the runtime
     * @param optionsArena Arena for allocating connection options (caller manages lifecycle)
     * @param dispatcher The callback dispatcher with reusable stubs
     * @param targetUrl The server URL (e.g., "http://localhost:7233")
     * @param namespace The namespace to use
     * @param tls TLS configuration, or null for no TLS
     * @param callback Callback invoked when connection completes
     * @return Context pointer for cancellation support
     */
    fun connect(
        runtimePtr: MemorySegment,
        optionsArena: Arena,
        dispatcher: ClientCallbackDispatcher,
        targetUrl: String,
        namespace: String = "default",
        clientName: String = "temporal-kotlin",
        clientVersion: String = "0.1.0",
        identity: String? = null,
        tls: ClientTlsOptions? = null,
        apiKey: String? = null,
        callback: ConnectCallback,
    ): MemorySegment {
        val options =
            buildClientOptions(
                arena = optionsArena,
                targetUrl = targetUrl,
                clientName = clientName,
                clientVersion = clientVersion,
                identity = identity,
                tls = tls,
                apiKey = apiKey,
            )

        // Register callback WITHOUT arena - the arena is managed by the caller
        // because its lifetime should match the client's lifetime
        val contextPtr = dispatcher.registerConnect(callback)
        CoreBridge.temporal_core_client_connect(runtimePtr, options, contextPtr, dispatcher.connectCallbackStub)
        return contextPtr
    }

    /**
     * Frees a client.
     *
     * @param clientPtr Pointer to the client to free
     */
    fun freeClient(clientPtr: MemorySegment) {
        CoreBridge.temporal_core_client_free(clientPtr)
    }

    /**
     * Updates the client's metadata headers.
     *
     * @param clientPtr Pointer to the client
     * @param metadata The metadata as key-value pairs
     * @param arena Arena for allocations
     */
    fun updateMetadata(
        clientPtr: MemorySegment,
        metadata: Map<String, String>,
        arena: Arena,
    ) {
        val metadataRef = createMetadataRef(arena, metadata)
        CoreBridge.temporal_core_client_update_metadata(clientPtr, metadataRef)
    }

    /**
     * Updates the client's binary metadata headers.
     *
     * @param clientPtr Pointer to the client
     * @param metadata The binary metadata as key-value pairs
     * @param arena Arena for allocations
     */
    fun updateBinaryMetadata(
        clientPtr: MemorySegment,
        metadata: Map<String, ByteArray>,
        arena: Arena,
    ) {
        val metadataRef = createBinaryMetadataRef(arena, metadata)
        CoreBridge.temporal_core_client_update_binary_metadata(clientPtr, metadataRef)
    }

    /**
     * Updates the client's API key.
     *
     * @param clientPtr Pointer to the client
     * @param apiKey The new API key
     * @param arena Arena for allocations
     */
    fun updateApiKey(
        clientPtr: MemorySegment,
        apiKey: String,
        arena: Arena,
    ) {
        val apiKeyRef = TemporalCoreFfmUtil.createByteArrayRef(arena, apiKey)
        CoreBridge.temporal_core_client_update_api_key(clientPtr, apiKeyRef)
    }

    // ============================================================
    // RPC Call API
    // ============================================================

    /**
     * Makes an RPC call to the Temporal server with zero-copy protobuf serialization and parsing.
     *
     * This version uses a shared callback stub for better performance on repeated calls.
     * Both request serialization and response parsing use zero-copy:
     * - Request is serialized directly to native memory without intermediate ByteArray
     * - Response is parsed directly from native memory without intermediate ByteArray copy
     *
     * @param clientPtr Pointer to the client
     * @param arena Arena for allocations (for RPC options, not callback stub)
     * @param dispatcher The callback dispatcher with reusable stubs
     * @param service The RPC service type
     * @param rpc The RPC method name
     * @param request The request protobuf message
     * @param parser Function that parses the CodedInputStream into the response type
     * @param retry Whether to retry on failure
     * @param timeoutMillis Timeout in milliseconds (0 for default)
     * @param cancellationToken Optional cancellation token
     * @param callback Typed callback invoked when RPC completes
     * @return The context pointer for cancellation
     */
    fun <Req : com.google.protobuf.MessageLite, Resp : com.google.protobuf.MessageLite> rpcCall(
        clientPtr: MemorySegment,
        arena: Arena,
        dispatcher: ClientCallbackDispatcher,
        service: RpcService,
        rpc: String,
        request: Req,
        parser: (com.google.protobuf.CodedInputStream) -> Resp,
        retry: Boolean = true,
        timeoutMillis: Int = 0,
        cancellationToken: MemorySegment? = null,
        callback: TypedRpcCallback<Resp>,
    ): MemorySegment {
        val options = TemporalCoreRpcCallOptions.allocate(arena)
        TemporalCoreRpcCallOptions.service(options, service.value)
        TemporalCoreRpcCallOptions.rpc(options, TemporalCoreFfmUtil.createByteArrayRef(arena, rpc))
        TemporalCoreRpcCallOptions.req(options, TemporalCoreFfmUtil.serializeToByteArrayRef(arena, request))
        TemporalCoreRpcCallOptions.retry(options, retry)
        TemporalCoreRpcCallOptions.metadata(options, createEmptyMetadataRef(arena))
        TemporalCoreRpcCallOptions.binary_metadata(options, createEmptyMetadataRef(arena))
        TemporalCoreRpcCallOptions.timeout_millis(options, timeoutMillis)
        TemporalCoreRpcCallOptions.cancellation_token(options, cancellationToken ?: MemorySegment.NULL)

        val contextPtr = dispatcher.registerRpc(callback, parser)
        CoreBridge.temporal_core_client_rpc_call(clientPtr, options, contextPtr, dispatcher.rpcCallbackStub)
        return contextPtr
    }

    // ============================================================
    // gRPC Override API (for advanced use cases)
    // ============================================================

    /**
     * Gets the service name from a gRPC override request.
     *
     * @param arena Arena for allocations
     * @param reqPtr Pointer to the gRPC override request
     * @return The service name
     */
    fun grpcOverrideRequestService(
        arena: Arena,
        reqPtr: MemorySegment,
    ): String? {
        val ref = CoreBridge.temporal_core_client_grpc_override_request_service(arena, reqPtr)
        return TemporalCoreFfmUtil.readByteArrayRef(ref)
    }

    /**
     * Gets the RPC method name from a gRPC override request.
     *
     * @param arena Arena for allocations
     * @param reqPtr Pointer to the gRPC override request
     * @return The RPC method name
     */
    fun grpcOverrideRequestRpc(
        arena: Arena,
        reqPtr: MemorySegment,
    ): String? {
        val ref = CoreBridge.temporal_core_client_grpc_override_request_rpc(arena, reqPtr)
        return TemporalCoreFfmUtil.readByteArrayRef(ref)
    }

    /**
     * Gets the proto bytes from a gRPC override request.
     *
     * @param arena Arena for allocations
     * @param reqPtr Pointer to the gRPC override request
     * @return The proto bytes
     */
    fun grpcOverrideRequestProto(
        arena: Arena,
        reqPtr: MemorySegment,
    ): ByteArray? {
        val ref = CoreBridge.temporal_core_client_grpc_override_request_proto(arena, reqPtr)
        return TemporalCoreFfmUtil.readByteArrayRefAsBytes(ref)
    }

    /**
     * Responds to a gRPC override request.
     *
     * @param reqPtr Pointer to the gRPC override request
     * @param respPtr Pointer to the response
     */
    fun grpcOverrideRequestRespond(
        reqPtr: MemorySegment,
        respPtr: MemorySegment,
    ) {
        CoreBridge.temporal_core_client_grpc_override_request_respond(reqPtr, respPtr)
    }

    // ============================================================
    // Helper Functions
    // ============================================================

    private fun buildClientOptions(
        arena: Arena,
        targetUrl: String,
        clientName: String,
        clientVersion: String,
        identity: String?,
        tls: ClientTlsOptions?,
        apiKey: String? = null,
    ): MemorySegment {
        val options = TemporalCoreClientOptions.allocate(arena)

        TemporalCoreClientOptions.target_url(options, TemporalCoreFfmUtil.createByteArrayRef(arena, targetUrl))
        TemporalCoreClientOptions.client_name(options, TemporalCoreFfmUtil.createByteArrayRef(arena, clientName))
        TemporalCoreClientOptions.client_version(options, TemporalCoreFfmUtil.createByteArrayRef(arena, clientVersion))
        TemporalCoreClientOptions.metadata(options, createEmptyMetadataRef(arena))
        TemporalCoreClientOptions.binary_metadata(options, createEmptyMetadataRef(arena))
        TemporalCoreClientOptions.api_key(options, TemporalCoreFfmUtil.createByteArrayRef(arena, apiKey))
        TemporalCoreClientOptions.identity(options, TemporalCoreFfmUtil.createByteArrayRef(arena, identity))
        TemporalCoreClientOptions.tls_options(options, buildTlsOptions(arena, tls))
        TemporalCoreClientOptions.retry_options(options, MemorySegment.NULL)
        TemporalCoreClientOptions.keep_alive_options(options, MemorySegment.NULL)
        TemporalCoreClientOptions.http_connect_proxy_options(options, MemorySegment.NULL)
        TemporalCoreClientOptions.grpc_override_callback(options, MemorySegment.NULL)
        TemporalCoreClientOptions.grpc_override_callback_user_data(options, MemorySegment.NULL)

        return options
    }

    private fun buildTlsOptions(
        arena: Arena,
        tls: ClientTlsOptions?,
    ): MemorySegment {
        if (tls == null) {
            return MemorySegment.NULL
        }

        val options = TemporalCoreClientTlsOptions.allocate(arena)

        TemporalCoreClientTlsOptions.server_root_ca_cert(
            options,
            TemporalCoreFfmUtil.createByteArrayRef(arena, tls.serverRootCaCert),
        )
        TemporalCoreClientTlsOptions.domain(
            options,
            TemporalCoreFfmUtil.createByteArrayRef(arena, tls.domain),
        )
        TemporalCoreClientTlsOptions.client_cert(
            options,
            TemporalCoreFfmUtil.createByteArrayRef(arena, tls.clientCert),
        )
        TemporalCoreClientTlsOptions.client_private_key(
            options,
            TemporalCoreFfmUtil.createByteArrayRef(arena, tls.clientPrivateKey),
        )

        return options
    }

    private fun createEmptyMetadataRef(arena: Arena): MemorySegment {
        val ref = TemporalCoreByteArrayRefArray.allocate(arena)
        TemporalCoreByteArrayRefArray.data(ref, MemorySegment.NULL)
        TemporalCoreByteArrayRefArray.size(ref, 0L)
        return ref
    }

    private fun createMetadataRef(
        arena: Arena,
        metadata: Map<String, String>,
    ): MemorySegment {
        if (metadata.isEmpty()) {
            return createEmptyMetadataRef(arena)
        }

        // Metadata is an array of ByteArrayRef pairs (key, value, key, value, ...)
        val entryCount = metadata.size * 2
        val entries = TemporalCoreFfmUtil.createByteArrayRef(arena, MemorySegment.NULL, 0L)
        // For now, allocate a contiguous array of ByteArrayRef structs
        val dataArray = arena.allocate(16L * entryCount) // 16 bytes per ByteArrayRef

        var offset = 0L
        for ((key, value) in metadata) {
            val keyRef = TemporalCoreFfmUtil.createByteArrayRef(arena, key)
            val valueRef = TemporalCoreFfmUtil.createByteArrayRef(arena, value)
            MemorySegment.copy(keyRef, 0L, dataArray, offset, 16L)
            MemorySegment.copy(valueRef, 0L, dataArray, offset + 16L, 16L)
            offset += 32L
        }

        val ref = TemporalCoreByteArrayRefArray.allocate(arena)
        TemporalCoreByteArrayRefArray.data(ref, dataArray)
        TemporalCoreByteArrayRefArray.size(ref, entryCount.toLong())
        return ref
    }

    private fun createBinaryMetadataRef(
        arena: Arena,
        metadata: Map<String, ByteArray>,
    ): MemorySegment {
        if (metadata.isEmpty()) {
            return createEmptyMetadataRef(arena)
        }

        val entryCount = metadata.size * 2
        val dataArray = arena.allocate(16L * entryCount)

        var offset = 0L
        for ((key, value) in metadata) {
            val keyRef = TemporalCoreFfmUtil.createByteArrayRef(arena, key)
            val valueRef = TemporalCoreFfmUtil.createByteArrayRef(arena, value)
            MemorySegment.copy(keyRef, 0L, dataArray, offset, 16L)
            MemorySegment.copy(valueRef, 0L, dataArray, offset + 16L, 16L)
            offset += 32L
        }

        val ref = TemporalCoreByteArrayRefArray.allocate(arena)
        TemporalCoreByteArrayRefArray.data(ref, dataArray)
        TemporalCoreByteArrayRefArray.size(ref, entryCount.toLong())
        return ref
    }
}
