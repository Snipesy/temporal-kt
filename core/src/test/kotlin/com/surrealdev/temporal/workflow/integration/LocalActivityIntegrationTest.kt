package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.RetryPolicy
import com.surrealdev.temporal.common.exceptions.WorkflowActivityCancelledException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startLocalActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real integration tests for local activity execution.
 *
 * These tests run against an actual Temporal test server and verify
 * end-to-end local activity behavior including:
 * - Basic execution and result handling
 * - Argument passing
 * - Parallel and sequential execution
 * - Failure handling and retries
 * - Cancellation
 *
 * Unlike unit tests that simulate activations, these tests execute
 * real activity code through the full worker pipeline.
 */
@Tag("integration")
class LocalActivityIntegrationTest {
    // ================================================================
    // Test Activities
    // ================================================================

    /**
     * Collection of local activities for testing.
     * Each method is annotated with @Activity to register it with the worker.
     */
    class TestLocalActivities {
        @Activity("localGreet")
        fun greet(name: String): String = "Hello, $name!"

        @Activity("localEcho")
        fun echo(message: String): String = message

        @Activity("localAdd")
        fun add(
            a: Int,
            b: Int,
        ): Int = a + b

        @Activity("localFailing")
        fun failing(): String = throw RuntimeException("Intentional local activity failure")

        @Activity("localSlowSuccess")
        fun slowSuccess(): String {
            Thread.sleep(50)
            return "slow done"
        }

        /**
         * Activity that fails on first N attempts, then succeeds.
         * Uses shared state to track attempts.
         */
        private val retryCounter = AtomicInteger(0)

        @Activity("localRetryable")
        fun retryable(): Int {
            val attempt = retryCounter.incrementAndGet()
            if (attempt < 3) {
                throw RuntimeException("Not ready yet, attempt $attempt")
            }
            return attempt
        }

        fun resetRetryCounter() {
            retryCounter.set(0)
        }
    }

    // ================================================================
    // Test Workflows
    // ================================================================

