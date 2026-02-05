package com.surrealdev.temporal.testing

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Integration tests for time-skipping functionality.
 *
 * These tests verify that the TemporalTestServer correctly skips time
 * when workflows are waiting on timers.
 */
class TimeSkippingTest {
    /**
     * A workflow that waits for a long timer (1 hour).
     */
    @Workflow("LongTimerWorkflow")
    class LongTimerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Wait for 1 hour - should complete instantly with time skipping
            sleep(1.hours)
            return "Timer completed!"
        }
    }

    @Test
    fun `time skipping server starts and stops cleanly`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "time-skip-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<LongTimerWorkflow>()
                }
            }

            // If we get here, the test server started successfully
        }

    @Test
    fun `can get current time from test server`() =
        runTemporalTest(timeSkipping = true) {
            application { }

            val time = getCurrentTime()
            println("Test server current time: $time")

            // Time should be reasonably recent (within the last hour)
            val now = java.time.Instant.now()
            assertTrue(time.isBefore(now.plusSeconds(3600)))
        }

    @Test
    fun `can lock and unlock time skipping`() =
        runTemporalTest(timeSkipping = true) {
            application { }

            // Time skipping is auto-unlocked by default
            // Lock it for manual control
            lockTimeSkipping()

            // Get current time
            val time1 = getCurrentTime()

            // Unlock to allow time to advance
            unlockTimeSkipping()

            // Time should still be queryable
            val time2 = getCurrentTime()

            println("Time 1: $time1, Time 2: $time2")
        }

    @Test
    fun `workflow with long timer completes quickly with time skipping`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "time-skip-workflow-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<LongTimerWorkflow>()
                }
            }

            val elapsed =
                measureTime {
                    // Start the workflow and wait for result
                    val client = client()
                    val handle =
                        client.startWorkflow(
                            workflowType = "LongTimerWorkflow",
                            taskQueue = taskQueue,
                        )

                    val result: String = handle.result(timeout = 30.seconds)
                    assertEquals("Timer completed!", result)
                    println("Workflow result: $result")
                }

            println("Elapsed time: $elapsed")

            // Should complete in under 30 seconds despite 1-hour timer
            assertTrue(elapsed < 30.seconds, "Workflow with 1-hour timer should complete quickly with time skipping")
        }
}
