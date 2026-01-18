package com.surrealdev.temporal.core.internal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Low-level FFM bridge to the Temporal Rust Core.
 *
 * This object uses Java's Foreign Function & Memory (FFM) API to call
 * into the native Rust library. Higher-level APIs (TemporalCoreClient,
 * TemporalCoreWorker) should be built on top of these primitives.
 *
 * All methods in this class are internal implementation details and should not be
 * used directly by application code.
 */
object TemporalCoreBridge {
    private val linker: Linker = Linker.nativeLinker()
    private val lookup: SymbolLookup = NativeLoader.load()

    // Method handles for native functions
    private val initHandle: MethodHandle
    private val versionHandle: MethodHandle
    private val echoHandle: MethodHandle
    private val freeStringHandle: MethodHandle

    init {

        // temporal_init() -> c_long
        initHandle =
            linker.downcallHandle(
                lookup.find("temporal_init").orElseThrow {
                    UnsatisfiedLinkError("Symbol not found: temporal_init")
                },
                FunctionDescriptor.of(ValueLayout.JAVA_LONG),
            )

        // temporal_version() -> *const c_char
        versionHandle =
            linker.downcallHandle(
                lookup.find("temporal_version").orElseThrow {
                    UnsatisfiedLinkError("Symbol not found: temporal_version")
                },
                FunctionDescriptor.of(ValueLayout.ADDRESS),
            )

        // temporal_echo(input: *const c_char) -> *mut c_char
        echoHandle =
            linker.downcallHandle(
                lookup.find("temporal_echo").orElseThrow {
                    UnsatisfiedLinkError("Symbol not found: temporal_echo")
                },
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )

        // temporal_free_string(ptr: *mut c_char)
        freeStringHandle =
            linker.downcallHandle(
                lookup.find("temporal_free_string").orElseThrow {
                    UnsatisfiedLinkError("Symbol not found: temporal_free_string")
                },
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            )

        // Initialize the Rust runtime
        val result = initHandle.invokeExact() as Long
        if (result != 0L) {
            throw IllegalStateException("Failed to initialize Temporal Core native library")
        }
    }

    /**
     * Returns the version of the native Rust library.
     *
     * This is useful for debugging and ensuring the correct library is loaded.
     */
    fun nativeVersion(): String {
        val ptr = versionHandle.invokeExact() as MemorySegment
        return ptr.reinterpret(Long.MAX_VALUE).getString(0)
    }

    /**
     * Simple echo function to verify FFM communication.
     *
     * @param input The string to echo
     * @return The input string prefixed with "Echo from Rust: "
     */
    fun nativeEcho(input: String): String {
        Arena.ofConfined().use { tempArena ->
            val inputSegment = tempArena.allocateFrom(input)
            val resultPtr = echoHandle.invokeExact(inputSegment) as MemorySegment

            if (resultPtr == MemorySegment.NULL) {
                throw IllegalStateException("Native echo returned null")
            }

            // Read the result string
            val result = resultPtr.reinterpret(Long.MAX_VALUE).getString(0)

            // Free the Rust-allocated string
            freeStringHandle.invokeExact(resultPtr)

            return result
        }
    }

    // Future methods will be added here:
    // - createClient(config: ByteArray): Long
    // - destroyClient(handle: Long)
    // - createWorker(clientHandle: Long, config: ByteArray): Long
    // - destroyWorker(handle: Long)
    // - pollWorkflowTask(workerHandle: Long): ByteArray
    // - completeWorkflowTask(workerHandle: Long, completion: ByteArray)
    // - pollActivityTask(workerHandle: Long): ByteArray
    // - completeActivityTask(workerHandle: Long, completion: ByteArray)
}