    @Workflow("BasicLocalActivityWorkflow")
    class BasicLocalActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(name: String): String =
            startLocalActivity<String>(
                activityType = "localGreet",
                arg = name,
                startToCloseTimeout = 10.seconds,
            ).result()
    }

    @Workflow("LocalActivityWithArgsWorkflow")
    class LocalActivityWithArgsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(
            a: Int,
            b: Int,
        ): Int =
            startLocalActivity<Int, Int>(
                activityType = "localAdd",
                arg1 = a,
                arg2 = b,
                startToCloseTimeout = 10.seconds,
            ).result()
    }

    @Workflow("ParallelLocalActivitiesWorkflow")
    class ParallelLocalActivitiesWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val handles =
                listOf("Alice", "Bob", "Charlie").map { name ->
                    async {
                        startLocalActivity<String>(
                            activityType = "localGreet",
                            arg = name,
                            startToCloseTimeout = 10.seconds,
                        ).result<String>()
                    }
                }

            val results: List<String> = handles.awaitAll()
            return results.joinToString(", ")
        }
    }

    @Workflow("SequentialLocalActivitiesWorkflow")
    class SequentialLocalActivitiesWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val r1: String =
                startLocalActivity<String>(
                    activityType = "localEcho",
                    arg = "step1",
                    startToCloseTimeout = 10.seconds,
                ).result()

            val r2: String =
                startLocalActivity<String>(
                    activityType = "localEcho",
                    arg = "$r1->step2",
                    startToCloseTimeout = 10.seconds,
                ).result()

            val r3: String =
                startLocalActivity<String>(
                    activityType = "localEcho",
                    arg = "$r2->step3",
                    startToCloseTimeout = 10.seconds,
                ).result()

            return r3
        }
    }

    @Workflow("FailingLocalActivityWorkflow")
    class FailingLocalActivityWorkflow {
        var caughtMessage: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                startLocalActivity(
                    activityType = "localFailing",
                    startToCloseTimeout = 10.seconds,
                    retryPolicy = RetryPolicy(maximumAttempts = 1),
                ).result<String>()
                return "unexpected success"
            } catch (e: WorkflowActivityFailureException) {
                caughtMessage = e.message
                return "caught: ${e.message}"
            }
        }
    }

    @Workflow("CancelLocalActivityWorkflow")
    class CancelLocalActivityWorkflow {
        var wasCancelled = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val handle =
                startLocalActivity(
                    activityType = "localSlowSuccess",
                    startToCloseTimeout = 60.seconds,
                )

            // Cancel immediately
            handle.cancel("Test cancellation")

            return try {
                handle.result<String>()
                "unexpected success"
            } catch (e: WorkflowActivityCancelledException) {
                wasCancelled = true
                "cancelled"
            }
        }
    }

    // ================================================================
    // Integration Tests
    // ================================================================

    /**
     * Tests basic local activity execution with a single argument.
     */
    @Test
    fun `local activity executes and returns result`() =
        runTemporalTest {
            val taskQueue = "test-la-basic-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<BasicLocalActivityWorkflow>()
                    activity(TestLocalActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "BasicLocalActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = "World",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("Hello, World!", result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Tests local activity with multiple arguments.
     */
    @Test
    fun `local activity with multiple arguments works correctly`() =
        runTemporalTest {
            val taskQueue = "test-la-args-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<LocalActivityWithArgsWorkflow>()
                    activity(TestLocalActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "LocalActivityWithArgsWorkflow",
                    taskQueue = taskQueue,
                    arg1 = 10,
                    arg2 = 32,
                )

            val result: Int = handle.result(timeout = 30.seconds)
            assertEquals(42, result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Tests parallel local activity execution.
     * Multiple activities should be scheduled concurrently.
     */
    @Test
    fun `parallel local activities execute concurrently`() =
        runTemporalTest {
            val taskQueue = "test-la-parallel-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ParallelLocalActivitiesWorkflow>()
                    activity(TestLocalActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ParallelLocalActivitiesWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)

            // Should have all three greetings
            assertTrue(result.contains("Hello, Alice!"), "Should greet Alice")
            assertTrue(result.contains("Hello, Bob!"), "Should greet Bob")
            assertTrue(result.contains("Hello, Charlie!"), "Should greet Charlie")

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Tests sequential local activity execution.
     * Activities should chain their results correctly.
     */
    @Test
    fun `sequential local activities execute in order`() =
        runTemporalTest {
            val taskQueue = "test-la-sequential-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<SequentialLocalActivitiesWorkflow>()
                    activity(TestLocalActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "SequentialLocalActivitiesWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("step1->step2->step3", result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Tests local activity failure handling.
     * Workflow should catch WorkflowActivityFailureException.
     */
    @Test
    fun `local activity failure is caught by workflow`() =
        runTemporalTest {
            val taskQueue = "test-la-failure-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<FailingLocalActivityWorkflow>()
                    activity(TestLocalActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "FailingLocalActivityWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("caught:"), "Should catch failure: $result")

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Tests local activity cancellation.
     * Cancelling before completion should throw WorkflowActivityCancelledException.
     */
    @Test
    fun `local activity cancellation throws WorkflowActivityCancelledException`() =
        runTemporalTest {
            val taskQueue = "test-la-cancel-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<CancelLocalActivityWorkflow>()
                    activity(TestLocalActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CancelLocalActivityWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("cancelled", result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Tests that different workflow runs get isolated local activity results.
     */
    @Test
    fun `multiple workflow runs have isolated local activity state`() =
        runTemporalTest {
            val taskQueue = "test-la-isolation-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<BasicLocalActivityWorkflow>()
                    activity(TestLocalActivities())
                }
            }

            val client = client()

            // Start multiple workflows concurrently
            val handles =
                listOf("Alice", "Bob", "Charlie").map { name ->
                    client.startWorkflow(
                        workflowType = "BasicLocalActivityWorkflow",
                        taskQueue = taskQueue,
                        arg = name,
                    )
                }

            // Collect results
            val results: List<String> = handles.map { it.result<String>(timeout = 30.seconds) }

            // Each should have the correct greeting
            assertEquals("Hello, Alice!", results[0])
            assertEquals("Hello, Bob!", results[1])
            assertEquals("Hello, Charlie!", results[2])
        }
}
