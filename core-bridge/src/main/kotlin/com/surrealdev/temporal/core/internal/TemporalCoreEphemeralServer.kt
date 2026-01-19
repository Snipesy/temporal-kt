package com.surrealdev.temporal.core.internal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * FFM bridge for Temporal Core ephemeral server operations.
 *
 * Ephemeral servers are used for testing - they provide a local Temporal
 * server instance that can be started and stopped programmatically.
 */
internal object TemporalCoreEphemeralServer {
    private val linker = TemporalCoreFfmUtil.linker

    // ============================================================
    // Method Handles
    // ============================================================

    // temporal_core_ephemeral_server_start_dev_server(runtime, options, user_data, callback) -> void
    private val devServerStartHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_ephemeral_server_start_dev_server"),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, // runtime
                ValueLayout.ADDRESS, // options
                ValueLayout.ADDRESS, // user_data
                ValueLayout.ADDRESS, // callback
            ),
        )
    }

    // temporal_core_ephemeral_server_start_test_server(runtime, options, user_data, callback) -> void
    private val testServerStartHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_ephemeral_server_start_test_server"),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, // runtime
                ValueLayout.ADDRESS, // options
                ValueLayout.ADDRESS, // user_data
                ValueLayout.ADDRESS, // callback
            ),
        )
    }

    // temporal_core_ephemeral_server_free(server) -> void
    private val ephemeralServerFreeHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_ephemeral_server_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    }

    // temporal_core_ephemeral_server_shutdown(server, user_data, callback) -> void
    private val ephemeralServerShutdownHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_ephemeral_server_shutdown"),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, // server
                ValueLayout.ADDRESS, // user_data
                ValueLayout.ADDRESS, // callback
            ),
        )
    }

    // ============================================================
    // Callback Interfaces
    // ============================================================

    /**
     * Result of starting an ephemeral server.
     */
    data class StartResult(
        val serverPtr: MemorySegment,
        val targetUrl: String,
    )

    /**
     * Callback interface for server start.
     */
    fun interface StartCallback {
        fun onComplete(
            serverPtr: MemorySegment?,
            targetUrl: String?,
            error: String?,
        )
    }

    /**
     * Callback interface for server shutdown.
     */
    fun interface ShutdownCallback {
        fun onComplete(error: String?)
    }

    // ============================================================
    // Callback Handlers
    // ============================================================

    /**
     * Handler class for server start callback (must be a class for MethodHandles.bind)
     */
    private class StartCallbackHandler(
        private val runtimePtr: MemorySegment,
        private val callback: StartCallback,
    ) {
        @Suppress("unused") // Called via reflection by FFM
        fun handle(
            userData: MemorySegment,
            serverPtr: MemorySegment,
            targetUrlPtr: MemorySegment,
            failPtr: MemorySegment,
        ) {
            val targetUrl =
                if (targetUrlPtr !=
                    MemorySegment.NULL
                ) {
                    TemporalCoreFfmUtil.readByteArray(targetUrlPtr)
                } else {
                    null
                }
            val error = if (failPtr != MemorySegment.NULL) TemporalCoreFfmUtil.readByteArray(failPtr) else null

            // Free the byte arrays if present
            if (targetUrlPtr != MemorySegment.NULL) {
                TemporalCoreRuntime.freeByteArray(runtimePtr, targetUrlPtr)
            }
            if (failPtr != MemorySegment.NULL) {
                TemporalCoreRuntime.freeByteArray(runtimePtr, failPtr)
            }

            callback.onComplete(
                if (serverPtr != MemorySegment.NULL) serverPtr else null,
                targetUrl,
                error,
            )
        }
    }

    /**
     * Handler class for server shutdown callback
     */
    private class ShutdownCallbackHandler(
        private val runtimePtr: MemorySegment,
        private val callback: ShutdownCallback,
    ) {
        @Suppress("unused") // Called via reflection by FFM
        fun handle(
            userData: MemorySegment,
            failPtr: MemorySegment,
        ) {
            val error = if (failPtr != MemorySegment.NULL) TemporalCoreFfmUtil.readByteArray(failPtr) else null

            // Free the byte array if present
            if (failPtr != MemorySegment.NULL) {
                TemporalCoreRuntime.freeByteArray(runtimePtr, failPtr)
            }

            callback.onComplete(error)
        }
    }

    // ============================================================
    // Callback Stub Creation
    // ============================================================

    /**
     * Creates an upcall stub for the server start callback.
     */
    private fun createStartCallbackStub(
        arena: Arena,
        runtimePtr: MemorySegment,
        callback: StartCallback,
    ): MemorySegment {
        val handle =
            MethodHandles
                .lookup()
                .bind(
                    StartCallbackHandler(runtimePtr, callback),
                    "handle",
                    MethodType.methodType(
                        Void.TYPE,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                    ),
                )
        return linker.upcallStub(handle, TemporalCoreFfmUtil.EPHEMERAL_SERVER_START_CALLBACK_DESC, arena)
    }

    /**
     * Creates an upcall stub for the server shutdown callback.
     */
    private fun createShutdownCallbackStub(
        arena: Arena,
        runtimePtr: MemorySegment,
        callback: ShutdownCallback,
    ): MemorySegment {
        val handle =
            MethodHandles
                .lookup()
                .bind(
                    ShutdownCallbackHandler(runtimePtr, callback),
                    "handle",
                    MethodType.methodType(
                        Void.TYPE,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                    ),
                )
        return linker.upcallStub(handle, TemporalCoreFfmUtil.EPHEMERAL_SERVER_SHUTDOWN_CALLBACK_DESC, arena)
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Starts a development server.
     *
     * @param runtimePtr Pointer to the runtime
     * @param arena Arena for allocations (must outlive the callback)
     * @param namespace The namespace to use
     * @param ip The IP address to bind to
     * @param existingPath Path to existing Temporal CLI (skips download if set)
     * @param downloadVersion Version to download ("default", "latest", or semver). Ignored if existingPath is set.
     * @param downloadTtlSeconds Cache duration for downloads (0 = no TTL)
     * @param callback Callback invoked when server starts or fails
     */
    fun startDevServer(
        runtimePtr: MemorySegment,
        arena: Arena,
        namespace: String = "default",
        ip: String = "127.0.0.1",
        existingPath: String? = null,
        downloadVersion: String? = "default",
        downloadTtlSeconds: Long = 0,
        callback: StartCallback,
    ) {
        // Allocate TestServerOptions (required by DevServerOptions)
        val testServerOptions = arena.allocate(TemporalCoreFfmUtil.TEST_SERVER_OPTIONS_LAYOUT)
        var offset = 0L

        // existing_path ByteArrayRef (first field)
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, existingPath)

        // sdk_name ByteArrayRef - identify ourselves
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, "temporal-kotlin")

        // sdk_version ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, "0.1.0")

        // download_version ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, downloadVersion)

        // download_dest_dir ByteArrayRef (empty = default)
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, null)

        // port = 0 (auto)
        testServerOptions.set(ValueLayout.JAVA_SHORT, offset, 0.toShort())
        offset += 8 // 2 + 6 padding

        // extra_args ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, null)

        // download_ttl_seconds
        testServerOptions.set(ValueLayout.JAVA_LONG, offset, downloadTtlSeconds)

        // Allocate DevServerOptions
        val devServerOptions = arena.allocate(TemporalCoreFfmUtil.DEV_SERVER_OPTIONS_LAYOUT)
        offset = 0L

        // test_server pointer
        devServerOptions.set(ValueLayout.ADDRESS, offset, testServerOptions)
        offset += 8

        // namespace_ ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, devServerOptions, offset, namespace)

        // ip ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, devServerOptions, offset, ip)

        // database_filename ByteArrayRef (empty = in-memory)
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, devServerOptions, offset, null)

        // ui = false
        devServerOptions.set(ValueLayout.JAVA_BOOLEAN, offset, false)
        offset += 1
        offset += 1 // padding
        // ui_port = 0
        devServerOptions.set(ValueLayout.JAVA_SHORT, offset, 0.toShort())
        offset += 2
        offset += 4 // padding

        // log_format ByteArrayRef - "text" as default
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, devServerOptions, offset, "text")

        // log_level ByteArrayRef - "warn" for dev server
        TemporalCoreFfmUtil.writeByteArrayRef(arena, devServerOptions, offset, "warn")

        // Create callback stub
        val callbackStub = createStartCallbackStub(arena, runtimePtr, callback)

        // Call native function
        devServerStartHandle.invokeExact(runtimePtr, devServerOptions, MemorySegment.NULL, callbackStub)
    }

    /**
     * Starts a test server (with time skipping support).
     *
     * @param runtimePtr Pointer to the runtime
     * @param arena Arena for allocations (must outlive the callback)
     * @param existingPath Path to existing test server binary (skips download if set)
     * @param downloadVersion Version to download ("default", "latest", or semver)
     * @param downloadTtlSeconds Cache duration for downloads (0 = no TTL)
     * @param callback Callback invoked when server starts or fails
     */
    fun startTestServer(
        runtimePtr: MemorySegment,
        arena: Arena,
        existingPath: String? = null,
        downloadVersion: String? = "default",
        downloadTtlSeconds: Long = 0,
        callback: StartCallback,
    ) {
        // Allocate TestServerOptions
        val testServerOptions = arena.allocate(TemporalCoreFfmUtil.TEST_SERVER_OPTIONS_LAYOUT)
        var offset = 0L

        // existing_path ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, existingPath)

        // sdk_name ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, "temporal-kotlin")

        // sdk_version ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, "0.1.0")

        // download_version ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, downloadVersion)

        // download_dest_dir ByteArrayRef (empty = default)
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, null)

        // port = 0 (auto)
        testServerOptions.set(ValueLayout.JAVA_SHORT, offset, 0.toShort())
        offset += 8 // 2 + 6 padding

        // extra_args ByteArrayRef
        offset += TemporalCoreFfmUtil.writeByteArrayRef(arena, testServerOptions, offset, null)

        // download_ttl_seconds
        testServerOptions.set(ValueLayout.JAVA_LONG, offset, downloadTtlSeconds)

        // Create callback stub
        val callbackStub = createStartCallbackStub(arena, runtimePtr, callback)

        // Call native function
        testServerStartHandle.invokeExact(runtimePtr, testServerOptions, MemorySegment.NULL, callbackStub)
    }

    /**
     * Frees an ephemeral server.
     *
     * @param serverPtr Pointer to the server to free
     */
    fun freeServer(serverPtr: MemorySegment) {
        ephemeralServerFreeHandle.invokeExact(serverPtr)
    }

    /**
     * Shuts down an ephemeral server.
     *
     * @param serverPtr Pointer to the server
     * @param arena Arena for callback stub allocation
     * @param runtimePtr Pointer to the runtime
     * @param callback Callback invoked when shutdown completes
     */
    fun shutdownServer(
        serverPtr: MemorySegment,
        arena: Arena,
        runtimePtr: MemorySegment,
        callback: ShutdownCallback,
    ) {
        val callbackStub = createShutdownCallbackStub(arena, runtimePtr, callback)
        ephemeralServerShutdownHandle.invokeExact(serverPtr, MemorySegment.NULL, callbackStub)
    }
}
