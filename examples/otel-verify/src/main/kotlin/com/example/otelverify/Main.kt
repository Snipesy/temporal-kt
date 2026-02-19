package com.example.otelverify

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.client.update
import com.surrealdev.temporal.opentelemetry.OpenTelemetryPlugin
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.continueAsNew
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.signal
import com.surrealdev.temporal.workflow.startActivity
import com.surrealdev.temporal.workflow.startChildWorkflow
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("otel-verify")

fun main() {
    val sdk = buildOpenTelemetry()

    val embedded =
        embeddedTemporal(
            module = {
                install(OpenTelemetryPlugin) {
                    openTelemetry = sdk
                    tracerName = "temporal-otel-verify"
                }

                taskQueue("otel-verify") {
                    workflow<OrchestratorWorkflow>()
                    workflow<CounterChildWorkflow>()
                    activity(Activities())
                }
            },
        )

    embedded.start(wait = false)

    printBanner()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutting down...")
            runBlocking { embedded.stop() }
        },
    )

    // Simulate a REST server's tracer — this is what an HTTP framework (Ktor, Spring) would create
    val httpTracer = sdk.getTracer("http-server", "1.0.0")

    runBlocking {
        val client = embedded.application.client()
        var run = 1

        while (true) {
            try {
                log.info("=== Starting run {} ===", run)

                // Simulate an incoming HTTP request span — the root of the whole trace.
                // In a real app this would be created by Ktor/Spring OTel instrumentation.
                val httpSpan =
                    httpTracer
                        .spanBuilder("POST /api/orchestrate")
                        .setSpanKind(SpanKind.SERVER)
                        .setAttribute("http.method", "POST")
                        .setAttribute("http.route", "/api/orchestrate")
                        .setAttribute("http.target", "/api/orchestrate?name=run-$run")
                        .setAttribute("http.status_code", 200L)
                        .startSpan()

                // Use asContextElement() to propagate the HTTP span across
                // coroutine suspension points (delay, await, etc.)
                withContext(httpSpan.asContextElement()) {
                    try {
                        val handle =
                            client.startWorkflow<String>(
                                workflowType = "OrchestratorWorkflow",
                                taskQueue = "otel-verify",
                                arg = "run-$run",
                            )

                        // Send a signal to the workflow
                        delay(500)
                        handle.signal("addNote", "external-note-from-client")
                        log.info("Run {}: sent signal 'addNote'", run)

                        // Send an update and get the result back
                        delay(200)
                        val snapshot: String = handle.update("getSnapshot")
                        log.info("Run {}: update 'getSnapshot' returned: {}", run, snapshot)

                        // Signal to proceed past the wait
                        handle.signal("proceed")
                        log.info("Run {}: sent signal 'proceed'", run)

                        val result = handle.result<String>(timeout = 60.seconds)
                        log.info("Run {}: final result = {}", run, result)
                    } catch (e: Exception) {
                        httpSpan.recordException(e)
                        httpSpan.setStatus(StatusCode.ERROR, e.message ?: "Error")
                        log.error("Run {} failed: {}", run, e.message, e)
                    } finally {
                        httpSpan.end()
                    }
                }
            } catch (e: Exception) {
                log.error("Run {} unexpected error: {}", run, e.message, e)
            }

            run++
            delay(5.seconds)
        }
    }
}

// ==================== Orchestrator Workflow ====================
// Exercises: parallel activities, child workflow, signals, updates, awaitCondition

@Workflow("OrchestratorWorkflow")
class OrchestratorWorkflow {
    private val notes = mutableListOf<String>()
    private var shouldProceed = false

    @Signal("addNote")
    fun WorkflowContext.addNote(note: String) {
        log.info("Signal received: addNote({})", note)
        notes.add(note)
    }

    @Signal("proceed")
    fun WorkflowContext.proceed() {
        log.info("Signal received: proceed")
        shouldProceed = true
    }

