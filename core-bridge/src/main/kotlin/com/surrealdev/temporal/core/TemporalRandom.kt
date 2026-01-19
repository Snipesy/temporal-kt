package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.TemporalCoreRandom
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * A deterministic random number generator from Temporal Core.
 *
 * This RNG is seeded and produces deterministic sequences, which is
 * essential for workflow replay determinism. Given the same seed,
 * the same sequence of random values will be produced.
 *
 * Example usage:
 * ```kotlin
 * TemporalRandom(seed = 12345L).use { random ->
 *     val dice = random.nextInt(1, 6, maxInclusive = true)
 *     val probability = random.nextDouble(0.0, 1.0)
 * }
 * ```
 */
class TemporalRandom(
    seed: Long,
) : AutoCloseable {
    private val handle: MemorySegment = TemporalCoreRandom.createRandom(seed)
    private val arena: Arena = Arena.ofConfined()

    @Volatile
    private var closed = false

    /**
     * Generates a random 32-bit integer in the range [min, max) or [min, max].
     *
     * @param min Minimum value (inclusive)
     * @param max Maximum value
     * @param maxInclusive If true, max is inclusive; if false, max is exclusive
     * @return A random integer in the specified range
     */
    fun nextInt(
        min: Int = 0,
        max: Int = Int.MAX_VALUE,
        maxInclusive: Boolean = false,
    ): Int {
        ensureOpen()
        return TemporalCoreRandom.randomInt32Range(handle, min, max, maxInclusive)
    }

    /**
     * Generates a random double in the range [min, max) or [min, max].
     *
     * @param min Minimum value (inclusive)
     * @param max Maximum value
     * @param maxInclusive If true, max is inclusive; if false, max is exclusive
     * @return A random double in the specified range
     */
    fun nextDouble(
        min: Double = 0.0,
        max: Double = 1.0,
        maxInclusive: Boolean = false,
    ): Double {
        ensureOpen()
        return TemporalCoreRandom.randomDoubleRange(handle, min, max, maxInclusive)
    }

    /**
     * Fills the given byte array with random bytes.
     *
     * @param bytes The byte array to fill with random data
     */
    fun nextBytes(bytes: ByteArray) {
        ensureOpen()
        TemporalCoreRandom.randomFillBytes(handle, arena, bytes)
    }

    /**
     * Generates a new byte array of the specified size filled with random bytes.
     *
     * @param size The number of random bytes to generate
     * @return A new byte array filled with random data
     */
    fun nextBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        nextBytes(bytes)
        return bytes
    }

    /**
     * Checks if this random generator has been closed.
     */
    fun isClosed(): Boolean = closed

    private fun ensureOpen() {
        if (closed) {
            throw IllegalStateException("Random generator has been closed")
        }
    }

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            TemporalCoreRandom.freeRandom(handle)
            arena.close()
        }
    }
}
