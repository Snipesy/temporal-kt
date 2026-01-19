package com.surrealdev.temporal.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for TemporalDevServer.
 *
 * These tests use auto-download to fetch the Temporal CLI if not cached.
 * First run may take longer due to download.
 */
class TemporalDevServerTest {
    @Test
    fun `can start and stop dev server`() {
        TemporalRuntime.create().use { runtime ->
            TemporalDevServer.start(runtime, timeoutSeconds = 120).use { server ->
                assertFalse(server.isClosed())
                assertNotNull(server.targetUrl)
                assertTrue(server.targetUrl.isNotEmpty())
                println("Dev server running at: ${server.targetUrl}")
            }
        }
    }

    @Test
    fun `dev server target  url contains host and port`() {
        TemporalRuntime.create().use { runtime ->
            TemporalDevServer.start(runtime, timeoutSeconds = 120).use { server ->
                // Target URL should be in format like "127.0.0.1:7233" or "localhost:7233"
                assertTrue(
                    server.targetUrl.contains(":"),
                    "Target URL should contain port: ${server.targetUrl}",
                )
            }
        }
    }

    @Test
    fun `server is closed after use block`() {
        val server: TemporalDevServer
        TemporalRuntime.create().use { runtime ->
            server = TemporalDevServer.start(runtime, timeoutSeconds = 120)
            server.use {
                assertFalse(it.isClosed())
            }
        }
        assertTrue(server.isClosed())
    }
}
