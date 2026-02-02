package com.surrealdev.temporal.core.internal

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
    runtimePtr: MemorySegment,
) : BaseCallbackDispatcher(runtimePtr) {
    private val pendingStartCallbacks = PendingCallbacks<TemporalCoreEphemeralServer.StartCallback>()
    private val pendingShutdownCallbacks = PendingCallbacks<TemporalCoreEphemeralServer.ShutdownCallback>()

    private val logger = LoggerFactory.getLogger(EphemeralServerCallbackDispatcher::class.java)

    /**
     * Single reusable stub for server start operations.
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val startCallbackStub: MemorySegment =
        CallbackStubFactory.createServerStartCallbackStub(arena, pendingStartCallbacks, runtimePtr)

    /**
     * Single reusable stub for server shutdown operations.
     * Dispatches to the correct callback based on context ID in user_data.
     */
    val shutdownCallbackStub: MemorySegment =
        CallbackStubFactory.createServerShutdownCallbackStub(arena, pendingShutdownCallbacks, runtimePtr)

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
     * Blocks until all pending callbacks have been dispatched.
     *
     * This must be called BEFORE freeing the native server handle to ensure
     * all Tokio tasks holding references to the server have completed.
     */
    fun awaitPendingCallbacks() {
        pendingStartCallbacks.awaitEmpty()
        pendingShutdownCallbacks.awaitEmpty()
    }

    override fun close() {
        if (pendingStartCallbacks.isNotEmpty() || pendingShutdownCallbacks.isNotEmpty()) {
            logger.trace("[EphemeralServerCallbackDispatcher] WARNING: Closing with pending callbacks!")
        }
        pendingStartCallbacks.clear()
        pendingShutdownCallbacks.clear()
    }
}
