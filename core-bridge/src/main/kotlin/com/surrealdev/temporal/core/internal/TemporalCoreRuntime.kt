package com.surrealdev.temporal.core.internal

import com.surrealdev.temporal.core.TemporalCoreException
import io.temporal.sdkbridge.TemporalCoreRuntimeOptions
import io.temporal.sdkbridge.TemporalCoreRuntimeOrFail
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

/**
 * FFM bridge for Temporal Core runtime operations.
 *
 * The runtime is the foundational object that must be created before
 * any other Temporal Core operations. It manages the Tokio async runtime
 * and other shared resources.
 *
 * Uses jextract-generated bindings for direct function calls.
 */
internal object TemporalCoreRuntime {
    init {
        // Ensure native library is loaded before using generated bindings
        TemporalCoreFfmUtil.ensureLoaded()
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Creates a new Temporal Core runtime with default options.
     *
     * @param arena The arena to allocate memory from
     * @return A pointer to the created runtime
     * @throws TemporalCoreException if runtime creation fails
     */
    fun createRuntime(arena: Arena): MemorySegment {
        // Allocate RuntimeOptions with null telemetry and default heartbeat interval
        val options = TemporalCoreRuntimeOptions.allocate(arena)
        TemporalCoreRuntimeOptions.telemetry(options, MemorySegment.NULL)
        TemporalCoreRuntimeOptions.worker_heartbeat_interval_millis(options, 0L)

        // Call temporal_core_runtime_new - returns struct by value
        val result = CoreBridge.temporal_core_runtime_new(arena, options)

        val runtimePtr = TemporalCoreRuntimeOrFail.runtime(result)
        val failPtr = TemporalCoreRuntimeOrFail.fail(result)

        // Check for error
        if (failPtr != MemorySegment.NULL) {
            val errorMessage = TemporalCoreFfmUtil.readByteArray(failPtr)
            // Free the error byte array
            CoreBridge.temporal_core_byte_array_free(runtimePtr, failPtr)
            // Still need to free the runtime even on error (per C API docs)
            if (runtimePtr != MemorySegment.NULL) {
                CoreBridge.temporal_core_runtime_free(runtimePtr)
            }
            throw TemporalCoreException(errorMessage ?: "Unknown error creating runtime")
        }

        return runtimePtr
    }

    /**
     * Creates a new Temporal Core runtime with custom telemetry options.
     *
     * @param arena The arena to allocate memory from
     * @param telemetryOptions Pointer to telemetry options (or NULL for default)
     * @param workerHeartbeatIntervalMillis Worker heartbeat interval (0 for default)
     * @return A pointer to the created runtime
     * @throws TemporalCoreException if runtime creation fails
     */
    fun createRuntime(
        arena: Arena,
        telemetryOptions: MemorySegment,
        workerHeartbeatIntervalMillis: Long = 0L,
    ): MemorySegment {
        val options = TemporalCoreRuntimeOptions.allocate(arena)
        TemporalCoreRuntimeOptions.telemetry(options, telemetryOptions)
        TemporalCoreRuntimeOptions.worker_heartbeat_interval_millis(options, workerHeartbeatIntervalMillis)

        val result = CoreBridge.temporal_core_runtime_new(arena, options)

        val runtimePtr = TemporalCoreRuntimeOrFail.runtime(result)
        val failPtr = TemporalCoreRuntimeOrFail.fail(result)

        if (failPtr != MemorySegment.NULL) {
            val errorMessage = TemporalCoreFfmUtil.readByteArray(failPtr)
            CoreBridge.temporal_core_byte_array_free(runtimePtr, failPtr)
            if (runtimePtr != MemorySegment.NULL) {
                CoreBridge.temporal_core_runtime_free(runtimePtr)
            }
            throw TemporalCoreException(errorMessage ?: "Unknown error creating runtime")
        }

        return runtimePtr
    }

    /**
     * Frees a Temporal Core runtime.
     *
     * @param runtimePtr Pointer to the runtime to free
     */
    fun freeRuntime(runtimePtr: MemorySegment) {
        CoreBridge.temporal_core_runtime_free(runtimePtr)
    }

    /**
     * Frees a byte array allocated by the Temporal Core.
     *
     * @param runtimePtr Pointer to the runtime
     * @param byteArrayPtr Pointer to the byte array to free
     */
    fun freeByteArray(
        runtimePtr: MemorySegment,
        byteArrayPtr: MemorySegment,
    ) {
        CoreBridge.temporal_core_byte_array_free(runtimePtr, byteArrayPtr)
    }
}