    @Update("getSnapshot")
    fun WorkflowContext.getSnapshot(): String {
        val snapshot = "notes=${notes.size}, shouldProceed=$shouldProceed"
        log.info("Update handled: getSnapshot -> {}", snapshot)
        return snapshot
    }

    @WorkflowRun
    suspend fun WorkflowContext.run(name: String): String {
        log.info("OrchestratorWorkflow started for name={}", name)

        // 1. Parallel activities — fan-out / fan-in
        log.info("Starting parallel activities...")
        val greetDeferred =
            async {
                startActivity(
                    activityType = "greet",
                    arg = name,
                    options = ActivityOptions(startToCloseTimeout = 1.minutes),
                ).result<String>()
            }
        val reverseDeferred =
            async {
                startActivity(
                    activityType = "reverse",
                    arg = name,
                    options = ActivityOptions(startToCloseTimeout = 1.minutes),
                ).result<String>()
            }
        val uppercaseDeferred =
            async {
                startActivity(
                    activityType = "uppercase",
                    arg = name,
                    options = ActivityOptions(startToCloseTimeout = 1.minutes),
                ).result<String>()
            }

        val greeting = greetDeferred.await()
        val reversed = reverseDeferred.await()
        val uppercased = uppercaseDeferred.await()
        log.info("Parallel activities done: greeting={}, reversed={}, uppercased={}", greeting, reversed, uppercased)

        // 2. Wait for signals/updates from the client
        log.info("Waiting for 'proceed' signal...")
        awaitCondition { shouldProceed }
        log.info("Proceed signal received, notes collected: {}", notes)

        // 3. Child workflow that continues-as-new
        log.info("Starting CounterChildWorkflow...")
        val childHandle =
            startChildWorkflow(
                workflowType = "CounterChildWorkflow",
                arg = 0,
                options = ChildWorkflowOptions(),
            )
        val childResult: Int = childHandle.result()
        log.info("Child workflow completed with count={}", childResult)

        // 4. One more sequential activity
        val summary =
            startActivity(
                activityType = "summarize",
                arg = "$greeting | $reversed | $uppercased | notes=${notes.size} | childCount=$childResult",
                options = ActivityOptions(startToCloseTimeout = 1.minutes),
            ).result<String>()

        log.info("OrchestratorWorkflow complete: {}", summary)
        return summary
    }

    companion object {
        private val log = LoggerFactory.getLogger("otel-verify.orchestrator")
    }
}

// ==================== Child Workflow (continues-as-new) ====================

@Workflow("CounterChildWorkflow")
class CounterChildWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(count: Int): Int {
        log.info("CounterChildWorkflow iteration count={}", count)
        sleep(1.seconds) // small delay per iteration
        if (count < 3) {
            continueAsNew(count + 1)
        }
        log.info("CounterChildWorkflow done at count={}", count)
        return count
    }

    companion object {
        private val log = LoggerFactory.getLogger("otel-verify.counter-child")
    }
}

// ==================== Activities ====================

class Activities {
    @Activity("greet")
    suspend fun ActivityContext.greet(name: String): String {
        log.info("greet activity: name={}", name)
        delay(100)
        return "Hello, $name!"
    }

    @Activity("reverse")
    suspend fun ActivityContext.reverse(name: String): String {
        log.info("reverse activity: name={}", name)
        delay(80)
        return name.reversed()
    }

    @Activity("uppercase")
    suspend fun ActivityContext.uppercase(name: String): String {
        log.info("uppercase activity: name={}", name)
        delay(60)
        return name.uppercase()
    }

    @Activity("summarize")
    suspend fun ActivityContext.summarize(data: String): String {
        log.info("summarize activity: data={}", data)
        delay(50)
        return "Summary[$data]"
    }

    companion object {
        private val log = LoggerFactory.getLogger("otel-verify.activities")
    }
}

// ==================== OpenTelemetry SDK ====================

