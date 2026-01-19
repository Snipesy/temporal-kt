package com.surrealdev.temporal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for TemporalRandom - the deterministic RNG from Temporal Core.
 */
class TemporalRandomTest {
    @Test
    fun `can create and close random`() {
        val random = TemporalRandom(seed = 12345L)
        assertFalse(random.isClosed())
        random.close()
        assertTrue(random.isClosed())
    }

    @Test
    fun `random can be used with use block`() {
        TemporalRandom(seed = 12345L).use { random ->
            assertFalse(random.isClosed())
        }
    }

    @Test
    fun `nextInt returns values in range`() {
        TemporalRandom(seed = 42L).use { random ->
            repeat(100) {
                val value = random.nextInt(min = 0, max = 10)
                assertTrue(value >= 0, "Value $value should be >= 0")
                assertTrue(value < 10, "Value $value should be < 10")
            }
        }
    }

    @Test
    fun `nextInt with maxInclusive includes max`() {
        TemporalRandom(seed = 42L).use { random ->
            // Generate many values and check if max is ever produced
            val values = (1..1000).map { random.nextInt(min = 0, max = 5, maxInclusive = true) }
            assertTrue(values.any { it == 5 }, "With maxInclusive=true, max value should be possible")
            assertTrue(values.all { it in 0..5 }, "All values should be in range [0, 5]")
        }
    }

    @Test
    fun `nextDouble returns values in range`() {
        TemporalRandom(seed = 42L).use { random ->
            repeat(100) {
                val value = random.nextDouble(min = 0.0, max = 1.0)
                assertTrue(value >= 0.0, "Value $value should be >= 0.0")
                assertTrue(value < 1.0, "Value $value should be < 1.0")
            }
        }
    }

    @Test
    fun `nextBytes fills array with data`() {
        TemporalRandom(seed = 42L).use { random ->
            val bytes = ByteArray(16)
            // Initially all zeros
            assertTrue(bytes.all { it == 0.toByte() })

            random.nextBytes(bytes)

            // After filling, should have some non-zero bytes
            assertTrue(bytes.any { it != 0.toByte() }, "Random bytes should contain non-zero values")
        }
    }

    @Test
    fun `nextBytes with size returns new array`() {
        TemporalRandom(seed = 42L).use { random ->
            val bytes = random.nextBytes(32)
            assertEquals(32, bytes.size)
            assertTrue(bytes.any { it != 0.toByte() }, "Random bytes should contain non-zero values")
        }
    }

    @Test
    fun `same seed produces same sequence`() {
        val values1 =
            TemporalRandom(seed = 12345L).use { random ->
                (1..10).map { random.nextInt(0, 1000) }
            }

        val values2 =
            TemporalRandom(seed = 12345L).use { random ->
                (1..10).map { random.nextInt(0, 1000) }
            }

        assertEquals(values1, values2, "Same seed should produce identical sequence")
    }

    @Test
    fun `different seeds produce different sequences`() {
        val values1 =
            TemporalRandom(seed = 12345L).use { random ->
                (1..10).map { random.nextInt(0, 1000) }
            }

        val values2 =
            TemporalRandom(seed = 54321L).use { random ->
                (1..10).map { random.nextInt(0, 1000) }
            }

        assertTrue(values1 != values2, "Different seeds should produce different sequences")
    }

    @Test
    fun `throws after close`() {
        val random = TemporalRandom(seed = 42L)
        random.close()

        assertFailsWith<IllegalStateException> {
            random.nextInt()
        }
    }

    @Test
    fun `multiple close calls are safe`() {
        val random = TemporalRandom(seed = 42L)
        random.close()
        random.close()
        random.close()
        assertTrue(random.isClosed())
    }
}
