package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for workflow cancellation and cleanup.
 */
@Tag("integration")
class CancellationTest {
    /**
     * Workflow that can be cancelled during a long sleep.
     */
    @Workflow("CancellableConditionWorkflow")
    class CancellableConditionWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Long sleep that can be cancelled
            sleep(10.seconds)
            return "completed"
        }
    }

    @Test
    fun `workflow can be cancelled and cleanup occurs`() =
        runTemporalTest {
            val taskQueue = "test-cancel-cleanup-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<CancellableConditionWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CancellableConditionWorkflow",
                    taskQueue = taskQueue,
                )

            // Cancel the workflow
            handle.cancel()

            // Wait for cancellation to be processed
            try {
                handle.result(timeout = 10.seconds)
            } catch (e: Exception) {
                // Expected - workflow was cancelled
            }

            handle.assertHistory {
                canceled()
                check("Workflow should not be completed") { !it.isCompleted }
            }
        }
}
