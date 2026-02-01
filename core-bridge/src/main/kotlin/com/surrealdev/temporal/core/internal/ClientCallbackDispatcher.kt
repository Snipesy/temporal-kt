package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreClientConnectCallback
import io.temporal.sdkbridge.TemporalCoreClientRpcCallCallback
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Manages reusable callback stubs for FFM client operations.
 *
 * Instead of creating a new upcall stub for each RPC call or connect operation,
 * this dispatcher creates reusable stubs and uses the user_data pointer to dispatch
 * to the correct Kotlin callback.
 *
 * @param arena The arena for allocating the reusable stubs (typically client's callbackArena)
 * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
 */
internal class ClientCallbackDispatcher(
    arena: Arena,
    private val runtimePtr: MemorySegment,
) : AutoCloseable {
    private val pendingConnectCallbacks = PendingCallbacks<TemporalCoreClient.ConnectCallback>()
    private val pendingRpcCallbacks = PendingCallbacks<TemporalCoreClient.RpcCallback>()

    private val logger = LoggerFactory.getLogger(ClientCallbackDispatcher::class.java)

    /**
     * Single reusable stub for client connect operations.
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val connectCallbackStub: MemorySegment =
        TemporalCoreClientConnectCallback.allocate(
            { userDataPtr, clientPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pendingConnectCallbacks.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failPtr)
                    return@allocate
                }

                val error = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, failPtr)
                callback.onComplete(
                    if (clientPtr != MemorySegment.NULL) clientPtr else null,
                    error,
                )
            },
            arena,
        )

    /**
     * Single reusable stub for RPC call operations.
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val rpcCallbackStub: MemorySegment =
        TemporalCoreClientRpcCallCallback.allocate(
            { userDataPtr, successPtr, statusCode, failMessagePtr, failDetailsPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pendingRpcCallbacks.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, successPtr)
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failMessagePtr)
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failDetailsPtr)
                    return@allocate
                }

                val response = TemporalCoreFfmUtil.readAndFreeByteArrayAsBytes(runtimePtr, successPtr)
                val failureMessage = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, failMessagePtr)
                val failureDetails = TemporalCoreFfmUtil.readAndFreeByteArrayAsBytes(runtimePtr, failDetailsPtr)
                callback.onComplete(response, statusCode, failureMessage, failureDetails)
            },
            arena,
        )

    /**
     * Registers a connect callback and returns a context pointer to pass as user_data.
     *
     * @param callback The callback to invoke when the connect completes
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun registerConnect(callback: TemporalCoreClient.ConnectCallback): MemorySegment =
        pendingConnectCallbacks.register(callback)

    /**
     * Registers an RPC callback and returns a context pointer to pass as user_data.
     *
     * @param callback The callback to invoke when the RPC completes
     * @return A MemorySegment containing the context ID (pass as user_data to FFI)
     */
    fun registerRpc(callback: TemporalCoreClient.RpcCallback): MemorySegment = pendingRpcCallbacks.register(callback)

    /**
     * Extracts the context ID from a context pointer.
     *
     * @param contextPtr The context pointer returned from register methods
     * @return The context ID
     */
    fun getContextId(contextPtr: MemorySegment): Long = PendingCallbacks.getContextId(contextPtr)

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
