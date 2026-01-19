package com.surrealdev.temporal.core.internal

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

/**
 * FFM bridge for Temporal Core deterministic random number generation.
 *
 * Workflows must use deterministic randomness to ensure replay consistency.
 * This bridge provides access to the seeded RNG in Temporal Core.
 *
 * Uses jextract-generated bindings for direct function calls.
 */
internal object TemporalCoreRandom {
    init {
        // Ensure native library is loaded before using generated bindings
        TemporalCoreFfmUtil.ensureLoaded()
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
    fun createRandom(seed: Long): MemorySegment = CoreBridge.temporal_core_random_new(seed)

    /**
     * Frees a random number generator.
     *
     * @param randomPtr Pointer to the random generator to free
     */
    fun freeRandom(randomPtr: MemorySegment) {
        CoreBridge.temporal_core_random_free(randomPtr)
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
    ): Int = CoreBridge.temporal_core_random_int32_range(randomPtr, min, max, maxInclusive)

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
    ): Double = CoreBridge.temporal_core_random_double_range(randomPtr, min, max, maxInclusive)

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

        // Create ByteArrayRef pointing to the native memory (pass struct by value)
        val ref = TemporalCoreFfmUtil.createByteArrayRef(arena, dataSegment, bytes.size.toLong())

        // Call the native function
        CoreBridge.temporal_core_random_fill_bytes(randomPtr, ref)

        // Copy the filled bytes back to the Kotlin array
        MemorySegment.copy(dataSegment, ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.size)
    }
}
