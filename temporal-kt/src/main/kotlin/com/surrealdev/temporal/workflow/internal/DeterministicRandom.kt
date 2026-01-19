package com.surrealdev.temporal.workflow.internal

import java.util.Random

/**
 * Deterministic random number generator for use within workflows.
 *
 * This class provides random values that are seeded from a workflow-specific
 * random seed, ensuring that the same sequence of random values is produced
 * during replay as during the original execution.
 *
 * The seed is provided by the Temporal server and is updated via
 * UpdateRandomSeed activation jobs.
 */
internal class DeterministicRandom(
    seed: Long,
) {
    private val random = Random(seed)

    /**
     * Updates the random seed.
     * Called when an UpdateRandomSeed job is received.
     */
    fun updateSeed(newSeed: Long) {
        random.setSeed(newSeed)
    }

    /**
     * Generates a deterministic UUID.
     *
     * The UUID is constructed from two random longs, ensuring it's
     * reproducible during replay when given the same seed.
     *
     * @return A UUID string in the standard format (8-4-4-4-12)
     */
    fun randomUuid(): String {
        val mostSignificantBits = random.nextLong()
        val leastSignificantBits = random.nextLong()

        // Use Java's UUID to handle the version/variant bit manipulation
        val uuid =
            java.util.UUID(
                // Clear version bits and set to 4
                (mostSignificantBits and VERSION_MASK) or VERSION_4,
                // Clear variant bits and set variant to IETF
                (leastSignificantBits and VARIANT_MASK) or VARIANT_IETF,
            )

        return uuid.toString()
    }

    companion object {
        // Mask to clear version bits (bits 12-15 of MSB)
        private const val VERSION_MASK = -0x000000000000f001L // ~0xf000L
        private const val VERSION_4 = 0x0000000000004000L

        // Mask to clear variant bits (bits 62-63 of LSB)
        private const val VARIANT_MASK = 0x3fffffffffffffffL
        private val VARIANT_IETF = Long.MIN_VALUE // 0x8000000000000000L
    }

    /**
     * Generates a random integer.
     */
    fun nextInt(): Int = random.nextInt()

    /**
     * Generates a random integer between 0 (inclusive) and bound (exclusive).
     */
    fun nextInt(bound: Int): Int = random.nextInt(bound)

    /**
     * Generates a random long.
     */
    fun nextLong(): Long = random.nextLong()

    /**
     * Generates a random double between 0.0 and 1.0.
     */
    fun nextDouble(): Double = random.nextDouble()

    /**
     * Generates a random boolean.
     */
    fun nextBoolean(): Boolean = random.nextBoolean()
}
