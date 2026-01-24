package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.signal
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polls a query until the expected condition is met or timeout.
 */
private suspend inline fun <T> pollUntil(
    timeout: Duration = 15.seconds,
    crossinline condition: (T) -> Boolean,
    crossinline query: suspend () -> T,
): T =
    // bypass 'runTest' context
    withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(timeout) {
            while (true) {
                val result = query()
                if (condition(result)) return@withTimeout result
                kotlinx.coroutines.yield()
            }
            @Suppress("UNREACHABLE_CODE")
            throw AssertionError("Unreachable")
        }
    }

/**
 * Integration tests for signal handlers in workflows.
 */
@Tag("integration")
class SignalHandlerTest {
    /**
     * Workflow that receives signals and accumulates values.
     */
    @Workflow("SignalAccumulatorWorkflow")
    class SignalAccumulatorWorkflow {
        private val values = mutableListOf<String>()
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return values.joinToString(",")
        }

        @Signal("addValue")
        fun WorkflowContext.addValue(value: String) {
            values.add(value)
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `workflow receives and processes signals correctly`() =
        runTemporalTest {
            val taskQueue = "test-signal-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SignalAccumulatorWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "SignalAccumulatorWorkflow",
                    taskQueue = taskQueue,
                )

            // Send multiple signals
            handle.signal("addValue", "first")
            handle.signal("addValue", "second")
            handle.signal("addValue", "third")
            handle.signal("complete")

            val result = handle.result(timeout = 30.seconds)

            assertEquals("first,second,third", result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow that demonstrates buffered signal replay.
     * The signal handler is registered after signals arrive.
     */
    @Workflow("BufferedSignalReplayWorkflow")
    class BufferedSignalReplayWorkflow {
        private val values = mutableListOf<String>()
        private var handlerRegistered = false
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Wait for trigger to register handler
            awaitCondition { handlerRegistered }

            // Register runtime handler - this should replay any buffered signals
            setSignalHandlerWithPayloads("dynamicValue") { payloads ->
                val value =
                    serializer.deserialize(
                        payloads[0],
                    ) as String
                values.add("dynamic:$value")
            }

            // Wait for completion signal
            awaitCondition { done }
            return values.joinToString(",")
        }

        @Signal("registerHandler")
        fun WorkflowContext.registerHandler() {
            handlerRegistered = true
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `buffered signals are replayed when handler is registered`() =
        runTemporalTest {
            val taskQueue = "test-buffered-signal-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(BufferedSignalReplayWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "BufferedSignalReplayWorkflow",
                    taskQueue = taskQueue,
                )

            // Send signals BEFORE handler is registered - they will be buffered
            handle.signal("dynamicValue", "buffered1")
            handle.signal("dynamicValue", "buffered2")

            // Advance time to ensure signals are processed in a separate workflow task
            skipTime(100.milliseconds)

            // Now register the handler - buffered signals should be replayed
            handle.signal("registerHandler")

            // Advance time to allow handler registration to complete
            skipTime(100.milliseconds)

            // Send more signals after handler is registered
            handle.signal("dynamicValue", "live1")

            // Advance time before completing
            skipTime(100.milliseconds)

            // Complete the workflow
            handle.signal("complete")

            val result = handle.result(timeout = 30.seconds)

            // All signals should be received: buffered ones replayed + live ones
            assertTrue(result.contains("dynamic:buffered1"), "Should contain buffered1")
            assertTrue(result.contains("dynamic:buffered2"), "Should contain buffered2")
            assertTrue(result.contains("dynamic:live1"), "Should contain live1")

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow with dynamic signal handler that catches all signals.
     */
    @Workflow("DynamicSignalWorkflow")
    class DynamicSignalWorkflow {
        private val signals = mutableListOf<String>()
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register dynamic handler to catch all signals
            setDynamicSignalHandlerWithPayloads { signalName, _ ->
                if (signalName == "complete") {
                    done = true
                } else {
                    signals.add(signalName)
                }
            }

            awaitCondition { done }
            return signals.sorted().joinToString(",")
        }
    }

    @Test
    fun `dynamic signal handler catches all signal types`() =
        runTemporalTest {
            val taskQueue = "test-dynamic-signal-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(DynamicSignalWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "DynamicSignalWorkflow",
                    taskQueue = taskQueue,
                )

            // Send various signal types
            handle.signal("signalA")
            handle.signal("signalB")
            handle.signal("signalC")
            handle.signal("complete")

            val result = handle.result(timeout = 30.seconds)

            assertEquals("signalA,signalB,signalC", result)

            handle.assertHistory {
                completed()
            }
        }
}
