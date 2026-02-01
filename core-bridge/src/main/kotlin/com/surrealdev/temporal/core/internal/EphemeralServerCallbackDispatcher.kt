package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreEphemeralServerShutdownCallback
import io.temporal.sdkbridge.TemporalCoreEphemeralServerStartCallback
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Manages reusable callback stubs for FFM ephemeral server operations.
 *
 * Instead of creating a new upcall stub for each start/shutdown operation,
 * this dispatcher creates reusable stubs and uses the user_data pointer to dispatch
 * to the correct Kotlin callback.
 *
 * @param arena The arena for allocating the reusable stubs
 * @param runtimePtr Pointer to the Temporal runtime for freeing byte arrays
 */
internal class EphemeralServerCallbackDispatcher(
    arena: Arena,
    private val runtimePtr: MemorySegment,
) : AutoCloseable {
    private val pendingStartCallbacks = PendingCallbacks<TemporalCoreEphemeralServer.StartCallback>()
    private val pendingShutdownCallbacks = PendingCallbacks<TemporalCoreEphemeralServer.ShutdownCallback>()

    private val logger = LoggerFactory.getLogger(EphemeralServerCallbackDispatcher::class.java)

    /**
     * Single reusable stub for server start operations.
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val startCallbackStub: MemorySegment =
        TemporalCoreEphemeralServerStartCallback.allocate(
            { userDataPtr, serverPtr, targetUrlPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pendingStartCallbacks.remove(contextId)

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
     * Single reusable stub for server shutdown operations.
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val shutdownCallbackStub: MemorySegment =
        TemporalCoreEphemeralServerShutdownCallback.allocate(
            { userDataPtr, failPtr ->
                val contextId = PendingCallbacks.getContextId(userDataPtr)
                val callback = pendingShutdownCallbacks.remove(contextId)

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
     * Registers a start callback and returns a context pointer to pass as user_data.
     */
    fun registerStart(callback: TemporalCoreEphemeralServer.StartCallback): MemorySegment =
        pendingStartCallbacks.register(callback)

    /**
     * Registers a shutdown callback and returns a context pointer to pass as user_data.
     */
    fun registerShutdown(callback: TemporalCoreEphemeralServer.ShutdownCallback): MemorySegment =
        pendingShutdownCallbacks.register(callback)

    /**
     * Extracts the context ID from a context pointer.
     */
    fun getContextId(contextPtr: MemorySegment): Long = PendingCallbacks.getContextId(contextPtr)

    /**
     * Cancels a pending start callback.
     */
    fun cancelStart(contextId: Long): Boolean = pendingStartCallbacks.cancel(contextId)

    /**
     * Cancels a pending shutdown callback.
     */
    fun cancelShutdown(contextId: Long): Boolean = pendingShutdownCallbacks.cancel(contextId)

    override fun close() {
        if (pendingStartCallbacks.isNotEmpty() || pendingShutdownCallbacks.isNotEmpty()) {
            logger.trace("[EphemeralServerCallbackDispatcher] WARNING: Closing with pending callbacks!")
        }
        pendingStartCallbacks.clear()
        pendingShutdownCallbacks.clear()
    }
}
