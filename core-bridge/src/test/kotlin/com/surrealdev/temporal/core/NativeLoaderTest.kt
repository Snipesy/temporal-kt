package com.surrealdev.temporal.core

import com.surrealdev.temporal.core.internal.NativeLoader
import com.surrealdev.temporal.core.internal.TemporalCoreBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the native library loading and FFM communication.
 *
 * Note: These tests require the native library to be built first.
 * Run `cargo build --release` in the `rust/` directory before running tests.
 */
class NativeLoaderTest {
    @Test
    fun `native library loads successfully`() {
        NativeLoader.load()
        assertTrue(NativeLoader.isLoaded(), "Native library should be loaded")
    }

    @Test
    fun `nativeVersion returns valid version string`() {
        val version = TemporalCoreBridge.nativeVersion()
        assertNotNull(version, "Version should not be null")
        assertTrue(version.isNotEmpty(), "Version should not be empty")
        // Version should match semver pattern
        assertTrue(
            version.matches(Regex("""\d+\.\d+\.\d+.*""")),
            "Version '$version' should match semver pattern",
        )
    }

    @Test
    fun `nativeEcho returns echoed string`() {
        val input = "Hello, Temporal!"
        val result = TemporalCoreBridge.nativeEcho(input)
        assertEquals("Echo from Rust: $input", result)
    }

    @Test
    fun `nativeEcho handles unicode strings`() {
        val input = "Hello 世界 🌍"
        val result = TemporalCoreBridge.nativeEcho(input)
        assertEquals("Echo from Rust: $input", result)
    }

    @Test
    fun `nativeEcho handles empty string`() {
        val result = TemporalCoreBridge.nativeEcho("")
        assertEquals("Echo from Rust: ", result)
    }

    @Test
    fun `multiple load calls are safe`() {
        NativeLoader.load()
        NativeLoader.load()
        NativeLoader.load()
        assertTrue(NativeLoader.isLoaded())
    }
}
