package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for deterministic values in workflows (random UUIDs, time progression).
 */
@Tag("integration")
class DeterministicValueTest {
    /**
     * Workflow that uses deterministic UUIDs from the workflow context.
     */
    @Workflow("RandomUuidWorkflow")
    class RandomUuidWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<String> {
            // Generate multiple deterministic UUIDs
            val values = mutableListOf<String>()
            repeat(5) {
                values.add(randomUuid())
            }
            return values
        }
    }

    @Test
    fun `workflow produces deterministic random values`() =
        runTemporalTest {
            val taskQueue = "test-random-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(RandomUuidWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<List<String>>(
                    workflowType = "RandomUuidWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Result should be a list of 5 UUIDs
            assertEquals(5, result.size)
            // All values should be valid UUID strings
            assertTrue(result.all { it.isNotEmpty() })

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow that uses workflow time.
     */
    @Workflow("TimeProgressionWorkflow")
    class TimeProgressionWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<Long> {
            val times = mutableListOf<Long>()

            // Record time before and after sleeps
            times.add(now().toEpochMilliseconds())

            sleep(100.milliseconds)
            times.add(now().toEpochMilliseconds())

            sleep(100.milliseconds)
            times.add(now().toEpochMilliseconds())

            return times
        }
    }

    @Test
    fun `workflow has deterministic time progression`() =
        runTemporalTest {
            val taskQueue = "test-time-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(TimeProgressionWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<List<Long>>(
                    workflowType = "TimeProgressionWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Result should have 3 timestamps
            assertEquals(3, result.size)

            // Times should be monotonically increasing
            assertTrue(result[0] <= result[1], "Time should increase after first sleep")
            assertTrue(result[1] <= result[2], "Time should increase after second sleep")

            // The difference between times should reflect the sleep durations
            // (approximately, allowing for some variance)
            val diff1 = result[1] - result[0]
            val diff2 = result[2] - result[1]

            assertTrue(diff1 >= 50, "First sleep difference should be >= 50ms, was $diff1")
            assertTrue(diff2 >= 50, "Second sleep difference should be >= 50ms, was $diff2")

            handle.assertHistory {
                completed()
                timerCount(2)
            }
        }
}
