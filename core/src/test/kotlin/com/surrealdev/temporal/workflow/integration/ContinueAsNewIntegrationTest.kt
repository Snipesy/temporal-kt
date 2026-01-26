package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ContinueAsNewOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.continueAsNew
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for continue-as-new functionality.
 *
 * These tests run against a real Temporal server to verify end-to-end behavior
 * of workflows that use continueAsNew() to restart execution.
 */
@Tag("integration")
class ContinueAsNewIntegrationTest {
    // ================================================================
    // Test Data Classes
    // ================================================================

    @Serializable
    data class IterationState(
        val iteration: Int,
        val accumulator: String,
    )

    // ================================================================
    // Test Workflow Classes
    // ================================================================

    /**
     * Simple workflow that continues as new once.
     */
    @Workflow("SimpleContinueAsNewWorkflow")
    class SimpleContinueAsNewWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(iteration: Int): String {
            if (iteration >= 2) {
                return "completed at iteration $iteration"
            }
            continueAsNew(iteration + 1)
        }
    }

    /**
     * Workflow that continues as new multiple times (chain of runs).
     */
    @Workflow("ChainedContinueAsNewWorkflow")
    class ChainedContinueAsNewWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(iteration: Int): String {
            if (iteration >= 5) {
                return "completed after $iteration iterations"
            }
            // Small sleep to make history more interesting
            sleep(10.milliseconds)
            continueAsNew(iteration + 1)
        }
    }

    /**
     * Workflow that continues as new with complex data.
     */
    @Workflow("ComplexDataContinueAsNewWorkflow")
    class ComplexDataContinueAsNewWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(state: IterationState): String {
            if (state.iteration >= 3) {
                return "Final: ${state.accumulator}"
            }
            val newState =
                IterationState(
                    iteration = state.iteration + 1,
                    accumulator = "${state.accumulator}-${state.iteration}",
                )
            continueAsNew(newState)
        }
    }

    /**
     * Workflow that continues as new with multiple arguments.
     */
    @Workflow("MultiArgContinueAsNewWorkflow")
    class MultiArgContinueAsNewWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(
            count: Int,
            message: String,
        ): String {
            if (count >= 3) {
                return "Final: $message (count=$count)"
            }
            continueAsNew(count + 1, "$message!")
        }
    }

    /**
     * Workflow that continues as new with explicit options.
     */
    @Workflow("ContinueAsNewWithOptionsWorkflow")
    class ContinueAsNewWithOptionsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(iteration: Int): String {
            if (iteration >= 2) {
                return "completed with options at $iteration"
            }
            // Continue with explicit task queue (same as current)
            continueAsNew(
                iteration + 1,
                ContinueAsNewOptions(
                    workflowRunTimeout = 60.seconds,
                ),
            )
        }
    }

    /**
     * Target workflow for continue-as-new to different type.
     */
    @Workflow("TargetWorkflow")
    class TargetWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(value: Int): String = "TargetWorkflow received: $value"
    }

    /**
     * Workflow that continues as new to a different workflow type.
     */
    @Workflow("ContinueToNewTypeWorkflow")
    class ContinueToNewTypeWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            continueAsNew(
                42,
                ContinueAsNewOptions(
                    workflowType = "TargetWorkflow",
                ),
            )
        }
    }

    /**
     * Workflow with pending timer that continues as new.
     */
    @Workflow("ContinueAsNewWithTimerWorkflow")
    class ContinueAsNewWithTimerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(iteration: Int): String {
            // Start a timer
            sleep(50.milliseconds)

            if (iteration >= 2) {
                return "completed at iteration $iteration"
            }

            // Continue as new - pending state should be cleaned up
            continueAsNew(iteration + 1)
        }
    }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun `workflow continues as new with same type`() =
        runTemporalTest {
            val taskQueue = "test-can-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SimpleContinueAsNewWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, Int>(
                    workflowType = "SimpleContinueAsNewWorkflow",
                    taskQueue = taskQueue,
                    arg = 1,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("completed at iteration 2", result)

            // Note: Can't use assertHistory { completed() } because the original run's
            // history shows "continued-as-new", not "completed". The result() call
            // follows the chain to get the final result.
        }

    @Test
    fun `workflow continues as new multiple times (chain of 5)`() =
        runTemporalTest {
            val taskQueue = "test-can-chain-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ChainedContinueAsNewWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, Int>(
                    workflowType = "ChainedContinueAsNewWorkflow",
                    taskQueue = taskQueue,
                    arg = 1,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("completed after 5 iterations", result)

            // Note: Can't use assertHistory { completed() } because the original run's
            // history shows "continued-as-new", not "completed". The result() call
            // follows the chain to get the final result.
        }

    @Test
    fun `workflow continues as new with complex data`() =
        runTemporalTest {
            val taskQueue = "test-can-complex-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ComplexDataContinueAsNewWorkflow())
                }
            }

            val client = client()
            val initialState = IterationState(iteration = 1, accumulator = "start")
            val handle =
                client.startWorkflow<String, IterationState>(
                    workflowType = "ComplexDataContinueAsNewWorkflow",
                    taskQueue = taskQueue,
                    arg = initialState,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Final: start-1-2", result)

            // Note: Can't use assertHistory { completed() } because the original run's
            // history shows "continued-as-new", not "completed". The result() call
            // follows the chain to get the final result.
        }

    @Test
    fun `workflow continues as new with multiple arguments`() =
        runTemporalTest {
            val taskQueue = "test-can-multi-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(MultiArgContinueAsNewWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, Int, String>(
                    workflowType = "MultiArgContinueAsNewWorkflow",
                    taskQueue = taskQueue,
                    arg1 = 1,
                    arg2 = "Hello",
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Final: Hello!! (count=3)", result)

            // Note: Can't use assertHistory { completed() } because the original run's
            // history shows "continued-as-new", not "completed". The result() call
            // follows the chain to get the final result.
        }

    @Test
    fun `workflow continues as new with explicit options`() =
        runTemporalTest {
            val taskQueue = "test-can-options-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ContinueAsNewWithOptionsWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, Int>(
                    workflowType = "ContinueAsNewWithOptionsWorkflow",
                    taskQueue = taskQueue,
                    arg = 1,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("completed with options at 2", result)

            // Note: Can't use assertHistory { completed() } because the original run's
            // history shows "continued-as-new", not "completed". The result() call
            // follows the chain to get the final result.
        }

    @Test
    fun `workflow continues as new to different workflow type`() =
        runTemporalTest {
            val taskQueue = "test-can-newtype-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ContinueToNewTypeWorkflow())
                    workflow(TargetWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ContinueToNewTypeWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("TargetWorkflow received: 42", result)

            // Note: Can't use assertHistory { completed() } because the original run's
            // history shows "continued-as-new", not "completed". The result() call
            // follows the chain to get the final result.
        }

    @Test
    fun `workflow continues as new with pending timer`() =
        runTemporalTest {
            val taskQueue = "test-can-timer-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ContinueAsNewWithTimerWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, Int>(
                    workflowType = "ContinueAsNewWithTimerWorkflow",
                    taskQueue = taskQueue,
                    arg = 1,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("completed at iteration 2", result)

            // Note: Can't use assertHistory { completed() } because the original run's
            // history shows "continued-as-new", not "completed". The result() call
            // follows the chain to get the final result.
        }

    @Test
    fun `final run completes normally after continue-as-new chain`() =
        runTemporalTest {
            val taskQueue = "test-can-final-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ChainedContinueAsNewWorkflow())
                }
            }

            val client = client()

            // Start with iteration 1
            val handle =
                client.startWorkflow<String, Int>(
                    workflowType = "ChainedContinueAsNewWorkflow",
                    taskQueue = taskQueue,
                    arg = 1,
                )

            val result = handle.result(timeout = 60.seconds)

            // Verify final result
            assertTrue(result.startsWith("completed after"))

            // The history should show the final run completed
            // Note: Can't use assertHistory { completed() } because the original run's
            // history shows "continued-as-new", not "completed". The result() call
            // follows the chain to get the final result.
        }

    // ================================================================
    // Tests for historyLength and isContinueAsNewSuggested
    // ================================================================

    /**
     * Workflow that exposes history metrics via result.
     */
    @Workflow("HistoryMetricsTestWorkflow")
    class HistoryMetricsTestWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Return all history metrics
            return "historyLength=$historyLength, historySizeBytes=$historySizeBytes, " +
                "suggested=${isContinueAsNewSuggested()}"
        }
    }

    @Test
    fun `workflow can access historyLength, historySizeBytes, and isContinueAsNewSuggested`() =
        runTemporalTest {
            val taskQueue = "test-history-metrics-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(HistoryMetricsTestWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "HistoryMetricsTestWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            // History length should be present and positive
            assertTrue(result.contains("historyLength="))
            // History size should be present
            assertTrue(result.contains("historySizeBytes="))
            // For a small workflow, server will not suggest continue-as-new
            assertTrue(result.contains("suggested=false"))
        }
}
