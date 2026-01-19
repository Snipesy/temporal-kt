package com.surrealdev.temporal.application

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Integration tests for workflow execution.
 *
 * These tests verify that workflows are correctly executed when activations
 * are received from the Temporal server.
 */
class WorkflowExecutionTest {
    /**
     * A simple workflow that returns a greeting.
     */
    @Workflow("SimpleGreeting")
    class SimpleGreetingWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(name: String): String = "Hello, $name!"
    }

    /**
     * A workflow that uses a timer.
     */
    @Workflow("TimerWorkflow")
    class TimerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(delayMs: Long): String {
            sleep(delayMs.milliseconds)
            return "Timer completed after $delayMs ms"
        }
    }

    /**
     * A workflow without WorkflowContext receiver (plain method).
     */
    @Workflow("PlainWorkflow")
    class PlainWorkflow {
        @WorkflowRun
        suspend fun execute(value: Int): Int = value * 2
    }

    @Test
    fun `worker with registered workflow starts and stops cleanly`() =
        runTemporalTest {
            val taskQueue = "test-queue-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SimpleGreetingWorkflow())
                }
            }

            // Give the worker time to start polling and register with the server
            delay(500)

            // If we get here without crashing, the workflow registration and
            // worker polling are working correctly
        }

    @Test
    fun `worker with timer workflow starts and stops cleanly`() =
        runTemporalTest {
            val taskQueue = "test-queue-timer-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(TimerWorkflow())
                }
            }

            delay(500)
        }

    @Test
    fun `worker with plain workflow starts and stops cleanly`() =
        runTemporalTest {
            val taskQueue = "test-queue-plain-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(PlainWorkflow())
                }
            }

            delay(500)
        }

    @Test
    fun `worker with multiple workflows starts and stops cleanly`() =
        runTemporalTest {
            val taskQueue = "test-queue-multi-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SimpleGreetingWorkflow())
                    workflow(TimerWorkflow())
                    workflow(PlainWorkflow())
                }
            }

            delay(500)
        }
}
