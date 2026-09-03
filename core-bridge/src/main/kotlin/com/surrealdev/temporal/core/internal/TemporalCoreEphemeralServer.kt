package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreDevServerOptions
import io.temporal.sdkbridge.TemporalCoreTestServerOptions
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
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
        extraArgs: List<String> = emptyList(),
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
        // extra_args is newline-delimited in the C bridge
        val extraArgsString = extraArgs.joinToString("\n").ifEmpty { null }
        TemporalCoreTestServerOptions.extra_args(opts, TemporalCoreFfmUtil.createByteArrayRef(arena, extraArgsString))
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
     * @param extraArgs Additional CLI arguments to pass to the server
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
        extraArgs: List<String> = emptyList(),
        callback: StartCallback,
    ): MemorySegment {
        val testServerOptions =
            buildTestServerOptions(arena, existingPath, downloadVersion, downloadTtlSeconds, extraArgs)
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
     * @param extraArgs Additional CLI arguments to pass to the server
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
        extraArgs: List<String> = emptyList(),
        callback: StartCallback,
    ): MemorySegment {
        val testServerOptions =
            buildTestServerOptions(arena, existingPath, downloadVersion, downloadTtlSeconds, extraArgs)
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
     * Returns the OS process ID of the server's child process, or null if Core no longer
     * tracks one (already shut down, or the process exited).
     *
     * `temporal_core_ephemeral_server_pid` is a temporal-kt addition to the C bridge and is
     * not part of the jextract-generated bindings, hence the hand-written downcall.
     */
    fun pid(serverPtr: MemorySegment): Long? {
        val pid = pidHandle.invokeExact(serverPtr) as Int
        return if (pid == 0) null else pid.toLong() and 0xFFFF_FFFFL
    }

    private val pidHandle: MethodHandle by lazy {
        val address =
            NativeLoader
                .load()
                .find("temporal_core_ephemeral_server_pid")
                .orElseThrow {
                    UnsatisfiedLinkError(
                        "temporal_core_ephemeral_server_pid is missing from the native library; " +
                            "rebuild core-bridge (the C bridge patch adds it)",
                    )
                }
        Linker
            .nativeLinker()
            .downcallHandle(address, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
    }

    /**
     * Synchronously shuts down and frees a server whose Kotlin wrapper was never constructed
     * (a failure between the native start succeeding and the wrapper taking ownership).
     * Blocks until Core has killed the child process, bounded by [SHUTDOWN_TIMEOUT_SECONDS].
     */
    fun shutdownAndFree(
        serverPtr: MemorySegment,
        dispatcher: EphemeralServerCallbackDispatcher,
    ) {
        val done = java.util.concurrent.CompletableFuture<Unit>()
        shutdownServer(serverPtr, dispatcher) { done.complete(Unit) }
        try {
            done.get(SHUTDOWN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Best effort; the process may already be gone
        }
        dispatcher.awaitPendingCallbacks()
        freeServer(serverPtr)
    }

    private const val SHUTDOWN_TIMEOUT_SECONDS = 30L

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
