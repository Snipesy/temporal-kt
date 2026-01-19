package com.surrealdev.temporal.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for TemporalRuntime wrapper class.
 */
class TemporalRuntimeTest {
    @Test
    fun `can create runtime`() {
        val runtime = TemporalRuntime.create()
        assertFalse(runtime.isClosed())
        runtime.close()
    }

    @Test
    fun `runtime can be used with use block`() {
        TemporalRuntime.create().use { runtime ->
            assertFalse(runtime.isClosed())
        }
    }

    @Test
    fun `runtime is closed after use block`() {
        val runtime = TemporalRuntime.create()
        runtime.use { }
        assertTrue(runtime.isClosed())
    }

    @Test
    fun `multiple close calls are safe`() {
        val runtime = TemporalRuntime.create()
        runtime.close()
        runtime.close()
        runtime.close()
        assertTrue(runtime.isClosed())
    }

    @Test
    fun `ensureOpen throws after close`() {
        val runtime = TemporalRuntime.create()
        runtime.close()
        assertFailsWith<IllegalStateException> {
            runtime.ensureOpen()
        }
    }

    @Test
    fun `can create multiple runtimes`() {
        val runtime1 = TemporalRuntime.create()
        val runtime2 = TemporalRuntime.create()

        assertFalse(runtime1.isClosed())
        assertFalse(runtime2.isClosed())

        runtime1.close()
        assertTrue(runtime1.isClosed())
        assertFalse(runtime2.isClosed())

        runtime2.close()
        assertTrue(runtime2.isClosed())
    }
}
