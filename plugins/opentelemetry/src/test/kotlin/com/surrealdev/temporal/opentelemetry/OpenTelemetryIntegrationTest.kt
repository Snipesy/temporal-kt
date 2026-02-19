package com.surrealdev.temporal.opentelemetry

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.logger
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.logger
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the OpenTelemetry plugin.
 *
 * These tests run actual workflows and activities against a real Temporal test server
 * and verify that spans and metrics are recorded correctly.
 */
@Tag("integration")
class OpenTelemetryIntegrationTest {
    // ==================== Test Workflows & Activities ====================

    class GreetingActivity {
        @Activity("greet")
        suspend fun ActivityContext.greet(name: String): String {
            val log = logger()
            log.info("Greeting activity started for name: {}", name)
            delay(10) // Simulate some work
            log.info("Greeting activity completed")
            return "Hello, $name!"
        }
    }

    @Workflow("SimpleWorkflow")
    class SimpleWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(name: String): String {
            val log = logger()
            log.info("SimpleWorkflow started with name: {}", name)
            return "Result: $name"
        }
    }

    @Workflow("WorkflowWithActivity")
    class WorkflowWithActivity {
        @WorkflowRun
        suspend fun WorkflowContext.run(name: String): String {
            val log = logger()
            log.info("WorkflowWithActivity starting activity for: {}", name)
            val greeting =
                startActivity(
                    activityType = "greet",
                    arg = name,
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                        ),
                ).result<String>()
            log.info("WorkflowWithActivity got result: {}", greeting)
            return greeting
        }
    }

    // ==================== Tests ====================

    @Test
    fun `plugin records RunWorkflow span with correct name and kind`() =
        runTemporalTest(timeSkipping = true) {
            val spanExporter = InMemorySpanExporter.create()
            val metricReader = InMemoryMetricReader.create()
            val openTelemetry = createTestOpenTelemetry(spanExporter, metricReader)

            val taskQueue = "otel-workflow-test-${UUID.randomUUID()}"

            application {
                install(OpenTelemetryPlugin) {
                    this.openTelemetry = openTelemetry
                    tracerName = "test-tracer"
                }

                taskQueue(taskQueue) {
                    workflow<SimpleWorkflow>()
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String>(
                    workflowType = "SimpleWorkflow",
                    taskQueue = taskQueue,
                    arg = "World",
                )

            val result = handle.result<String>(timeout = 30.seconds)
            assertEquals("Result: World", result)

            // Verify RunWorkflow span
            val workflowSpans = waitForSpans(spanExporter, "RunWorkflow:SimpleWorkflow", timeout = 5.seconds)
            assertTrue(workflowSpans.isNotEmpty(), "Expected RunWorkflow:SimpleWorkflow span")

            val span = workflowSpans.first()
            assertEquals(SpanKind.SERVER, span.kind)
            assertNotNull(span.attributes.get(AttributeKey.stringKey("temporalWorkflowID")))
            assertNotNull(span.attributes.get(AttributeKey.stringKey("temporalRunID")))

            openTelemetry.close()
        }

    @Test
    fun `plugin records RunActivity span with correct name and kind`() =
        runTemporalTest(timeSkipping = false) {
            val spanExporter = InMemorySpanExporter.create()
            val metricReader = InMemoryMetricReader.create()
            val openTelemetry = createTestOpenTelemetry(spanExporter, metricReader)

            val taskQueue = "otel-activity-test-${UUID.randomUUID()}"

            application {
                install(OpenTelemetryPlugin) {
                    this.openTelemetry = openTelemetry
                    tracerName = "test-tracer"
                }

                taskQueue(taskQueue) {
                    workflow<WorkflowWithActivity>()
                    activity(GreetingActivity())
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String>(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                    arg = "Alice",
                )

            val result = handle.result<String>(timeout = 30.seconds)
            assertEquals("Hello, Alice!", result)

            // Verify RunActivity span
            val activitySpans = waitForSpans(spanExporter, "RunActivity:greet", timeout = 5.seconds)
            assertTrue(activitySpans.isNotEmpty(), "Expected RunActivity:greet span")

            val span = activitySpans.first()
            assertEquals(SpanKind.SERVER, span.kind)
            assertNotNull(span.attributes.get(AttributeKey.stringKey("temporalActivityID")))
            assertNotNull(span.attributes.get(AttributeKey.stringKey("temporalWorkflowID")))
            assertNotNull(span.attributes.get(AttributeKey.stringKey("temporalRunID")))

            openTelemetry.close()
        }

    @Test
    fun `plugin propagates trace context from workflow to activity`() =
        runTemporalTest(timeSkipping = false) {
            val spanExporter = InMemorySpanExporter.create()
            val metricReader = InMemoryMetricReader.create()
            val openTelemetry = createTestOpenTelemetry(spanExporter, metricReader)

            val taskQueue = "otel-propagation-test-${UUID.randomUUID()}"

            application {
                install(OpenTelemetryPlugin) {
                    this.openTelemetry = openTelemetry
                    tracerName = "test-tracer"
                }

                taskQueue(taskQueue) {
                    workflow<WorkflowWithActivity>()
                    activity(GreetingActivity())
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String>(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                    arg = "Bob",
                )

            handle.result<String>(timeout = 30.seconds)

            // Wait for all spans
            waitForSpans(spanExporter, "RunActivity:greet", timeout = 5.seconds)

            val allSpans = spanExporter.finishedSpanItems

            // Find the StartActivity span (outbound from workflow) and RunActivity span (inbound at activity)
            val startActivitySpan = allSpans.find { it.name == "StartActivity:greet" }
            val runActivitySpan = allSpans.find { it.name == "RunActivity:greet" }

            assertNotNull(startActivitySpan, "Expected StartActivity:greet span")
            assertNotNull(runActivitySpan, "Expected RunActivity:greet span")

            // The RunActivity span should be a child of StartActivity (via header propagation)
            assertEquals(
                startActivitySpan.spanContext.traceId,
                runActivitySpan.spanContext.traceId,
                "RunActivity should share the same trace ID as StartActivity",
            )
            assertEquals(
                startActivitySpan.spanContext.spanId,
                runActivitySpan.parentSpanId,
                "RunActivity's parent should be StartActivity",
            )

            openTelemetry.close()
        }

    @Test
    fun `plugin records metrics for workflow and activity tasks`() =
        runTemporalTest(timeSkipping = false) {
            val spanExporter = InMemorySpanExporter.create()
            val metricReader = InMemoryMetricReader.create()
            val openTelemetry = createTestOpenTelemetry(spanExporter, metricReader)

            val taskQueue = "otel-metrics-test-${UUID.randomUUID()}"

            application {
                install(OpenTelemetryPlugin) {
                    this.openTelemetry = openTelemetry
                    tracerName = "test-tracer"
                }

                taskQueue(taskQueue) {
                    workflow<WorkflowWithActivity>()
                    activity(GreetingActivity())
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String>(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                    arg = "Bob",
                )

            handle.result<String>(timeout = 30.seconds)

            val workflowTaskMetric = waitForMetric(metricReader, "temporal.workflow.task.total", timeout = 5.seconds)
            assertNotNull(workflowTaskMetric, "Expected workflow task counter metric")

            val activityTaskMetric = waitForMetric(metricReader, "temporal.activity.task.total", timeout = 5.seconds)
            assertNotNull(activityTaskMetric, "Expected activity task counter metric")

            val workflowDurationMetric =
                waitForMetric(metricReader, "temporal.workflow.task.duration", timeout = 5.seconds)
            assertNotNull(workflowDurationMetric, "Expected workflow task duration metric")

            val activityDurationMetric =
                waitForMetric(metricReader, "temporal.activity.task.duration", timeout = 5.seconds)
            assertNotNull(activityDurationMetric, "Expected activity task duration metric")

            openTelemetry.close()
        }

    @Test
    fun `plugin can be disabled for workflows only`() =
        runTemporalTest(timeSkipping = true) {
            val spanExporter = InMemorySpanExporter.create()
            val metricReader = InMemoryMetricReader.create()
            val openTelemetry = createTestOpenTelemetry(spanExporter, metricReader)

            val taskQueue = "otel-disable-workflow-test-${UUID.randomUUID()}"

            application {
                install(OpenTelemetryPlugin) {
                    this.openTelemetry = openTelemetry
                    tracerName = "test-tracer"
                    enableWorkflowSpans = false // Disabled
                    enableActivitySpans = true
                    enableMetrics = true
                }

                taskQueue(taskQueue) {
                    workflow<WorkflowWithActivity>()
                    activity(GreetingActivity())
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String>(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                    arg = "Charlie",
                )

            handle.result<String>(timeout = 30.seconds)

            // Wait for activity spans (which should be recorded)
            val activitySpans = waitForSpans(spanExporter, "RunActivity:greet", timeout = 5.seconds)

            // Should have activity spans but NOT workflow spans
            val workflowSpans = spanExporter.finishedSpanItems.filter { it.name.startsWith("RunWorkflow:") }

            assertTrue(workflowSpans.isEmpty(), "Expected NO workflow spans when disabled")
            assertTrue(activitySpans.isNotEmpty(), "Expected activity spans")

            openTelemetry.close()
        }

    @Test
    fun `plugin records worker started metric`() =
        runTemporalTest(timeSkipping = true) {
            val spanExporter = InMemorySpanExporter.create()
            val metricReader = InMemoryMetricReader.create()
            val openTelemetry = createTestOpenTelemetry(spanExporter, metricReader)

            val taskQueue = "otel-worker-metric-test-${UUID.randomUUID()}"

            application {
                install(OpenTelemetryPlugin) {
                    this.openTelemetry = openTelemetry
                    tracerName = "test-tracer"
                    enableMetrics = true
                }

                taskQueue(taskQueue) {
                    workflow<SimpleWorkflow>()
                }
            }

            val workerStartedMetric = waitForMetric(metricReader, "temporal.worker.started.total", timeout = 5.seconds)
            assertNotNull(workerStartedMetric, "Expected worker started counter metric")

            openTelemetry.close()
        }

    // ==================== Helper Methods ====================

    private suspend fun waitForSpans(
        exporter: InMemorySpanExporter,
        spanName: String,
        timeout: kotlin.time.Duration = 5.seconds,
    ): List<SpanData> {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val spans = exporter.finishedSpanItems.filter { it.name == spanName }
            if (spans.isNotEmpty()) {
                return spans
            }
            delay(50)
        }
        return exporter.finishedSpanItems.filter { it.name == spanName }
    }

    private suspend fun waitForMetric(
        reader: InMemoryMetricReader,
        metricName: String,
        timeout: kotlin.time.Duration = 5.seconds,
    ): io.opentelemetry.sdk.metrics.data.MetricData? {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            val metric = reader.collectAllMetrics().find { it.name == metricName }
            if (metric != null) {
                return metric
            }
            delay(50)
        }
        return reader.collectAllMetrics().find { it.name == metricName }
    }

    private fun createTestOpenTelemetry(
        spanExporter: InMemorySpanExporter,
        metricReader: MetricReader,
    ): OpenTelemetrySdk {
        val tracerProvider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build()

        val meterProvider =
            SdkMeterProvider
                .builder()
                .registerMetricReader(metricReader)
                .build()

        return OpenTelemetrySdk
            .builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build()
    }
}
