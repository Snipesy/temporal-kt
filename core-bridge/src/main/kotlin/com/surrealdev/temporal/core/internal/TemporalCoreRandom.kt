package com.surrealdev.temporal.core.internal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * FFM bridge for Temporal Core deterministic random number generation.
 *
 * Workflows must use deterministic randomness to ensure replay consistency.
 * This bridge provides access to the seeded RNG in Temporal Core.
 */
internal object TemporalCoreRandom {
    private val linker = TemporalCoreFfmUtil.linker

    // ============================================================
    // Method Handles
    // ============================================================

    // temporal_core_random_new(uint64_t seed) -> TemporalCoreRandom*
    private val randomNewHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_random_new"),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
        )
    }

    // temporal_core_random_free(TemporalCoreRandom *random) -> void
    private val randomFreeHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_random_free"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
    }

    // temporal_core_random_int32_range(TemporalCoreRandom *random, int32_t min, int32_t max, bool max_inclusive) -> int32_t
    private val randomInt32RangeHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_random_int32_range"),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_BOOLEAN,
            ),
        )
    }

    // temporal_core_random_double_range(TemporalCoreRandom *random, double min, double max, bool max_inclusive) -> double
    private val randomDoubleRangeHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_random_double_range"),
            FunctionDescriptor.of(
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.JAVA_BOOLEAN,
            ),
        )
    }

    // temporal_core_random_fill_bytes(TemporalCoreRandom *random, TemporalCoreByteArrayRef bytes) -> void
    private val randomFillBytesHandle: MethodHandle by lazy {
        linker.downcallHandle(
            TemporalCoreFfmUtil.findSymbol("temporal_core_random_fill_bytes"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, TemporalCoreFfmUtil.BYTE_ARRAY_REF_LAYOUT),
        )
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Creates a new deterministic random number generator with the given seed.
     *
     * @param seed The seed for the RNG
     * @return A pointer to the created random generator
     */
    fun createRandom(seed: Long): MemorySegment = randomNewHandle.invokeExact(seed) as MemorySegment

    /**
     * Frees a random number generator.
     *
     * @param randomPtr Pointer to the random generator to free
     */
    fun freeRandom(randomPtr: MemorySegment) {
        randomFreeHandle.invokeExact(randomPtr)
    }

    /**
     * Generates a random 32-bit integer in the given range.
     *
     * @param randomPtr Pointer to the random generator
     * @param min Minimum value (inclusive)
     * @param max Maximum value
     * @param maxInclusive Whether the max value is inclusive
     * @return A random integer in the range
     */
    fun randomInt32Range(
        randomPtr: MemorySegment,
        min: Int,
        max: Int,
        maxInclusive: Boolean,
    ): Int = randomInt32RangeHandle.invokeExact(randomPtr, min, max, maxInclusive) as Int

    /**
     * Generates a random double in the given range.
     *
     * @param randomPtr Pointer to the random generator
     * @param min Minimum value (inclusive)
     * @param max Maximum value
     * @param maxInclusive Whether the max value is inclusive
     * @return A random double in the range
     */
    fun randomDoubleRange(
        randomPtr: MemorySegment,
        min: Double,
        max: Double,
        maxInclusive: Boolean,
    ): Double = randomDoubleRangeHandle.invokeExact(randomPtr, min, max, maxInclusive) as Double

    /**
     * Fills a byte array with random bytes.
     *
     * @param randomPtr Pointer to the random generator
     * @param arena Arena to allocate temporary memory
     * @param bytes The byte array to fill
     */
    fun randomFillBytes(
        randomPtr: MemorySegment,
        arena: Arena,
        bytes: ByteArray,
    ) {
        // Allocate native memory for the bytes
        val dataSegment = arena.allocate(bytes.size.toLong())

        // Create ByteArrayRef pointing to the native memory
        val ref = arena.allocate(TemporalCoreFfmUtil.BYTE_ARRAY_REF_LAYOUT)
        ref.set(ValueLayout.ADDRESS, 0, dataSegment)
        ref.set(ValueLayout.JAVA_LONG, 8, bytes.size.toLong())

        // Call the native function (pass struct by value)
        randomFillBytesHandle.invokeExact(randomPtr, ref)

        // Copy the filled bytes back to the Kotlin array
        MemorySegment.copy(dataSegment, ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.size)
    }
}
