package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreClientConnectCallback
import io.temporal.sdkbridge.TemporalCoreClientRpcCallCallback
import io.temporal.sdkbridge.TemporalCoreEphemeralServerShutdownCallback
import io.temporal.sdkbridge.TemporalCoreEphemeralServerStartCallback
import io.temporal.sdkbridge.TemporalCoreWorkerCallback
import io.temporal.sdkbridge.TemporalCoreWorkerPollCallback
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Factory for creating reusable callback stubs for FFM operations.
 *
 * This factory encapsulates the common pattern for all callback types:
 * 1. Extract context ID from user_data pointer
 * 2. Look up the callback in PendingCallbacks
 * 3. Handle null (canceled/already dispatched) by freeing Rust memory
 * 4. Invoke the callback with the result
 *
 * Arena lifecycle is managed by callers (via ManagedArena or similar patterns),
 * not by these stubs. This avoids FFM "session acquired" errors that occur
 * when closing arenas during upcalls.
 */
internal object CallbackStubFactory {
    /**
     * Creates a reusable connect callback stub.
     *
     * The stub dispatches to the correct Kotlin callback based on the context ID
     * stored in the user_data pointer. When the callback is invoked:
     * 1. Extracts context ID from user_data
     * 2. Looks up and removes the callback from pending
     * 3. If callback was cancelled, frees Rust memory and returns
     * 4. Otherwise invokes the callback with client pointer or error
     *
     * @param arena Arena for allocating the stub (typically the dispatcher's callback arena)
     * @param pending PendingCallbacks instance managing connect callbacks
     * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
     * @return A reusable MemorySegment stub for connect callbacks
     */
    fun createConnectCallbackStub(
        arena: Arena,
        pending: PendingCallbacks<TemporalCoreClient.ConnectCallback>,
        runtimePtr: MemorySegment,
    ): MemorySegment =
        TemporalCoreClientConnectCallback.allocate(
            { userDataPtr, clientPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pending.remove(contextId)

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
     * Creates a reusable RPC call callback stub.
     *
     * Uses zero-copy protobuf parsing directly from native memory.
     *
     * @param arena Arena for allocating the stub
     * @param pending PendingCallbacks instance managing RPC callbacks
     * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
     * @return A reusable MemorySegment stub for RPC callbacks
     */
    fun createRpcCallbackStub(
        arena: Arena,
        pending: PendingCallbacks<RpcCallbackWrapper<*>>,
        runtimePtr: MemorySegment,
    ): MemorySegment =
        TemporalCoreClientRpcCallCallback.allocate(
            { userDataPtr, successPtr, statusCode, failMessagePtr, failDetailsPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pending.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, successPtr)
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failMessagePtr)
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failDetailsPtr)
                    return@allocate
                }

                // Invoke the wrapper - it handles zero-copy parsing
                callback.invoke(runtimePtr, successPtr, statusCode, failMessagePtr, failDetailsPtr)
            },
            arena,
        )

    /**
     * Creates a reusable poll callback stub.
     *
     * Uses zero-copy protobuf parsing directly from native memory.
     *
     * @param arena Arena for allocating the stub
     * @param pending PendingCallbacks instance managing poll callbacks
     * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
     * @return A reusable MemorySegment stub for poll callbacks
     */
    fun createPollCallbackStub(
        arena: Arena,
        pending: PendingCallbacks<TemporalCoreFfmUtil.TypedCallbackWrapper<*>>,
        runtimePtr: MemorySegment,
    ): MemorySegment =
        TemporalCoreWorkerPollCallback.allocate(
            { userDataPtr, successPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pending.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, successPtr)
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failPtr)
                    return@allocate
                }

                // Invoke the wrapper - it handles zero-copy parsing
                callback.invoke(runtimePtr, successPtr, failPtr)
            },
            arena,
        )

    /**
     * Creates a reusable worker callback stub.
     *
     * @param arena Arena for allocating the stub
     * @param pending PendingCallbacks instance managing worker callbacks
     * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
     * @return A reusable MemorySegment stub for worker callbacks
     */
    fun createWorkerCallbackStub(
        arena: Arena,
        pending: PendingCallbacks<TemporalCoreWorker.WorkerCallback>,
        runtimePtr: MemorySegment,
    ): MemorySegment =
        TemporalCoreWorkerCallback.allocate(
            { userDataPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pending.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failPtr)
                    return@allocate
                }

                val error = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, failPtr)
                callback.onComplete(error)
            },
            arena,
        )

    /**
     * Creates a reusable server start callback stub.
     *
     * @param arena Arena for allocating the stub
     * @param pending PendingCallbacks instance managing start callbacks
     * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
     * @return A reusable MemorySegment stub for server start callbacks
     */
    fun createServerStartCallbackStub(
        arena: Arena,
        pending: PendingCallbacks<TemporalCoreEphemeralServer.StartCallback>,
        runtimePtr: MemorySegment,
    ): MemorySegment =
        TemporalCoreEphemeralServerStartCallback.allocate(
            { userDataPtr, serverPtr, targetUrlPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pending.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, targetUrlPtr)
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failPtr)
                    return@allocate
                }

                val targetUrl = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, targetUrlPtr)
                val error = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, failPtr)
                callback.onComplete(
                    if (serverPtr != MemorySegment.NULL) serverPtr else null,
                    targetUrl,
                    error,
                )
            },
            arena,
        )

    /**
     * Creates a reusable server shutdown callback stub.
     *
     * @param arena Arena for allocating the stub
     * @param pending PendingCallbacks instance managing shutdown callbacks
     * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
     * @return A reusable MemorySegment stub for server shutdown callbacks
     */
    fun createServerShutdownCallbackStub(
        arena: Arena,
        pending: PendingCallbacks<TemporalCoreEphemeralServer.ShutdownCallback>,
        runtimePtr: MemorySegment,
    ): MemorySegment =
        TemporalCoreEphemeralServerShutdownCallback.allocate(
            { userDataPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pending.remove(contextId)

                if (callback == null) {
                    // Callback was canceled or already dispatched - just free Rust memory
                    TemporalCoreFfmUtil.freeByteArrayIfNotNull(runtimePtr, failPtr)
                    return@allocate
                }

                val error = TemporalCoreFfmUtil.readAndFreeByteArray(runtimePtr, failPtr)
                callback.onComplete(error)
            },
            arena,
        )
}
