package com.surrealdev.temporal.opentelemetry

import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetup
import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.testing.runTemporalTest
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
class OpenTelemetryShutdownTest {
    @Test
    fun `application close exports final buffered Core metrics`() =
        runTemporalTest(timeSkipping = true) {
            val exporter = InMemoryMetricExporter.create()
            var finalMetrics: List<MetricData>? = null
            val capturingExporter =
                object : MetricExporter by exporter {
                    override fun shutdown(): CompletableResultCode {
                        // The in-memory exporter clears its observations on shutdown.
                        finalMetrics = exporter.finishedMetricItems
                        return exporter.shutdown()
                    }
                }
            val reader =
                PeriodicMetricReader
                    .builder(capturingExporter)
                    .setInterval(Duration.ofDays(1))
                    .build()
            val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()

            OpenTelemetrySdk.builder().setMeterProvider(provider).build().use { openTelemetry ->
                lateinit var coreClient: TemporalCoreClient
                application {
                    install(OpenTelemetryPlugin) {
                        this.openTelemetry = openTelemetry
                    }
                    hookRegistry.register(ApplicationSetup) { context ->
                        coreClient = context.coreClient
                        // Quiesce the periodic drain so the RPC below stays buffered until runtime.close().
                        val sampler =
                            TemporalRuntime::class.java
                                .getDeclaredField("sampler")
                                .apply { isAccessible = true }
                                .get(context.runtime) as ScheduledExecutorService
                        sampler.shutdown()
                        assertTrue(sampler.awaitTermination(5, TimeUnit.SECONDS), "Runtime sampler did not stop")
                    }
                }

                openTelemetry
                    .getMeter("shutdown-test")
                    .counterBuilder("shutdown_sentinel")
                    .build()
                    .add(1)
                coreClient.workflowServiceCall(
                    "DescribeNamespace",
                    DescribeNamespaceRequest.newBuilder().setNamespace("default").build(),
                ) { DescribeNamespaceResponse.parseFrom(it) }
                assertTrue(exporter.finishedMetricItems.isEmpty(), "Only shutdown should trigger an export")

                application.close()

                val exported = assertNotNull(finalMetrics, "Application shutdown must close the OTel provider")
                assertEquals(
                    1L,
                    exported
                        .single { it.name == "shutdown_sentinel" }
                        .longSumData.points
                        .single()
                        .value,
                )
                assertTrue(
                    exported.any { metric ->
                        metric.name == "temporal_request" &&
                            metric.longSumData.points.any { point ->
                                point.attributes.get(AttributeKey.stringKey("operation")) == "DescribeNamespace" &&
                                    point.value == 1L
                            }
                    },
                    "The provider's final export must include the Core RPC observation buffered until runtime close",
                )
            }
        }
}
