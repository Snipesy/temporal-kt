package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreByteArrayRefArray
import io.temporal.sdkbridge.TemporalCoreClientConnectCallback
import io.temporal.sdkbridge.TemporalCoreClientOptions
import io.temporal.sdkbridge.TemporalCoreClientRpcCallCallback
import io.temporal.sdkbridge.TemporalCoreRpcCallOptions
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

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
     * Callback interface for RPC calls.
     */
    fun interface RpcCallback {
        fun onComplete(
            response: ByteArray?,
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
     * Connects to a Temporal server.
     *
     * @param runtimePtr Pointer to the runtime
     * @param arena Arena for allocations
     * @param targetUrl The server URL (e.g., "http://localhost:7233")
     * @param namespace The namespace to use
     * @param callback Callback invoked when connection completes
     */
    fun connect(
        runtimePtr: MemorySegment,
        arena: Arena,
        targetUrl: String,
        namespace: String = "default",
        clientName: String = "temporal-kotlin",
        clientVersion: String = "0.1.0",
        identity: String? = null,
        callback: ConnectCallback,
    ) {
        val options =
            buildClientOptions(
                arena = arena,
                targetUrl = targetUrl,
                clientName = clientName,
                clientVersion = clientVersion,
                identity = identity,
            )

        val callbackStub = createConnectCallbackStub(arena, runtimePtr, callback)
        CoreBridge.temporal_core_client_connect(runtimePtr, options, MemorySegment.NULL, callbackStub)
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
     * Makes an RPC call to the Temporal server.
     *
     * @param clientPtr Pointer to the client
     * @param arena Arena for allocations
     * @param runtimePtr Pointer to the runtime (for freeing byte arrays)
     * @param service The RPC service type
     * @param rpc The RPC method name
     * @param request The request payload (protobuf bytes)
     * @param retry Whether to retry on failure
     * @param timeoutMillis Timeout in milliseconds (0 for default)
     * @param cancellationToken Optional cancellation token
     * @param callback Callback invoked when RPC completes
     */
    fun rpcCall(
        clientPtr: MemorySegment,
        arena: Arena,
        runtimePtr: MemorySegment,
        service: RpcService,
        rpc: String,
        request: ByteArray,
        retry: Boolean = true,
        timeoutMillis: Int = 0,
        cancellationToken: MemorySegment? = null,
        callback: RpcCallback,
    ) {
        val options = TemporalCoreRpcCallOptions.allocate(arena)
        TemporalCoreRpcCallOptions.service(options, service.value)
        TemporalCoreRpcCallOptions.rpc(options, TemporalCoreFfmUtil.createByteArrayRef(arena, rpc))
        TemporalCoreRpcCallOptions.req(options, TemporalCoreFfmUtil.createByteArrayRef(arena, request))
        TemporalCoreRpcCallOptions.retry(options, retry)
        TemporalCoreRpcCallOptions.metadata(options, createEmptyMetadataRef(arena))
        TemporalCoreRpcCallOptions.binary_metadata(options, createEmptyMetadataRef(arena))
        TemporalCoreRpcCallOptions.timeout_millis(options, timeoutMillis)
        TemporalCoreRpcCallOptions.cancellation_token(options, cancellationToken ?: MemorySegment.NULL)

        val callbackStub = createRpcCallbackStub(arena, runtimePtr, callback)
        CoreBridge.temporal_core_client_rpc_call(clientPtr, options, MemorySegment.NULL, callbackStub)
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
    ): MemorySegment {
        val options = TemporalCoreClientOptions.allocate(arena)

        TemporalCoreClientOptions.target_url(options, TemporalCoreFfmUtil.createByteArrayRef(arena, targetUrl))
        TemporalCoreClientOptions.client_name(options, TemporalCoreFfmUtil.createByteArrayRef(arena, clientName))
        TemporalCoreClientOptions.client_version(options, TemporalCoreFfmUtil.createByteArrayRef(arena, clientVersion))
        TemporalCoreClientOptions.metadata(options, createEmptyMetadataRef(arena))
        TemporalCoreClientOptions.binary_metadata(options, createEmptyMetadataRef(arena))
        TemporalCoreClientOptions.api_key(options, TemporalCoreFfmUtil.createEmptyByteArrayRef(arena))
        TemporalCoreClientOptions.identity(options, TemporalCoreFfmUtil.createByteArrayRef(arena, identity))
        TemporalCoreClientOptions.tls_options(options, MemorySegment.NULL)
        TemporalCoreClientOptions.retry_options(options, MemorySegment.NULL)
        TemporalCoreClientOptions.keep_alive_options(options, MemorySegment.NULL)
        TemporalCoreClientOptions.http_connect_proxy_options(options, MemorySegment.NULL)
        TemporalCoreClientOptions.grpc_override_callback(options, MemorySegment.NULL)
        TemporalCoreClientOptions.grpc_override_callback_user_data(options, MemorySegment.NULL)

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

    private fun createConnectCallbackStub(
        arena: Arena,
        runtimePtr: MemorySegment,
        callback: ConnectCallback,
    ): MemorySegment =
        TemporalCoreClientConnectCallback.allocate(
            { _, clientPtr, failPtr ->
                val error =
                    if (failPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArray(failPtr).also {
                            CoreBridge.temporal_core_byte_array_free(runtimePtr, failPtr)
                        }
                    } else {
                        null
                    }

                callback.onComplete(
                    if (clientPtr != MemorySegment.NULL) clientPtr else null,
                    error,
                )
            },
            arena,
        )

    private fun createRpcCallbackStub(
        arena: Arena,
        runtimePtr: MemorySegment,
        callback: RpcCallback,
    ): MemorySegment =
        TemporalCoreClientRpcCallCallback.allocate(
            { _, successPtr, statusCode, failMessagePtr, failDetailsPtr ->
                val response =
                    if (successPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArrayAsBytes(successPtr).also {
                            CoreBridge.temporal_core_byte_array_free(runtimePtr, successPtr)
                        }
                    } else {
                        null
                    }

                val failureMessage =
                    if (failMessagePtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArray(failMessagePtr).also {
                            CoreBridge.temporal_core_byte_array_free(runtimePtr, failMessagePtr)
                        }
                    } else {
                        null
                    }

                val failureDetails =
                    if (failDetailsPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArrayAsBytes(failDetailsPtr).also {
                            CoreBridge.temporal_core_byte_array_free(runtimePtr, failDetailsPtr)
                        }
                    } else {
                        null
                    }

                callback.onComplete(response, statusCode, failureMessage, failureDetails)
            },
            arena,
        )
}
