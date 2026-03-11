package com.surrealdev.temporal.health

import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.application.plugin.plugin
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.dependencies.dependencies
import com.surrealdev.temporal.dependencies.resolve
import com.surrealdev.temporal.testing.runTemporalTest
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HealthCheckPluginIntegrationTest {
    @Test
    fun `health endpoints return correct responses`() =
        runTemporalTest {
            application {
                install(HealthCheckPlugin) {
                    port = 0
                    readyPath = "/custom/ready"
                    livePath = "/custom/live"
                    healthPath = "/custom/health"
                }
                taskQueue("queue-a") {}
                taskQueue("queue-b") {}
            }

            val port = application.plugin(HealthCheckPlugin).port
            awaitReady("http://localhost:$port/custom/ready")

            // readyz
            val (readyStatus, readyBody) = httpGet("http://localhost:$port/custom/ready")
            assertEquals(200, readyStatus)
            assertEquals("ready", readyBody)

            // livez
            val (liveStatus, liveBody) = httpGet("http://localhost:$port/custom/live")
            assertEquals(200, liveStatus)
            assertEquals("alive", liveBody)

            // healthz — JSON with both workers
            val (healthStatus, healthBody) = httpGet("http://localhost:$port/custom/health")
            assertEquals(200, healthStatus)

            val response = healthJson.decodeFromString<HealthResponse>(healthBody)
            assertEquals("HEALTHY", response.status)
            assertEquals(2, response.workers.size)

            val taskQueues = response.workers.map { it.taskQueue }.toSet()
            assertTrue("queue-a" in taskQueues)
            assertTrue("queue-b" in taskQueues)
            response.workers.forEach { w ->
                assertEquals("READY", w.status)
            }
        }

    @Test
    fun `healthz includes DI resource health checks`() =
        runTemporalTest {
            application {
                install(HealthCheckPlugin) { port = 0 }
                taskQueue("di-health-queue") {}

                val healthy = AtomicBoolean(true)

                dependencies {
                    activityOnly<AtomicBoolean> { healthy } healthCheck { it.get() }
                }

                // Force instantiation so the health check has something to check
                val ctx = dependencies.createActivityContext()
                ctx.resolve<AtomicBoolean>()
            }

            val port = application.plugin(HealthCheckPlugin).port
            awaitReady("http://localhost:$port/readyz")

            // Healthy resource
            val (status1, body1) = httpGet("http://localhost:$port/healthz")
            assertEquals(200, status1)
            val response1 = healthJson.decodeFromString<HealthResponse>(body1)
            assertEquals(1, response1.resources.size)
            assertTrue(response1.resources[0].healthy)
            assertEquals("AtomicBoolean", response1.resources[0].name)

            // Flip to unhealthy
            val ctx = application.dependencies.createActivityContext()
            ctx.resolve<AtomicBoolean>().set(false)

            val (status2, body2) = httpGet("http://localhost:$port/healthz")
            assertEquals(503, status2)
            val response2 = healthJson.decodeFromString<HealthResponse>(body2)
            assertEquals(1, response2.resources.size)
            assertTrue(!response2.resources[0].healthy)
        }

    private suspend fun awaitReady(
        url: String,
        timeout: Duration = 10.seconds,
    ) {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val (status, _) = httpGet(url)
            if (status == 200) return
            delay(100)
        }
        error("Timed out waiting for $url to return 200")
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            val body = connection.inputStream.bufferedReader().readText()
            connection.responseCode to body
        } catch (_: Exception) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
            connection.responseCode to errorBody
        } finally {
            connection.disconnect()
        }
    }
}
