package com.surrealdev.temporal.client

import com.surrealdev.temporal.client.internal.connectionTarget
import com.surrealdev.temporal.core.TlsConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionTargetTest {
    @Test
    fun `TLS settings take precedence over the target scheme`() {
        for (target in listOf("localhost:7233", "http://localhost:7233", "HTTPS://localhost:7233")) {
            assertEquals("https://localhost:7233", connectionTarget(target, null, "api-key", false))
            assertEquals("https://localhost:7233", connectionTarget(target, TlsConfig(), null, false))
            assertEquals("http://localhost:7233", connectionTarget(target, TlsConfig(), "api-key", true))
        }
        assertEquals("http://localhost:7233", connectionTarget("localhost:7233", null, null, false))
        assertEquals("https://localhost:7233", connectionTarget("https://localhost:7233", null, null, false))
    }
}
