package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ChildWorkflowFailureException
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.startChildWorkflow
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for child workflow functionality.
 *
 * These tests run against a real Temporal server to verify end-to-end behavior.
 */
@Tag("integration")
class ChildWorkflowIntegrationTest {
    // ================================================================
    // Test Data Classes
    // ================================================================

    @Serializable
    data class ChildInput(
        val value: String,
    )

    @Serializable
    data class ChildOutput(
        val processedValue: String,
        val childWorkflowId: String,
    )

    // ================================================================
    // Test Workflow Classes
    // ================================================================

    /**
     * Simple child workflow that returns a greeting.
     */
    @Workflow("SimpleChildWorkflow")
    class SimpleChildWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "Hello from child (${info.workflowId})"
    }

    /**
     * Child workflow that processes input and returns structured output.
     */
    @Workflow("ChildWithArgsWorkflow")
    class ChildWithArgsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: ChildInput): ChildOutput =
            ChildOutput(
                processedValue = "Processed: ${input.value}",
                childWorkflowId = info.workflowId,
            )
    }

    /**
     * Child workflow that fails intentionally.
     */
    @Workflow("FailingChildWorkflow")
    class FailingChildWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = throw IllegalStateException("Child workflow intentional failure")
    }

    /**
     * Parent workflow that invokes a simple child workflow.
     */
    @Workflow("SimpleParentWorkflow")
    class SimpleParentWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val childResult = startChildWorkflow<String>("SimpleChildWorkflow", ChildWorkflowOptions()).result()
            return "Parent received: $childResult"
        }
    }

    /**
     * Parent workflow that passes arguments to child.
     */
    @Workflow("ParentWithArgsWorkflow")
    class ParentWithArgsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(parentInput: String): String {
            val childInput = ChildInput(value = parentInput)
            val result =
                startChildWorkflow<ChildOutput, ChildInput>(
                    "ChildWithArgsWorkflow",
                    childInput,
                    ChildWorkflowOptions(),
                ).result()
            return "Parent got: ${result.processedValue} from ${result.childWorkflowId}"
        }
    }

    /**
     * Parent workflow that invokes multiple children sequentially.
     */
    @Workflow("MultipleChildrenWorkflow")
    class MultipleChildrenWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val result1 = startChildWorkflow<String>("SimpleChildWorkflow", ChildWorkflowOptions()).result()
            val result2 = startChildWorkflow<String>("SimpleChildWorkflow", ChildWorkflowOptions()).result()
            return "Child1: $result1, Child2: $result2"
        }
    }

    /**
     * Parent workflow that handles child failure.
     */
    @Workflow("ChildFailureHandlerWorkflow")
    class ChildFailureHandlerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startChildWorkflow<String>("FailingChildWorkflow", ChildWorkflowOptions()).result()
                "Child succeeded unexpectedly"
            } catch (e: ChildWorkflowFailureException) {
                "Caught child failure: ${e.failure?.message}"
            }
    }

    /**
     * Parent workflow that sets custom child workflow ID.
     */
    @Workflow("CustomChildIdWorkflow")
    class CustomChildIdWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val customId = "custom-child-${info.workflowId}"
            val options = ChildWorkflowOptions(workflowId = customId)
            val result = startChildWorkflow<String>("SimpleChildWorkflow", options).result()
            return result
        }
    }

    /**
     * Nested child workflow (grandchild).
     */
    @Workflow("GrandchildWorkflow")
    class GrandchildWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "Hello from grandchild"
    }

    /**
     * Child workflow that starts its own child (grandchild).
     */
    @Workflow("ChildThatStartsGrandchild")
    class ChildThatStartsGrandchild {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val grandchildResult = startChildWorkflow<String>("GrandchildWorkflow", ChildWorkflowOptions()).result()
            return "Child received from grandchild: $grandchildResult"
        }
    }

    /**
     * Parent that starts a child which starts a grandchild.
     */
    @Workflow("NestedChildWorkflowsParent")
    class NestedChildWorkflowsParent {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val childResult = startChildWorkflow<String>("ChildThatStartsGrandchild", ChildWorkflowOptions()).result()
            return "Parent received: $childResult"
        }
    }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun `simple child workflow completes successfully`() =
        runTemporalTest {
            val taskQueue = "test-child-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<SimpleParentWorkflow>()
                    workflow<SimpleChildWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "SimpleParentWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("Parent received: Hello from child"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `child workflow receives arguments correctly`() =
        runTemporalTest {
            val taskQueue = "test-child-args-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ParentWithArgsWorkflow>()
                    workflow<ChildWithArgsWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, String>(
                    workflowType = "ParentWithArgsWorkflow",
                    taskQueue = taskQueue,
                    arg = "test input",
                )

            val result = handle.result(timeout = 30.seconds)
            assertTrue(result.contains("Processed: test input"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `multiple sequential child workflows complete`() =
        runTemporalTest {
            val taskQueue = "test-multi-child-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<MultipleChildrenWorkflow>()
                    workflow<SimpleChildWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "MultipleChildrenWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertTrue(result.contains("Child1:"))
            assertTrue(result.contains("Child2:"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `child workflow failure is caught by parent`() =
        runTemporalTest {
            val taskQueue = "test-child-failure-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ChildFailureHandlerWorkflow>()
                    workflow<FailingChildWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ChildFailureHandlerWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("Caught child failure:"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `child workflow with custom ID`() =
        runTemporalTest {
            val taskQueue = "test-custom-child-id-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<CustomChildIdWorkflow>()
                    workflow<SimpleChildWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "CustomChildIdWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            // The child should have the custom ID which includes parent's ID
            assertTrue(result.contains("custom-child-"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `nested child workflows complete successfully`() =
        runTemporalTest {
            val taskQueue = "test-nested-child-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<NestedChildWorkflowsParent>()
                    workflow<ChildThatStartsGrandchild>()
                    workflow<GrandchildWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "NestedChildWorkflowsParent",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertTrue(result.contains("grandchild"))

            handle.assertHistory {
                completed()
            }
        }
}
