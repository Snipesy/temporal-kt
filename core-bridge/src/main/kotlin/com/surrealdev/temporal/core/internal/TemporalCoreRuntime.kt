package com.surrealdev.temporal.core.internal

import com.surrealdev.temporal.core.TemporalCoreException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * FFM bridge for Temporal Core runtime operations.
 *
 * The runtime is the foundational object that must be created before
 * any other Temporal Core operations. It manages the Tokio async runtime
 * and other shared resources.
 */
internal object TemporalCoreRuntime {
    private val linker = TemporalCoreFfmUtil.linker
    private val lookup = TemporalCoreFfmUtil.lookup

    // ============================================================
    // Method Handles
    // ============================================================

    // temporal_core_runtime_new(const TemporalCoreRuntimeOptions *options) -> TemporalCoreRuntimeOrFail
    private val runtimeNewHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_runtime_new"),
            FunctionDescriptor.of(TemporalCoreFfmUtil.RUNTIME_OR_FAIL_LAYOUT, ValueLayout.ADDRESS),
        )
    }

    // temporal_core_runtime_free(TemporalCoreRuntime *runtime) -> void
    private val runtimeFreeHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_runtime_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    }

    // temporal_core_byte_array_free(TemporalCoreRuntime *runtime, const TemporalCoreByteArray *bytes) -> void
    private val byteArrayFreeHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_byte_array_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
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
        val options = arena.allocate(TemporalCoreFfmUtil.RUNTIME_OPTIONS_LAYOUT)
        options.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL) // telemetry = null
        options.set(ValueLayout.JAVA_LONG, 8, 0L) // worker_heartbeat_interval_millis = 0 (default)

        // Call temporal_core_runtime_new - returns struct by value
        val allocator: SegmentAllocator = arena
        val result = runtimeNewHandle.invokeExact(allocator, options) as MemorySegment

        val runtimePtr = result.get(ValueLayout.ADDRESS, 0)
        val failPtr = result.get(ValueLayout.ADDRESS, 8)

        // Check for error
        if (failPtr != MemorySegment.NULL) {
            val errorMessage = TemporalCoreFfmUtil.readByteArray(failPtr)
            // Free the error byte array
            byteArrayFreeHandle.invokeExact(runtimePtr, failPtr)
            // Still need to free the runtime even on error (per C API docs)
            if (runtimePtr != MemorySegment.NULL) {
                runtimeFreeHandle.invokeExact(runtimePtr)
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
        runtimeFreeHandle.invokeExact(runtimePtr)
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
        byteArrayFreeHandle.invokeExact(runtimePtr, byteArrayPtr)
    }
}
