package com.surrealdev.temporal.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for TemporalCoreClient.
 *
 * These tests use the dev server for connectivity testing.
 */
class TemporalCoreClientTest {
    @Test
    fun `can connect and close client`() {
        TemporalRuntime.create().use { runtime ->
            TemporalDevServer.start(runtime, timeoutSeconds = 120).use { server ->
                runBlocking {
                    val client =
                        TemporalCoreClient.connect(
                            runtime = runtime,
                            targetUrl = "http://${server.targetUrl}",
                            namespace = "default",
                        )
                    client.use { client ->
                        assertFalse(client.isClosed())
                        assertEquals("http://${server.targetUrl}", client.targetUrl)
                        assertEquals("default", client.namespace)
                    }
                    assertTrue(client.isClosed())
                }
            }
        }
    }

    @Test
    fun `client is closed after close call`() {
        TemporalRuntime.create().use { runtime ->
            TemporalDevServer.start(runtime, timeoutSeconds = 120).use { server ->
                runBlocking {
                    val client =
                        TemporalCoreClient.connect(
                            runtime = runtime,
                            targetUrl = "http://${server.targetUrl}",
                            namespace = "default",
                        )
                    assertFalse(client.isClosed())
                    client.close()
                    assertTrue(client.isClosed())
                    // Double close should be safe
                    client.close()
                    assertTrue(client.isClosed())
                }
            }
        }
    }

    @Test
    fun `can connect with custom options`() {
        TemporalRuntime.create().use { runtime ->
            TemporalDevServer.start(runtime, timeoutSeconds = 120).use { server ->
                runBlocking {
                    val options =
                        ClientOptions(
                            clientName = "test-client",
                            clientVersion = "1.0.0",
                            identity = "test-identity",
                        )
                    val client =
                        TemporalCoreClient.connect(
                            runtime = runtime,
                            targetUrl = "http://${server.targetUrl}",
                            namespace = "test-namespace",
                            options = options,
                        )
                    client.use { client ->
                        assertNotNull(client)
                        assertEquals("test-namespace", client.namespace)
                    }
                }
            }
        }
    }
}
