package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreDevServerOptions
import io.temporal.sdkbridge.TemporalCoreEphemeralServerShutdownCallback
import io.temporal.sdkbridge.TemporalCoreEphemeralServerStartCallback
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

        val callbackStub = createStartCallbackStub(arena, runtimePtr, callback)
        CoreBridge.temporal_core_ephemeral_server_start_dev_server(
            runtimePtr,
            devServerOptions,
            MemorySegment.NULL,
            callbackStub,
        )
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
        val testServerOptions = buildTestServerOptions(arena, existingPath, downloadVersion, downloadTtlSeconds)
        val callbackStub = createStartCallbackStub(arena, runtimePtr, callback)
        CoreBridge.temporal_core_ephemeral_server_start_test_server(
            runtimePtr,
            testServerOptions,
            MemorySegment.NULL,
            callbackStub,
        )
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
        CoreBridge.temporal_core_ephemeral_server_shutdown(serverPtr, MemorySegment.NULL, callbackStub)
    }

    // ============================================================
    // Callback Stub Creation (using jextract-generated callback classes)
    // ============================================================

    /**
     * Creates an upcall stub for the server start callback using jextract-generated callback class.
     */
    private fun createStartCallbackStub(
        arena: Arena,
        runtimePtr: MemorySegment,
        callback: StartCallback,
    ): MemorySegment =
        TemporalCoreEphemeralServerStartCallback.allocate(
            { _, serverPtr, targetUrlPtr, failPtr ->
                val targetUrl =
                    if (targetUrlPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArray(targetUrlPtr)
                    } else {
                        null
                    }
                val error =
                    if (failPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArray(failPtr)
                    } else {
                        null
                    }

                // Free the byte arrays if present
                if (targetUrlPtr != MemorySegment.NULL) {
                    CoreBridge.temporal_core_byte_array_free(runtimePtr, targetUrlPtr)
                }
                if (failPtr != MemorySegment.NULL) {
                    CoreBridge.temporal_core_byte_array_free(runtimePtr, failPtr)
                }

                callback.onComplete(
                    if (serverPtr != MemorySegment.NULL) serverPtr else null,
                    targetUrl,
                    error,
                )
            },
            arena,
        )

    /**
     * Creates an upcall stub for the server shutdown callback using jextract-generated callback class.
     */
    private fun createShutdownCallbackStub(
        arena: Arena,
        runtimePtr: MemorySegment,
        callback: ShutdownCallback,
    ): MemorySegment =
        TemporalCoreEphemeralServerShutdownCallback.allocate(
            { _, failPtr ->
                val error =
                    if (failPtr != MemorySegment.NULL) {
                        TemporalCoreFfmUtil.readByteArray(failPtr)
                    } else {
                        null
                    }

                // Free the byte array if present
                if (failPtr != MemorySegment.NULL) {
                    CoreBridge.temporal_core_byte_array_free(runtimePtr, failPtr)
                }

                callback.onComplete(error)
            },
            arena,
        )
}
