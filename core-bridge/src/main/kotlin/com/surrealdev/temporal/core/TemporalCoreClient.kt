package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.CallbackArena
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
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
    private val runtimePtr: MemorySegment,
    private val arena: Arena,
    val targetUrl: String,
    val namespace: String,
) : AutoCloseable {
    @Volatile
    private var closed = false

    companion object {
        /**
         * Connects to a Temporal server asynchronously.
         *
         * @param runtime The Temporal runtime to use
         * @param targetUrl The server URL (e.g., "http://localhost:7233")
         * @param namespace The namespace to use (default: "default")
         * @param options Additional client options
         * @return A connected client instance
         * @throws TemporalCoreException if connection fails
         */
        suspend fun connect(
            runtime: TemporalRuntime,
            targetUrl: String,
            namespace: String = "default",
            options: ClientOptions = ClientOptions(),
        ): TemporalCoreClient {
            runtime.ensureOpen()

            val (arena, clientPtr) =
                CallbackArena.withOwnershipTransfer<MemorySegment> { arena, callback ->
                    InternalClient.connect(
                        runtimePtr = runtime.handle,
                        arena = arena,
                        targetUrl = targetUrl,
                        namespace = namespace,
                        clientName = options.clientName,
                        clientVersion = options.clientVersion,
                        identity = options.identity,
                        callback = callback,
                    )
                }

            return TemporalCoreClient(
                handle = clientPtr,
                runtimePtr = runtime.handle,
                arena = arena,
                targetUrl = targetUrl,
                namespace = namespace,
            )
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
     * Makes an RPC call to the Temporal workflow service.
     *
     * Uses a long-lived arena because Rust spawns async tasks that hold the callback
     * pointer, which may complete after the arena would normally be GC'd.
     *
     * @param rpc The RPC method name (e.g., "StartWorkflowExecution")
     * @param request The request payload as protobuf bytes
     * @return The response payload as protobuf bytes
     * @throws TemporalCoreException if the RPC call fails
     */
    suspend fun workflowServiceCall(
        rpc: String,
        request: ByteArray,
    ): ByteArray {
        ensureOpen()
        return CallbackArena.withLongLivedNonNullResult<ByteArray> { callArena, callback ->
            InternalClient.rpcCall(
                clientPtr = handle,
                arena = callArena,
                runtimePtr = runtimePtr,
                service = InternalClient.RpcService.WORKFLOW,
                rpc = rpc,
                request = request,
            ) { response, statusCode, failureMessage, _ ->
                if (response != null && statusCode == 0) {
                    callback(response, null)
                } else {
                    callback(null, failureMessage ?: "RPC call failed with status $statusCode")
                }
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
            InternalClient.freeClient(handle)
            arena.close()
        }
    }
}
