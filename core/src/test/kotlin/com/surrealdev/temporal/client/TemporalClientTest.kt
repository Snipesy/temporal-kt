package com.surrealdev.temporal.client

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for TemporalClient workflow operations.
 */
class TemporalClientTest {
    @Workflow("GreetingWorkflow")
    class GreetingWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(name: String): String = "Hello, $name!"
    }

    @Workflow("TimerTestWorkflow")
    class TimerTestWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(delayMs: Long): String {
            sleep(delayMs.milliseconds)
            return "Timer completed"
        }
    }

    @Workflow("MultiArgWorkflow")
    class MultiArgWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(
            a: Int,
            b: Int,
        ): Int = a + b
    }

    @Workflow("NoArgWorkflow")
    class NoArgWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "No args needed"
    }

    @Test
    fun `can start workflow and get result`() =
        runTemporalTest {
            val taskQueue = "test-client-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<GreetingWorkflow>()
                }
            }

            val client = client()
            val handle: WorkflowHandle<String> =
                client.startWorkflow(
                    workflowType = "GreetingWorkflow",
                    taskQueue = taskQueue,
                    arg = "World",
                )

            assertNotNull(handle.workflowId)
            assertNotNull(handle.runId)

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Hello, World!", result)
        }

    @Test
    fun `can start workflow with custom workflow ID`() =
        runTemporalTest {
            val taskQueue = "test-client-custom-id-${UUID.randomUUID()}"
            val customWorkflowId = "my-custom-workflow-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<GreetingWorkflow>()
                }
            }

            val client = client()
            val handle: WorkflowHandle<String> =
                client.startWorkflow(
                    workflowType = "GreetingWorkflow",
                    taskQueue = taskQueue,
                    workflowId = customWorkflowId,
                    arg = "Test",
                )

            assertEquals(customWorkflowId, handle.workflowId)

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Hello, Test!", result)
        }

    @Test
    fun `can start workflow with timer and get result`() =
        runTemporalTest {
            val taskQueue = "test-client-timer-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<TimerTestWorkflow>()
                }
            }

            val client = client()
            val handle: WorkflowHandle<String> =
                client.startWorkflow(
                    workflowType = "TimerTestWorkflow",
                    taskQueue = taskQueue,
                    arg = 100L,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Timer completed", result)
        }

    @Test
    fun `can query workflow history after completion`() =
        runTemporalTest {
            val taskQueue = "test-client-history-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<GreetingWorkflow>()
                }
            }

            val client = client()
            val handle: WorkflowHandle<String> =
                client.startWorkflow(
                    workflowType = "GreetingWorkflow",
                    taskQueue = taskQueue,
                    arg = "History",
                )

            handle.result(timeout = 30.seconds)

            val history = handle.getHistory()
            assertTrue(history.isCompleted)
            assertEquals("GreetingWorkflow", history.workflowType)
            assertEquals(taskQueue, history.taskQueue)
            assertTrue(history.events.isNotEmpty())
        }

    @Test
    fun `can use assertHistory DSL`() =
        runTemporalTest {
            val taskQueue = "test-client-assert-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<TimerTestWorkflow>()
                }
            }

            val client = client()
            val handle: WorkflowHandle<String> =
                client.startWorkflow(
                    workflowType = "TimerTestWorkflow",
                    taskQueue = taskQueue,
                    arg = 50L,
                )

            handle.result(timeout = 30.seconds)

            handle.assertHistory {
                completed()
                hasTimerStarted()
                hasTimerFired()
                timerCount(1)
                noFailedActivities()
            }
        }

    @Test
    fun `can describe workflow execution`() =
        runTemporalTest {
            val taskQueue = "test-client-describe-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<GreetingWorkflow>()
                }
            }

            val client = client()
            val handle: WorkflowHandle<String> =
                client.startWorkflow(
                    workflowType = "GreetingWorkflow",
                    taskQueue = taskQueue,
                    arg = "Describe",
                )

            handle.result(timeout = 30.seconds)

            val description = handle.describe()
            assertEquals(handle.workflowId, description.workflowId)
            assertEquals(handle.runId, description.runId)
            assertEquals("GreetingWorkflow", description.workflowType)
            assertEquals(WorkflowExecutionStatus.COMPLETED, description.status)
        }

    @Test
    fun `can start workflow with multiple arguments`() =
        runTemporalTest {
            val taskQueue = "test-client-multiarg-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<MultiArgWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int, Int, Int>(
                    workflowType = "MultiArgWorkflow",
                    taskQueue = taskQueue,
                    arg1 = 5,
                    arg2 = 3,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals(8, result)
        }

    @Test
    fun `can start workflow with no arguments`() =
        runTemporalTest {
            val taskQueue = "test-client-noarg-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<NoArgWorkflow>()
                }
            }

            val client = client()
            val handle: WorkflowHandle<String> =
                client.startWorkflow(
                    workflowType = "NoArgWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("No args needed", result)
        }

    @Test
    fun `can get handle to existing workflow`() =
        runTemporalTest {
            val taskQueue = "test-client-gethandle-${UUID.randomUUID()}"
            val workflowId = "existing-workflow-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<GreetingWorkflow>()
                }
            }

            val client = client()

            // Start the workflow
            val originalHandle: WorkflowHandle<String> =
                client.startWorkflow(
                    workflowType = "GreetingWorkflow",
                    taskQueue = taskQueue,
                    workflowId = workflowId,
                    arg = "Existing",
                )

            // Get a new handle to the same workflow
            val retrievedHandle = client.getWorkflowHandle<String>(workflowId)

            assertEquals(workflowId, retrievedHandle.workflowId)

            // Both should return the same result
            val result = retrievedHandle.result(timeout = 30.seconds)
            assertEquals("Hello, Existing!", result)
        }
}
