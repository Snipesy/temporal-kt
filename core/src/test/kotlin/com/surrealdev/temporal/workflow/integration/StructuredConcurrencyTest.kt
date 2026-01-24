package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for structured concurrency with async operations in workflows.
 */
@Tag("integration")
class StructuredConcurrencyTest {
    /**
     * Workflow with multiple async operations.
     */
    @Workflow("AsyncOperationsWorkflow")
    class AsyncOperationsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Launch multiple async operations
            val deferred1 =
                async {
                    sleep(30.milliseconds)
                    "A"
                }
            val deferred2 =
                async {
                    sleep(40.milliseconds)
                    "B"
                }
            val deferred3 =
                async {
                    sleep(20.milliseconds)
                    "C"
                }

            // Await all in order
            val result1 = deferred1.await()
            val result2 = deferred2.await()
            val result3 = deferred3.await()

            return "$result1$result2$result3"
        }
    }

    @Test
    fun `async workflow operations execute deterministically`() =
        runTemporalTest {
            val taskQueue = "test-async-ops-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(AsyncOperationsWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "AsyncOperationsWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Results should always be in the await order, not completion order
            assertEquals("ABC", result)

            handle.assertHistory {
                completed()
                // All three async operations should create timers
                timerCount(3)
            }
        }

    /**
     * Workflow with nested async operations.
     */
    @Workflow("NestedAsyncWorkflow")
    class NestedAsyncWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val outer =
                async {
                    val inner1 =
                        async {
                            sleep(20.milliseconds)
                            "inner1"
                        }
                    val inner2 =
                        async {
                            sleep(20.milliseconds)
                            "inner2"
                        }
                    "${inner1.await()}-${inner2.await()}"
                }

            return "result: ${outer.await()}"
        }
    }

    @Test
    fun `nested async operations execute deterministically`() =
        runTemporalTest {
            val taskQueue = "test-nested-async-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(NestedAsyncWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "NestedAsyncWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("result: inner1-inner2", result)

            handle.assertHistory {
                completed()
                // Two inner async operations create timers
                timerCount(2)
            }
        }
}
