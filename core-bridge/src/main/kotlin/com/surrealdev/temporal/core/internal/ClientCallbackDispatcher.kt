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
 * ## Arena Lifecycle Patterns
 *
 * The "Arena Follows Callback" principle ensures that any arena registered with a callback
 * is automatically closed when the callback completes or is cancelled.
 *
 * **RPC Operations**: The options arena is registered with the callback via [registerRpc].
 * When the callback fires or is canceled, [PendingCallbacks] automatically closes the arena.
 *
 * **Connect Operations**: The options arena is NOT registered with the callback. This is
 * intentional because the arena contains connection configuration data (target URL, TLS options,
 * etc.) that may be referenced by the native client for its entire lifetime. The arena's
 * lifecycle is instead managed by the caller:
 * - On success: ownership transfers to [TemporalCoreClient], closed in its `close()` method
 * - On failure/cancellation: caller closes the arena explicitly
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
     * When the callback is invoked, any arena registered with it is automatically closed.
     */
    val connectCallbackStub: MemorySegment =
        CallbackStubFactory.createConnectCallbackStub(arena, pendingConnectCallbacks, runtimePtr)

    /**
     * Single reusable stub for RPC call operations.
     * Dispatches to the correct callback based on context ID in user_data.
     * Uses zero-copy protobuf parsing directly from native memory.
     * When the callback is invoked, any arena registered with it is automatically closed.
     */
    val rpcCallbackStub: MemorySegment =
        CallbackStubFactory.createRpcCallbackStub(arena, pendingRpcCallbacks, runtimePtr)

    /**
     * Registers a connect callback and returns a context pointer to pass as user_data.
     *
     * @param callback The callback to invoke when the connect completes
     * @param optionsArena Optional arena containing connection options; closed when callback completes or is cancelled
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun registerConnect(
        callback: TemporalCoreClient.ConnectCallback,
        optionsArena: Arena? = null,
    ): MemorySegment = pendingConnectCallbacks.register(callback, optionsArena)

    /**
     * Registers a typed RPC callback with zero-copy protobuf parsing.
     * The parser is invoked directly on native memory without intermediate ByteArray copy.
     *
     * @param callback The typed callback to invoke when the RPC completes
     * @param parser Function that parses the CodedInputStream into the response type
     * @param optionsArena Optional arena containing RPC options; closed when callback completes or is cancelled
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun <T : MessageLite> registerRpc(
        callback: TemporalCoreClient.TypedRpcCallback<T>,
        parser: (CodedInputStream) -> T,
        optionsArena: Arena? = null,
    ): MemorySegment = pendingRpcCallbacks.register(RpcCallbackWrapper(callback, parser), optionsArena)

    /**
     * Cancels a pending connect callback.
     *
     * @param contextId The context ID from getContextId()
     * @return true if the callback was found and removed, false if already dispatched/canceled
     */
    fun cancelConnect(contextId: Long): Boolean = pendingConnectCallbacks.cancel(contextId)

    /**
     * Cancels a pending RPC callback.
     *
     * @param contextId The context ID from getContextId()
     * @return true if the callback was found and removed, false if already dispatched/canceled
     */
    fun cancelRpc(contextId: Long): Boolean = pendingRpcCallbacks.cancel(contextId)

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
