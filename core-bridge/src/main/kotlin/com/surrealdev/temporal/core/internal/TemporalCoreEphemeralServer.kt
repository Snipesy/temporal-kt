package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreDevServerOptions
import io.temporal.sdkbridge.TemporalCoreTestServerOptions
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

/**
 * FFM bridge for Temporal Core ephemeral server operations.
 *
 * Ephemeral servers are used for testing - they provide a local Temporal
 * server instance that can be started and stopped programmatically.
 *
 * Uses jextract-generated bindings for direct function calls and callbacks.
 */
internal object TemporalCoreEphemeralServer {
    init {
        // Ensure native library is loaded before using generated bindings
        TemporalCoreFfmUtil.ensureLoaded()
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
    // Struct Builders
    // ============================================================

    private fun buildTestServerOptions(
        arena: Arena,
        existingPath: String?,
        downloadVersion: String?,
        downloadTtlSeconds: Long,
    ): MemorySegment {
        val opts = TemporalCoreTestServerOptions.allocate(arena)

        TemporalCoreTestServerOptions.existing_path(opts, TemporalCoreFfmUtil.createByteArrayRef(arena, existingPath))
        TemporalCoreTestServerOptions.sdk_name(opts, TemporalCoreFfmUtil.createByteArrayRef(arena, "temporal-kotlin"))
        TemporalCoreTestServerOptions.sdk_version(opts, TemporalCoreFfmUtil.createByteArrayRef(arena, "0.1.0"))
        TemporalCoreTestServerOptions.download_version(
            opts,
            TemporalCoreFfmUtil.createByteArrayRef(arena, downloadVersion),
        )
        TemporalCoreTestServerOptions.download_dest_dir(opts, TemporalCoreFfmUtil.createEmptyByteArrayRef(arena))
        TemporalCoreTestServerOptions.port(opts, 0.toShort())
        TemporalCoreTestServerOptions.extra_args(opts, TemporalCoreFfmUtil.createEmptyByteArrayRef(arena))
        TemporalCoreTestServerOptions.download_ttl_seconds(opts, downloadTtlSeconds)

        return opts
    }

    private fun buildDevServerOptions(
        arena: Arena,
        testServerOptions: MemorySegment,
        namespace: String,
        ip: String,
        databaseFilename: String?,
        ui: Boolean,
        uiPort: Short,
        logFormat: String,
        logLevel: String,
    ): MemorySegment {
        val opts = TemporalCoreDevServerOptions.allocate(arena)

        TemporalCoreDevServerOptions.test_server(opts, testServerOptions)
        TemporalCoreDevServerOptions.namespace_(opts, TemporalCoreFfmUtil.createByteArrayRef(arena, namespace))
        TemporalCoreDevServerOptions.ip(opts, TemporalCoreFfmUtil.createByteArrayRef(arena, ip))
        TemporalCoreDevServerOptions.database_filename(
            opts,
            TemporalCoreFfmUtil.createByteArrayRef(arena, databaseFilename),
        )
        TemporalCoreDevServerOptions.ui(opts, ui)
        TemporalCoreDevServerOptions.ui_port(opts, uiPort)
        TemporalCoreDevServerOptions.log_format(opts, TemporalCoreFfmUtil.createByteArrayRef(arena, logFormat))
        TemporalCoreDevServerOptions.log_level(opts, TemporalCoreFfmUtil.createByteArrayRef(arena, logLevel))

        return opts
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Starts a development server using a reusable callback dispatcher.
     *
     * @param runtimePtr Pointer to the runtime
     * @param arena Arena for allocations (for options, not callback stub)
     * @param dispatcher The callback dispatcher with reusable stubs
     * @param namespace The namespace to use
     * @param ip The IP address to bind to
     * @param existingPath Path to existing Temporal CLI (skips download if set)
     * @param downloadVersion Version to download
     * @param downloadTtlSeconds Cache duration for downloads
     * @param callback Callback invoked when server starts or fails
     * @return The context pointer for cancellation
     */
    fun startDevServer(
        runtimePtr: MemorySegment,
        arena: Arena,
        dispatcher: EphemeralServerCallbackDispatcher,
        namespace: String = "default",
        ip: String = "127.0.0.1",
        existingPath: String? = null,
        downloadVersion: String? = "default",
        downloadTtlSeconds: Long = 0,
        callback: StartCallback,
    ): MemorySegment {
        val testServerOptions = buildTestServerOptions(arena, existingPath, downloadVersion, downloadTtlSeconds)
        val devServerOptions =
            buildDevServerOptions(
                arena = arena,
                testServerOptions = testServerOptions,
                namespace = namespace,
                ip = ip,
                databaseFilename = null,
                ui = false,
                uiPort = 0,
                logFormat = "text",
                logLevel = "warn",
            )

        val contextPtr = dispatcher.registerStart(callback)
        CoreBridge.temporal_core_ephemeral_server_start_dev_server(
            runtimePtr,
            devServerOptions,
            contextPtr,
            dispatcher.startCallbackStub,
        )
        return contextPtr
    }

    /**
     * Starts a test server using a reusable callback dispatcher.
     *
     * @param runtimePtr Pointer to the runtime
     * @param arena Arena for allocations (for options, not callback stub)
     * @param dispatcher The callback dispatcher with reusable stubs
     * @param existingPath Path to existing test server binary
     * @param downloadVersion Version to download
     * @param downloadTtlSeconds Cache duration for downloads
     * @param callback Callback invoked when server starts or fails
     * @return The context pointer for cancellation
     */
    fun startTestServer(
        runtimePtr: MemorySegment,
        arena: Arena,
        dispatcher: EphemeralServerCallbackDispatcher,
        existingPath: String? = null,
        downloadVersion: String? = "default",
        downloadTtlSeconds: Long = 0,
        callback: StartCallback,
    ): MemorySegment {
        val testServerOptions = buildTestServerOptions(arena, existingPath, downloadVersion, downloadTtlSeconds)
        val contextPtr = dispatcher.registerStart(callback)
        CoreBridge.temporal_core_ephemeral_server_start_test_server(
            runtimePtr,
            testServerOptions,
            contextPtr,
            dispatcher.startCallbackStub,
        )
        return contextPtr
    }

    /**
     * Frees an ephemeral server.
     *
     * @param serverPtr Pointer to the server to free
     */
    fun freeServer(serverPtr: MemorySegment) {
        CoreBridge.temporal_core_ephemeral_server_free(serverPtr)
    }

    /**
     * Shuts down an ephemeral server using a reusable callback dispatcher.
     *
     * @param serverPtr Pointer to the server
     * @param dispatcher The callback dispatcher with reusable stubs
     * @param callback Callback invoked when shutdown completes
     * @return The context pointer for cancellation
     */
    fun shutdownServer(
        serverPtr: MemorySegment,
        dispatcher: EphemeralServerCallbackDispatcher,
        callback: ShutdownCallback,
    ): MemorySegment {
        val contextPtr = dispatcher.registerShutdown(callback)
        CoreBridge.temporal_core_ephemeral_server_shutdown(serverPtr, contextPtr, dispatcher.shutdownCallbackStub)
        return contextPtr
    }
}
