package com.surrealdev.temporal.client

import com.surrealdev.temporal.core.TemporalCoreException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class TemporalClientConnectionTest {
    @Test
    fun `failed standalone connection releases its runtime`() =
        runBlocking {
            val existingPumps = pumpThreads()
            assertFailsWith<TemporalCoreException> {
                TemporalClient.connect { target = "http://[invalid" }
            }
            assertEquals(emptySet(), pumpThreads() - existingPumps)
        }

    @Test
    fun `cancelled standalone connection releases its runtime`() =
        runBlocking {
            val existingPumps = pumpThreads()
            // Accept TCP connections without replying to the gRPC handshake.
            ServerSocket(0).use { server ->
                val connecting =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        TemporalClient.connect { target = "127.0.0.1:${server.localPort}" }.close()
                    }
                try {
                    assertTrue((pumpThreads() - existingPumps).isNotEmpty())
                } finally {
                    connecting.cancelAndJoin()
                }
            }
            assertEquals(emptySet(), pumpThreads() - existingPumps)
        }

    private fun pumpThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filterTo(mutableSetOf()) { it.name.startsWith("temporal-pump-") }
}
