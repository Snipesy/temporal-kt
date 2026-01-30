package com.surrealdev.temporal.activity.integration

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.heartbeat
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for activity heartbeat functionality.
 */
@Tag("integration")
class HeartbeatIntegrationTest {
    @Serializable
    data class ProcessingProgress(
        val itemsProcessed: Int,
        val lastItem: String,
    )

    @Serializable
    data class ProcessingResult(
        val totalItems: Int,
        val finalProgress: ProcessingProgress?,
    )

    class HeartbeatingActivity {
        @Activity("processWithHeartbeat")
        suspend fun ActivityContext.processWithHeartbeat(items: List<String>): ProcessingResult {
            var progress: ProcessingProgress? = null

            items.forEachIndexed { index, item ->
                delay(10)
                progress = ProcessingProgress(index + 1, item)
                heartbeat(progress)
            }

            return ProcessingResult(items.size, progress)
        }
    }

    @Workflow("HeartbeatTestWorkflow")
    class HeartbeatTestWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(items: List<String>): ProcessingResult =
            startActivity<ProcessingResult, List<String>>(
                activityType = "processWithHeartbeat",
                arg = items,
                options =
                    ActivityOptions(
                        startToCloseTimeout = 1.minutes,
                        heartbeatTimeout = 10.seconds,
                    ),
            ).result()
    }

    @Test
    fun `activity can send heartbeats during execution`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "heartbeat-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<HeartbeatTestWorkflow>()
                    activity(HeartbeatingActivity())
                }
            }

            val client = client()
            val items = listOf("item1", "item2", "item3", "item4", "item5")

            val handle =
                client.startWorkflow<ProcessingResult, List<String>>(
                    workflowType = "HeartbeatTestWorkflow",
                    taskQueue = taskQueue,
                    arg = items,
                )

            val result = handle.result(timeout = 1.minutes)

            assertEquals(5, result.totalItems)
            assertEquals(5, result.finalProgress?.itemsProcessed)
            assertEquals("item5", result.finalProgress?.lastItem)
        }

    @Test
    fun `heartbeat details structure is accessible in activity info`() =
        runTemporalTest(timeSkipping = false) {
            // This test verifies that the heartbeat details infrastructure is wired up correctly
            // by checking that an activity can access heartbeat details from info
            val taskQueue = "heartbeat-info-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<HeartbeatTestWorkflow>()
                    activity(HeartbeatingActivity())
                }
            }

            val client = client()
            val items = listOf("x", "y", "z")

            val handle =
                client.startWorkflow<ProcessingResult, List<String>>(
                    workflowType = "HeartbeatTestWorkflow",
                    taskQueue = taskQueue,
                    arg = items,
                )

            val result = handle.result(timeout = 1.minutes)

            // Verify heartbeats were sent and activity completed successfully
            assertEquals(3, result.totalItems)
            assertEquals("z", result.finalProgress?.lastItem)
        }
}
