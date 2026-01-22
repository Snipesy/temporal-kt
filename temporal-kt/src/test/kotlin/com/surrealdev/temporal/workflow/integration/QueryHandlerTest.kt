package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.query
import com.surrealdev.temporal.client.startWorkflow
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
 * Integration tests for query handlers in workflows.
 */
@Tag("integration")
class QueryHandlerTest {
    /**
     * Workflow with query handlers.
     */
    @Workflow("QueryableWorkflow")
    class QueryableWorkflow {
        private var counter = 0
        private val history = mutableListOf<String>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): Int {
            repeat(5) { i ->
                sleep(50.milliseconds)
                counter++
                history.add("step-$i")
            }
            return counter
        }

        @Query("getCounter")
        fun WorkflowContext.getCounter(): Int = counter

        @Query("getHistory")
        fun WorkflowContext.getHistory(): List<String> = history.toList()
    }

    @Test
    fun `workflow responds to queries during execution`() =
        runTemporalTest {
            val taskQueue = "test-query-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(QueryableWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int>(
                    workflowType = "QueryableWorkflow",
                    taskQueue = taskQueue,
                )

            // Query during execution - counter may be in progress (any non-negative value is valid)
            val midCounter: Int = handle.query("getCounter")
            assertTrue(midCounter >= 0, "Counter should be non-negative")

            // Wait for completion
            val finalResult = handle.result(timeout = 30.seconds)
            assertEquals(5, finalResult, "Final counter should be 5")

            // Query after completion
            val finalCounter: Int = handle.query("getCounter")
            assertEquals(5, finalCounter, "Final counter query should be 5 when querried after complete")

            val history: List<String> = handle.query("getHistory")
            assertEquals(5, history.size, "Workflow history should be 5 steps")
            assertEquals("step-0", history[0])
            assertEquals("step-4", history[4])

            handle.assertHistory {
                completed()
                timerCount(5)
            }
        }

    /**
     * Workflow with runtime-registered query handler.
     */
    @Workflow("RuntimeQueryWorkflow")
    class RuntimeQueryWorkflow {
        private var secret = "initial"

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register a runtime query handler
            setQueryHandler("getSecret") { _ ->
                serializer.serialize(
                    com.surrealdev.temporal.serialization
                        .typeInfoOf<String>(),
                    secret,
                )
            }

            sleep(100.milliseconds)
            secret = "updated"

            sleep(100.milliseconds)
            return secret
        }
    }

    @Test
    fun `runtime query handler works correctly`() =
        runTemporalTest {
            val taskQueue = "test-runtime-query-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(RuntimeQueryWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "RuntimeQueryWorkflow",
                    taskQueue = taskQueue,
                )

            // Poll until query handler is registered (returns a valid value)
            val secret1: String =
                pollUntil(condition = { it: String -> it == "initial" || it == "updated" }) {
                    try {
                        val s: String = handle.query("getSecret")
                        s
                    } catch (_: Exception) {
                        "" // Return empty string if query fails (handler not registered yet)
                    }
                }
            assertTrue(secret1 == "initial" || secret1 == "updated", "Secret should be initial or updated")

            // Wait for completion
            val result = handle.result(timeout = 30.seconds)
            assertEquals("updated", result)

            // Query after completion should return final value
            val finalSecret: String = handle.query("getSecret")
            assertEquals("updated", finalSecret)

            handle.assertHistory {
                completed()
                timerCount(2)
            }
        }
}
