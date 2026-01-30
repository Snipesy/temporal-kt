package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.query
import com.surrealdev.temporal.client.signal
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.client.update
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
 * Integration tests for workflows using signals, updates, and queries together.
 */
@Tag("integration")
class CombinedHandlersTest {
    /**
     * Workflow that uses signals, updates, and queries together.
     */
    @Workflow("CombinedHandlersWorkflow")
    class CombinedHandlersWorkflow {
        private val items = mutableListOf<String>()
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return items.joinToString(",")
        }

        @Signal("addItem")
        fun WorkflowContext.addItem(item: String) {
            items.add("signal:$item")
        }

        @Update("addItemWithConfirm")
        fun WorkflowContext.addItemWithConfirm(item: String): Int {
            items.add("update:$item")
            return items.size
        }

        @Query("getItems")
        fun WorkflowContext.getItems(): List<String> = items.toList()

        @Query("getCount")
        fun WorkflowContext.getCount(): Int = items.size

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `workflow handles signals, updates, and queries together`() =
        runTemporalTest {
            val taskQueue = "test-combined-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<CombinedHandlersWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "CombinedHandlersWorkflow",
                    taskQueue = taskQueue,
                )

            // Use signal
            handle.signal("addItem", "A")

            // Poll until signal is processed
            val count1: Int =
                pollUntil(condition = { it: Int -> it >= 1 }) {
                    val c: Int = handle.query("getCount")
                    c
                }
            assertEquals(1, count1)

            // Use update (updates are synchronous, so no polling needed)
            val count2: Int = handle.update("addItemWithConfirm", "B")
            assertEquals(2, count2)

            // Use signal again
            handle.signal("addItem", "C")

            // Poll until signal is processed
            val items: List<String> =
                pollUntil(condition = { it: List<String> -> it.size >= 3 }) {
                    val i: List<String> = handle.query("getItems")
                    i
                }
            assertEquals(3, items.size)
            assertTrue(items.contains("signal:A"))
            assertTrue(items.contains("update:B"))
            assertTrue(items.contains("signal:C"))

            // Complete
            handle.signal("complete")

            val result = handle.result(timeout = 30.seconds)
            assertEquals("signal:A,update:B,signal:C", result)

            handle.assertHistory {
                completed()
            }
        }
}
