package com.surrealdev.temporal.core.internal

import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Wrapper for RPC callbacks that captures the typed callback and parser for deferred invocation.
 * Parses the response protobuf directly from native memory (zero-copy) when invoked.
 */
internal class RpcCallbackWrapper<T : MessageLite>(
    private val callback: TemporalCoreClient.TypedRpcCallback<T>,
    private val parser: (CodedInputStream) -> T,
) {
    fun invoke(
        runtimePtr: MemorySegment,
        successPtr: MemorySegment,
        statusCode: Int,
        failMessagePtr: MemorySegment,
        failDetailsPtr: MemorySegment,
    ) {
        val response = TemporalCoreFfmUtil.readAndParseProto(runtimePtr, successPtr, parser)
        val failureMessage = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, failMessagePtr)
        // Failure details is also a protobuf (google.rpc.Status), but we keep it as bytes
        // for now since it's typically only used for error handling
        val failureDetails = TemporalCoreFfmUtil.readAndFreeByteArrayAsBytes(runtimePtr, failDetailsPtr)
        callback.onComplete(response, statusCode, failureMessage, failureDetails)
    }
}

/**
 * Manages reusable callback stubs for FFM client operations.
 *
 * Instead of creating a new upcall stub for each RPC call or connect operation,
 * this dispatcher creates reusable stubs and uses the user_data pointer to dispatch
 * to the correct Kotlin callback.
 *
 * All RPC callbacks use zero-copy protobuf parsing directly from native memory.
 *
 * Arena lifecycle is managed by the caller (via ManagedArena), not by this dispatcher.
 * Connect operations are special - the arena's lifecycle matches the client's lifetime.
 *
 * @param arena The arena for allocating the reusable stubs (typically client's callbackArena)
 * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
 */
internal class ClientCallbackDispatcher(
    arena: Arena,
    runtimePtr: MemorySegment,
) : BaseCallbackDispatcher(runtimePtr) {
    private val pendingConnectCallbacks = PendingCallbacks<TemporalCoreClient.ConnectCallback>()

    // Use type-erased wrapper to store different message types in the same map
    private val pendingRpcCallbacks = PendingCallbacks<RpcCallbackWrapper<*>>()

    private val logger = LoggerFactory.getLogger(ClientCallbackDispatcher::class.java)

    /**
     * Single reusable stub for connect operations.
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val connectCallbackStub: MemorySegment =
        CallbackStubFactory.createConnectCallbackStub(arena, pendingConnectCallbacks, runtimePtr)

    /**
     * Single reusable stub for RPC call operations.
     * Dispatches to the correct callback based on context ID in user_data.
     * Uses zero-copy protobuf parsing directly from native memory.
     */
    val rpcCallbackStub: MemorySegment =
        CallbackStubFactory.createRpcCallbackStub(arena, pendingRpcCallbacks, runtimePtr)

    /**
     * Registers a connect callback and returns a context pointer to pass as user_data.
     *
     * @param callback The callback to invoke when the connect completes
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun registerConnect(callback: TemporalCoreClient.ConnectCallback): MemorySegment =
        pendingConnectCallbacks.register(callback)

    /**
     * Registers a typed RPC callback with zero-copy protobuf parsing.
     * The parser is invoked directly on native memory without intermediate ByteArray copy.
     *
     * @param callback The typed callback to invoke when the RPC completes
     * @param parser Function that parses the CodedInputStream into the response type
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun <T : MessageLite> registerRpc(
        callback: TemporalCoreClient.TypedRpcCallback<T>,
        parser: (CodedInputStream) -> T,
    ): MemorySegment = pendingRpcCallbacks.register(RpcCallbackWrapper(callback, parser))

    /**
     * Blocks until all pending callbacks have been dispatched, or until timeout.
     *
     * This must be called BEFORE freeing the native client handle to ensure
     * all Tokio tasks holding references to the client have completed.
     *
     * @param timeoutSeconds Timeout in seconds (default 60s)
     * @return true if all callbacks completed, false if timeout was reached
     */
    fun awaitPendingCallbacks(timeoutSeconds: Long = 60): Boolean {
        val connectCompleted = pendingConnectCallbacks.awaitEmpty(timeoutSeconds)
        val rpcCompleted = pendingRpcCallbacks.awaitEmpty(timeoutSeconds)
        return connectCompleted && rpcCompleted
    }

    override fun close() {
        if (pendingConnectCallbacks.isNotEmpty() || pendingRpcCallbacks.isNotEmpty()) {
            logger.trace("[ClientCallbackDispatcher] WARNING: Closing with pending callbacks!")
            logger.trace("[ClientCallbackDispatcher] Pending connect callbacks: {}", pendingConnectCallbacks.keys)
            logger.trace("[ClientCallbackDispatcher] Pending RPC callbacks: {}", pendingRpcCallbacks.keys)
        }
        pendingConnectCallbacks.clear()
        pendingRpcCallbacks.clear()
        // Note: The arena (holding the stubs) is managed externally
    }
}
