package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.NativeLoader
import com.surrealdev.temporal.core.internal.TemporalCoreRuntime
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the native library loading and FFM communication.
 *
 * Note: These tests require the native library to be built first.
 * Run `./gradlew :core-bridge:cargoBuild` before running tests.
 */
class NativeLoaderTest {
    @Test
    fun `native library loads successfully`() {
        NativeLoader.load()
        assertTrue(NativeLoader.isLoaded(), "Native library should be loaded")
    }

    @Test
    fun `multiple load calls are safe`() {
        NativeLoader.load()
        NativeLoader.load()
        NativeLoader.load()
        assertTrue(NativeLoader.isLoaded())
    }

    @Test
    fun `can create and free runtime`() {
        Arena.ofConfined().use { arena ->
            val runtimePtr = TemporalCoreRuntime.createRuntime(arena)
            assertNotEquals(MemorySegment.NULL, runtimePtr, "Runtime pointer should not be null")

            // Clean up
            TemporalCoreRuntime.freeRuntime(runtimePtr)
        }
    }

    @Test
    fun `can create multiple runtimes`() {
        Arena.ofConfined().use { arena ->
            val runtime1 = TemporalCoreRuntime.createRuntime(arena)
            val runtime2 = TemporalCoreRuntime.createRuntime(arena)

            assertNotEquals(MemorySegment.NULL, runtime1)
            assertNotEquals(MemorySegment.NULL, runtime2)
            assertNotEquals(runtime1, runtime2, "Each runtime should have unique pointer")

            // Clean up
            TemporalCoreRuntime.freeRuntime(runtime1)
            TemporalCoreRuntime.freeRuntime(runtime2)
        }
    }
}
