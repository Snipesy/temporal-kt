package com.surrealdev.temporal.application

import com.surrealdev.temporal.application.plugin.createScopedPlugin
import com.surrealdev.temporal.application.plugin.hooks.SlotSupplierMetricsContext
import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.core.SlotSupplier
import com.surrealdev.temporal.testing.runTemporalTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
class SlotSupplierMetricsIntegrationTest {
    @Test
    fun `JVM slot samples reach application and matching task queue plugins`() =
        runTemporalTest(timeSkipping = false) {
            val metricsPlugin =
                createScopedPlugin<ConcurrentLinkedQueue<SlotSupplierMetricsContext>, Unit>(
                    name = "SlotMetrics",
                ) {
                    val samples = ConcurrentLinkedQueue<SlotSupplierMetricsContext>()
                    application {
                        onSlotSupplierMetrics { samples.add(it) }
                    }
                    samples
                }
            val queues = listOf("slot-metrics-first-${UUID.randomUUID()}", "slot-metrics-second-${UUID.randomUUID()}")
            val samples = mutableMapOf<String, ConcurrentLinkedQueue<SlotSupplierMetricsContext>>()
            application {
                samples["application"] = install(metricsPlugin)
                queues.forEach { queue ->
                    taskQueue(queue) {
                        activitySlotSupplier = SlotSupplier.JvmResourceBased(maximumSlots = 2)
                        samples[queue] = install(metricsPlugin)
                    }
                }
            }

            withTimeoutOrNull(5.seconds) {
                while (samples.values.any { it.isEmpty() }) delay(10)
            }
            assertEquals(
                queues.toSet(),
                samples.getValue("application").map { it.taskQueue }.toSet(),
                "Application plugin must receive samples from both workers",
            )
            queues.forEach { queue ->
                val queueSamples = samples.getValue(queue)
                assertEquals(setOf(queue), queueSamples.map { it.taskQueue }.toSet(), "Metrics for $queue")
                assertEquals(setOf("activity"), queueSamples.map { it.slotType }.toSet())
            }
        }
}
