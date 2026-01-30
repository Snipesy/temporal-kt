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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for workflows with mixed job types (timers and conditions).
 */
@Tag("integration")
class MixedJobTypesTest {
    /**
     * Workflow that has mixed operations (timers and conditions).
     */
    @Workflow("MixedJobTypesWorkflow")
    class MixedJobTypesWorkflow {
        private var state = "init"

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val steps = mutableListOf<String>()

            // Timer 1
            sleep(20.milliseconds)
            state = "after-timer-1"
            steps.add(state)

            // Condition check
            awaitCondition { state == "after-timer-1" }
            steps.add("condition-1-satisfied")

            // Timer 2
            sleep(20.milliseconds)
            state = "after-timer-2"
            steps.add(state)

            // Another condition
            awaitCondition { state == "after-timer-2" }
            steps.add("condition-2-satisfied")

            return steps.joinToString("->")
        }
    }

    @Test
    fun `workflow with mixed job types processes correctly`() =
        runTemporalTest {
            val taskQueue = "test-mixed-jobs-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<MixedJobTypesWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "MixedJobTypesWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("after-timer-1->condition-1-satisfied->after-timer-2->condition-2-satisfied", result)

            handle.assertHistory {
                completed()
                timerCount(2)
            }
        }
}
