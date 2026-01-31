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
import com.surrealdev.temporal.workflow.startActivity
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
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
                startActivity<String, String>(
                    activityType = "greet",
                    arg = name,
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                        ),
                ).result()
            log.info("WorkflowWithActivity got result: {}", greeting)
            return greeting
        }
    }

    // ==================== Tests ====================

    @Test
    fun `plugin records spans for workflow task`() =
        runTemporalTest(timeSkipping = true) {
            val spanExporter = InMemorySpanExporter.create()
            val metricReader = InMemoryMetricReader.create()
            val openTelemetry = createTestOpenTelemetry(spanExporter, metricReader)

            val taskQueue = "otel-workflow-test-${UUID.randomUUID()}"

            application {
                install(OpenTelemetryPlugin) {
                    this.openTelemetry = openTelemetry
                    tracerName = "test-tracer"
                    enableWorkflowSpans = true
                    enableActivitySpans = true
                    enableMetrics = true
                    enableMdcIntegration = true
                }

                taskQueue(taskQueue) {
                    workflow<SimpleWorkflow>()
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String, String>(
                    workflowType = "SimpleWorkflow",
                    taskQueue = taskQueue,
                    arg = "World",
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Result: World", result)

            // Wait for workflow task spans to be exported (with polling)
            val workflowSpans = waitForSpans(spanExporter, "temporal.workflow.task", timeout = 5.seconds)
            assertTrue(workflowSpans.isNotEmpty(), "Expected workflow task spans")

            val workflowSpan = workflowSpans.first()
            assertEquals(SpanKind.INTERNAL, workflowSpan.kind)
            assertEquals(
                "SimpleWorkflow",
                workflowSpan.attributes.get(AttributeKey.stringKey("temporal.workflow.type")),
            )
            assertEquals(taskQueue, workflowSpan.attributes.get(AttributeKey.stringKey("temporal.task_queue")))
            assertNotNull(workflowSpan.attributes.get(AttributeKey.stringKey("temporal.run.id")))

            openTelemetry.close()
        }

    @Test
    fun `plugin records spans for activity task`() =
        runTemporalTest(timeSkipping = false) {
            val spanExporter = InMemorySpanExporter.create()
            val metricReader = InMemoryMetricReader.create()
            val openTelemetry = createTestOpenTelemetry(spanExporter, metricReader)

            val taskQueue = "otel-activity-test-${UUID.randomUUID()}"

            application {
                install(OpenTelemetryPlugin) {
                    this.openTelemetry = openTelemetry
                    tracerName = "test-tracer"
                    enableWorkflowSpans = true
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
                client.startWorkflow<String, String>(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                    arg = "Alice",
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Hello, Alice!", result)

            // Wait for activity spans to be exported (with polling)
            val activitySpans = waitForSpans(spanExporter, "temporal.activity.execute", timeout = 5.seconds)
            assertTrue(activitySpans.isNotEmpty(), "Expected activity task spans")

            val activitySpan = activitySpans.first()
            assertEquals(SpanKind.INTERNAL, activitySpan.kind)
            assertEquals("greet", activitySpan.attributes.get(AttributeKey.stringKey("temporal.activity.type")))
            assertEquals(taskQueue, activitySpan.attributes.get(AttributeKey.stringKey("temporal.task_queue")))
            assertNotNull(activitySpan.attributes.get(AttributeKey.stringKey("temporal.activity.id")))
            assertNotNull(activitySpan.attributes.get(AttributeKey.stringKey("temporal.workflow.id")))
            assertNotNull(activitySpan.attributes.get(AttributeKey.stringKey("temporal.run.id")))

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
                    enableWorkflowSpans = true
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
                client.startWorkflow<String, String>(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                    arg = "Bob",
                )

            handle.result(timeout = 30.seconds)

            // Give metrics time to be collected
            delay(100)

            // Collect and verify metrics
            val metrics = metricReader.collectAllMetrics()

            // Check for workflow task counter
            val workflowTaskMetric = metrics.find { it.name == "temporal.workflow.task.total" }
            assertNotNull(workflowTaskMetric, "Expected workflow task counter metric")

            // Check for activity task counter
            val activityTaskMetric = metrics.find { it.name == "temporal.activity.task.total" }
            assertNotNull(activityTaskMetric, "Expected activity task counter metric")

            // Check for workflow task duration histogram
            val workflowDurationMetric = metrics.find { it.name == "temporal.workflow.task.duration" }
            assertNotNull(workflowDurationMetric, "Expected workflow task duration metric")

            // Check for activity task duration histogram
            val activityDurationMetric = metrics.find { it.name == "temporal.activity.task.duration" }
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
                client.startWorkflow<String, String>(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                    arg = "Charlie",
                )

            handle.result(timeout = 30.seconds)

            // Wait for activity spans (which should be recorded)
            val activitySpans = waitForSpans(spanExporter, "temporal.activity.execute", timeout = 5.seconds)

            // Should have activity spans but NOT workflow spans
            val workflowSpans = spanExporter.finishedSpanItems.filter { it.name == "temporal.workflow.task" }

            assertTrue(workflowSpans.isEmpty(), "Expected NO workflow task spans when disabled")
            assertTrue(activitySpans.isNotEmpty(), "Expected activity task spans")

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

            // Give time for worker started hook to fire
            delay(200)

            val metrics = metricReader.collectAllMetrics()

            val workerStartedMetric = metrics.find { it.name == "temporal.worker.started.total" }
            assertNotNull(workerStartedMetric, "Expected worker started counter metric")

            openTelemetry.close()
        }

    // ==================== Helper Methods ====================

    /**
     * Waits for spans with the given name to appear in the exporter, with polling.
     * This avoids flaky tests due to timing issues with span export.
     */
    private suspend fun waitForSpans(
        exporter: InMemorySpanExporter,
        spanName: String,
        timeout: kotlin.time.Duration = 5.seconds,
    ): List<io.opentelemetry.sdk.trace.data.SpanData> {
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