fun buildOpenTelemetry(): OpenTelemetrySdk {
    val resource =
        Resource.getDefault().merge(
            Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "temporal-otel-verify")),
        )

    val spanExporter =
        OtlpHttpSpanExporter
            .builder()
            .setEndpoint("http://localhost:4318/v1/traces")
            .build()

    val metricExporter =
        OtlpHttpMetricExporter
            .builder()
            .setEndpoint("http://localhost:4318/v1/metrics")
            .build()

    val logExporter =
        OtlpHttpLogRecordExporter
            .builder()
            .setEndpoint("http://localhost:4318/v1/logs")
            .build()

    return OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider
                .builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .build(),
        ).setMeterProvider(
            SdkMeterProvider
                .builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.create(metricExporter))
                .build(),
        ).setLoggerProvider(
            SdkLoggerProvider
                .builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
                .build(),
        ).build()
}

// ==================== Banner ====================

fun printBanner() {
    println(
        """
        ╔══════════════════════════════════════════════════════════════╗
        ║       temporal-kt OpenTelemetry Stress Verification          ║
        ╠══════════════════════════════════════════════════════════════╣
        ║  Grafana:      http://localhost:3000  (admin / admin)        ║
        ║  Temporal UI:  http://localhost:8233                         ║
        ║                                                              ║
        ║  Sending OTLP traces + metrics + logs to localhost:4318      ║
        ║  Running OrchestratorWorkflow every 5 seconds...             ║
        ║                                                              ║
        ║  Each run exercises:                                         ║
        ║   • 3 parallel activities (greet, reverse, uppercase)        ║
        ║   • Signal: addNote, proceed                                 ║
        ║   • Update: getSnapshot (returns current state)              ║
        ║   • Child workflow with continue-as-new (3 iterations)       ║
        ║   • Sequential summarize activity                            ║
        ║                                                              ║
        ║  Expected span tree per run:                                 ║
        ║   POST /api/orchestrate (SERVER, http-server)                ║
        ║    ├─ StartWorkflow:OrchestratorWorkflow (CLIENT)            ║
        ║    │   └─ RunWorkflow:OrchestratorWorkflow (SERVER)          ║
        ║    │       ├─ StartActivity:greet (CLIENT)                   ║
        ║    │       │   └─ RunActivity:greet (SERVER)                 ║
        ║    │       ├─ StartActivity:reverse (CLIENT)                 ║
        ║    │       │   └─ RunActivity:reverse (SERVER)               ║
        ║    │       ├─ StartActivity:uppercase (CLIENT)               ║
        ║    │       │   └─ RunActivity:uppercase (SERVER)             ║
        ║    │       ├─ HandleSignal:addNote (SERVER)                  ║
        ║    │       ├─ HandleUpdate:getSnapshot (SERVER)              ║
        ║    │       ├─ HandleSignal:proceed (SERVER)                  ║
        ║    │       ├─ StartChildWorkflow:CounterChildWorkflow        ║
        ║    │       │   └─ RunWorkflow:CounterChildWorkflow           ║
        ║    │       │       └─ (continue-as-new x3)                   ║
        ║    │       ├─ StartActivity:summarize (CLIENT)               ║
        ║    │       │   └─ RunActivity:summarize (SERVER)             ║
        ║    ├─ SignalWorkflow:addNote (CLIENT)                        ║
        ║    ├─ UpdateWorkflow:getSnapshot (CLIENT)                    ║
        ║    └─ SignalWorkflow:proceed (CLIENT)                        ║
        ║                                                              ║
        ║  In Grafana:                                                 ║
        ║   • Explore → Tempo  → search "temporal-otel-verify"         ║
        ║   • Explore → Mimir  → metric temporal_workflow_task_total   ║
        ║   • Explore → Loki   → {service_name="temporal-otel-verify"} ║
        ║  Press Ctrl+C to stop                                        ║
        ╚══════════════════════════════════════════════════════════════╝
        """.trimIndent(),
    )
}
