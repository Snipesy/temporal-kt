package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.signal
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for external workflow signaling and cancellation.
 *
 * These tests verify that workflows can signal and cancel other workflows
 * that are not their children using ExternalWorkflowHandle.
 */
@Tag("integration")
class ExternalWorkflowIntegrationTest {
    // ================================================================
    // Test Workflow Classes
    // ================================================================

    /**
     * A workflow that waits for a signal before completing.
     * Used as the target for external workflow signal tests.
     */
    @Workflow("SignalWaitingWorkflow")
    class SignalWaitingWorkflow {
        private var receivedValue: String? = null

        @Signal("complete")
        fun complete(value: String) {
            receivedValue = value
        }

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { receivedValue != null }
            return "Received: $receivedValue"
        }
    }

    /**
     * A workflow that signals an external workflow by ID.
     * The target workflow ID is passed as an argument.
     */
    @Workflow("ExternalSignalerWorkflow")
    class ExternalSignalerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(targetWorkflowId: String): String {
            val externalHandle = getExternalWorkflowHandle(targetWorkflowId)
            externalHandle.signal("complete", "hello from external signaler")
            return "Signaled workflow: $targetWorkflowId"
        }
    }

    /**
     * A workflow that waits for multiple signals before completing.
     */
    @Workflow("MultiSignalWorkflow")
    class MultiSignalWorkflow {
        private val receivedSignals = mutableListOf<String>()

        @Signal("addValue")
        fun addValue(value: String) {
            receivedSignals.add(value)
        }

        @WorkflowRun
        suspend fun WorkflowContext.run(expectedCount: Int): String {
            awaitCondition { receivedSignals.size >= expectedCount }
            return "Received ${receivedSignals.size} signals: ${receivedSignals.joinToString(", ")}"
        }
    }

    /**
     * A workflow that sends multiple signals to an external workflow.
     */
    @Workflow("MultiExternalSignalerWorkflow")
    class MultiExternalSignalerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(
            targetWorkflowId: String,
            values: List<String>,
        ): String {
            val externalHandle = getExternalWorkflowHandle(targetWorkflowId)
            for (value in values) {
                externalHandle.signal("addValue", value)
            }
            return "Sent ${values.size} signals to $targetWorkflowId"
        }
    }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun `workflow can signal external workflow using ExternalWorkflowHandle`() =
        runTemporalTest {
            val taskQueue = "test-external-signal-${UUID.randomUUID()}"
            val targetWorkflowId = "target-workflow-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<SignalWaitingWorkflow>()
                    workflow<ExternalSignalerWorkflow>()
                }
            }

            val client = client()

            // Start the workflow that waits for a signal
            val waitingHandle =
                client.startWorkflow(
                    workflowType = "SignalWaitingWorkflow",
                    taskQueue = taskQueue,
                    workflowId = targetWorkflowId,
                )

            // Start the workflow that will signal the waiting workflow
            val signalerHandle =
                client.startWorkflow<String>(
                    workflowType = "ExternalSignalerWorkflow",
                    taskQueue = taskQueue,
                    arg = targetWorkflowId,
                )

            // Both workflows should complete
            val signalerResult: String = signalerHandle.result(timeout = 30.seconds)
            val waitingResult: String = waitingHandle.result(timeout = 30.seconds)

            assertEquals("Signaled workflow: $targetWorkflowId", signalerResult)
            assertEquals("Received: hello from external signaler", waitingResult)

            waitingHandle.assertHistory {
                completed()
            }
            signalerHandle.assertHistory {
                completed()
            }
        }

    @Test
    fun `workflow can send multiple signals to external workflow`() =
        runTemporalTest {
            val taskQueue = "test-multi-external-signal-${UUID.randomUUID()}"
            val targetWorkflowId = "multi-target-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<MultiSignalWorkflow>()
                    workflow<MultiExternalSignalerWorkflow>()
                }
            }

            val client = client()

            // Start the workflow that waits for multiple signals
            val waitingHandle =
                client.startWorkflow<Int>(
                    workflowType = "MultiSignalWorkflow",
                    taskQueue = taskQueue,
                    workflowId = targetWorkflowId,
                    arg = 3, // Expect 3 signals
                )

            // Start the workflow that will send multiple signals
            val signalerHandle =
                client.startWorkflow<String, List<String>>(
                    workflowType = "MultiExternalSignalerWorkflow",
                    taskQueue = taskQueue,
                    arg1 = targetWorkflowId,
                    arg2 = listOf("first", "second", "third"),
                )

            // Both workflows should complete
            val signalerResult: String = signalerHandle.result(timeout = 30.seconds)
            val waitingResult: String = waitingHandle.result(timeout = 30.seconds)

            assertEquals("Sent 3 signals to $targetWorkflowId", signalerResult)
            assertTrue(waitingResult.contains("first"))
            assertTrue(waitingResult.contains("second"))
            assertTrue(waitingResult.contains("third"))

            waitingHandle.assertHistory {
                completed()
            }
        }

    @Test
    fun `two workflows can signal each other`() =
        runTemporalTest {
            val taskQueue = "test-bidirectional-signal-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<BidirectionalWorkflowA>()
                    workflow<BidirectionalWorkflowB>()
                }
            }

            val client = client()

            // Use coroutineScope to launch both workflows concurrently
            coroutineScope {
                val workflowAId = "workflow-a-${UUID.randomUUID()}"
                val workflowBId = "workflow-b-${UUID.randomUUID()}"

                // Start workflow A which will signal B
                val handleA =
                    async {
                        client.startWorkflow<String>(
                            workflowType = "BidirectionalWorkflowA",
                            taskQueue = taskQueue,
                            workflowId = workflowAId,
                            arg = workflowBId,
                        )
                    }

                // Start workflow B which will signal A
                val handleB =
                    async {
                        client.startWorkflow<String>(
                            workflowType = "BidirectionalWorkflowB",
                            taskQueue = taskQueue,
                            workflowId = workflowBId,
                            arg = workflowAId,
                        )
                    }

                val workflowAHandle = handleA.await()
                val workflowBHandle = handleB.await()

                // Both workflows should complete
                val resultA: String = workflowAHandle.result(timeout = 30.seconds)
                val resultB: String = workflowBHandle.result(timeout = 30.seconds)

                assertTrue(resultA.contains("from B"))
                assertTrue(resultB.contains("from A"))
            }
        }

    // ================================================================
    // Additional Test Workflows for Bidirectional Test
    // ================================================================

    /**
     * Workflow A: Signals workflow B, then waits for a signal from B.
     */
    @Workflow("BidirectionalWorkflowA")
    class BidirectionalWorkflowA {
        private var receivedFromB: String? = null

        @Signal("fromB")
        fun fromB(value: String) {
            receivedFromB = value
        }

        @WorkflowRun
        suspend fun WorkflowContext.run(workflowBId: String): String {
            // Signal workflow B
            val externalHandle = getExternalWorkflowHandle(workflowBId)
            externalHandle.signal("fromA", "hello from A")

            // Wait for signal from B
            awaitCondition { receivedFromB != null }
            return "A received: $receivedFromB"
        }
    }

    /**
     * Workflow B: Waits for a signal from A, then signals A back.
     */
    @Workflow("BidirectionalWorkflowB")
    class BidirectionalWorkflowB {
        private var receivedFromA: String? = null

        @Signal("fromA")
        fun fromA(value: String) {
            receivedFromA = value
        }

        @WorkflowRun
        suspend fun WorkflowContext.run(workflowAId: String): String {
            // Wait for signal from A first
            awaitCondition { receivedFromA != null }

            // Signal workflow A back
            val externalHandle = getExternalWorkflowHandle(workflowAId)
            externalHandle.signal("fromB", "hello from B")

            return "B received: $receivedFromA"
        }
    }
}
