package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for workflow failure and cancellation handling.
 *
 * These tests verify that:
 * - Workflow exceptions are converted to Temporal workflow failures
 * - Exceptions don't propagate up to the application/worker
 * - Cancellations are handled correctly
 * - Multiple workflows can fail independently without affecting each other
 * - The worker continues processing after workflow failures
 */
class WorkflowFailureHandlingTest {
    /**
     * Workflow that throws an exception immediately.
     */
    @Workflow("FailingWorkflow")
    class FailingWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(message: String): String =
            throw IllegalStateException("Workflow failed: $message")
    }

    /**
     * Workflow that throws an exception after some work.
     */
    @Workflow("FailAfterSleepWorkflow")
    class FailAfterSleepWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(50.milliseconds)
            throw RuntimeException("Failed after sleep")
        }
    }

    /**
     * Workflow that throws an exception in an async block.
     */
    @Workflow("FailInAsyncWorkflow")
    class FailInAsyncWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val deferred =
                async {
                    sleep(30.milliseconds)
                    throw IllegalArgumentException("Async block failed")
                }
            deferred.await()
        }
    }

    /**
     * Workflow that launches a coroutine that fails.
     */
    @Workflow("FailInLaunchWorkflow")
    class FailInLaunchWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val job =
                launch {
                    sleep(30.milliseconds)
                    throw IllegalStateException("Launched coroutine failed")
                }
            job.join()
            return "Should not reach here"
        }
    }

    /**
     * Workflow that can be cancelled externally.
     */
    @Workflow("CancellableWorkflow")
    class CancellableWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(1.seconds)
            return "Completed"
        }
    }

    /**
     * Workflow that succeeds normally.
     * Used to verify worker continues processing after failures.
     */
    @Workflow("SuccessfulWorkflow")
    class SuccessfulWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(value: String): String = "Success: $value"
    }

    /**
     * Workflow with multiple async operations where one fails.
     */
    @Workflow("PartialAsyncFailureWorkflow")
    class PartialAsyncFailureWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val deferred1 =
                async {
                    sleep(30.milliseconds)
                    "A"
                }
            val deferred2 =
                async {
                    sleep(30.milliseconds)
                    throw IllegalStateException("One async failed")
                }

            // This should propagate the exception
            val result1 = deferred1.await()
            val result2 = deferred2.await()
            return "$result1$result2"
        }
    }

    /**
     * Workflow that throws a custom exception with detailed message.
     */
    @Workflow("CustomExceptionWorkflow")
    class CustomExceptionWorkflow {
        class CustomWorkflowException(
            message: String,
            cause: Throwable? = null,
        ) : Exception(message, cause)

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(25.milliseconds)
            throw CustomWorkflowException(
                "Custom error with context",
                IllegalArgumentException("Root cause"),
            )
        }
    }

    // ================================================================
    // Exception Handling Tests
    // ================================================================

    @Test
    fun `workflow exception results in workflow failure not application crash`() =
        runTemporalTest {
            val taskQueue = "test-exception-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<FailingWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, String>(
                    workflowType = "FailingWorkflow",
                    taskQueue = taskQueue,
                    arg = "test error",
                )

            // Should get a workflow failure exception, not crash
            val exception =
                assertFailsWith<Exception> {
                    handle.result(timeout = 10.seconds)
                }

            // Verify the exception contains our message
            assertEquals(
                exception.message?.contains("Workflow failed: test error"),
                true,
                "Expected error message, got: ${exception.message}",
            )

            // Verify workflow failed (not completed)
            handle.assertHistory {
                failed()
                check("Workflow should not be completed") { !it.isCompleted }
            }
        }

    @Test
    fun `workflow exception after sleep is handled correctly`() =
        runTemporalTest {
            val taskQueue = "test-exception-after-sleep-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<FailAfterSleepWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "FailAfterSleepWorkflow",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<Exception> {
                    handle.result(timeout = 10.seconds)
                }

            assertEquals(exception.message?.contains("Failed after sleep"), true)

            // Verify timer was created before failure
            handle.assertHistory {
                failed()
                hasTimerStarted()
                hasTimerFired()
            }
        }

    @Test
    fun `exception in async block propagates to workflow failure`() =
        runTemporalTest {
            val taskQueue = "test-async-exception-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<FailInAsyncWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "FailInAsyncWorkflow",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<Exception> {
                    handle.result(timeout = 10.seconds)
                }

            assertEquals(exception.message?.contains("Async block failed"), true)

            handle.assertHistory {
                failed()
                hasTimerStarted() // Timer from sleep in async block
            }
        }

    @Test
    fun `exception in launched coroutine propagates to workflow failure`() =
        runTemporalTest {
            val taskQueue = "test-launch-exception-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<FailInLaunchWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "FailInLaunchWorkflow",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<Exception> {
                    handle.result(timeout = 10.seconds)
                }

            // When a launch {} block fails, it cancels the workflow via structured concurrency
            // The error message indicates cancellation, not the original exception
            assertTrue(
                exception.message?.contains("Cancelling") == true ||
                    exception.message?.contains("Launched coroutine failed") == true,
                "Expected cancellation or failure message, got: ${exception.message}",
            )

            handle.assertHistory {
                failed()
            }
        }

    @Test
    fun `custom exception is properly serialized in failure`() =
        runTemporalTest {
            val taskQueue = "test-custom-exception-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<CustomExceptionWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "CustomExceptionWorkflow",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<Exception> {
                    handle.result(timeout = 10.seconds)
                }

            // Verify exception message is preserved
            assertEquals(
                exception.message?.contains("Custom error with context"),
                true,
                "Expected custom error message, got: ${exception.message}",
            )

            handle.assertHistory {
                failed()
            }
        }

    @Test
    fun `partial async failure propagates correctly`() =
        runTemporalTest {
            val taskQueue = "test-partial-async-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<PartialAsyncFailureWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "PartialAsyncFailureWorkflow",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<Exception> {
                    handle.result(timeout = 10.seconds)
                }

            // Structured concurrency: when one async fails, it cancels the workflow
            assertTrue(
                exception.message?.contains("Cancelling") == true ||
                    exception.message?.contains("One async failed") == true,
                "Expected cancellation or failure message, got: ${exception.message}",
            )

            handle.assertHistory {
                failed()
                // Both async blocks should have created timers
                timerCount(2)
            }
        }

    // ================================================================
    // Worker Resilience Tests
    // ================================================================

    @Test
    fun `worker continues processing after workflow failure`() =
        runTemporalTest {
            val taskQueue = "test-worker-resilience-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<FailingWorkflow>()
                    workflow<SuccessfulWorkflow>()
                }
            }

            val client = client()

            // Start a workflow that fails
            val failingHandle =
                client.startWorkflow<String, String>(
                    workflowType = "FailingWorkflow",
                    taskQueue = taskQueue,
                    arg = "first failure",
                )

            assertFailsWith<Exception> {
                failingHandle.result(timeout = 10.seconds)
            }

            // Start a successful workflow - should work fine
            val successHandle =
                client.startWorkflow<String, String>(
                    workflowType = "SuccessfulWorkflow",
                    taskQueue = taskQueue,
                    arg = "after failure",
                )

            val result = successHandle.result(timeout = 10.seconds)
            assertEquals("Success: after failure", result)

            // Start another failing workflow - should also work
            val failingHandle2 =
                client.startWorkflow<String, String>(
                    workflowType = "FailingWorkflow",
                    taskQueue = taskQueue,
                    arg = "second failure",
                )

            assertFailsWith<Exception> {
                failingHandle2.result(timeout = 10.seconds)
            }
        }

    @Test
    fun `multiple workflows can fail independently`() =
        runTemporalTest {
            val taskQueue = "test-multiple-failures-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<FailingWorkflow>()
                }
            }

            val client = client()

            // Start multiple workflows that all fail
            val handles =
                (1..3).map { i ->
                    client.startWorkflow<String, String>(
                        workflowType = "FailingWorkflow",
                        taskQueue = taskQueue,
                        arg = "failure $i",
                    )
                }

            // All should fail with their respective messages
            handles.forEachIndexed { i, handle ->
                val exception =
                    assertFailsWith<Exception> {
                        handle.result(timeout = 10.seconds)
                    }
                assertEquals(exception.message?.contains("failure ${i + 1}"), true)
            }
        }

    @Test
    fun `noisy neighbor - concurrent workflows with one failing do not interfere`() =
        runTemporalTest {
            val taskQueue = "test-noisy-neighbor-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<FailAfterSleepWorkflow>()
                    workflow<SuccessfulWorkflow>()
                }
            }

            val client = client()

            // Start both workflows concurrently without waiting
            val failingHandle =
                client.startWorkflow<String>(
                    workflowType = "FailAfterSleepWorkflow",
                    taskQueue = taskQueue,
                )

            val successHandle =
                client.startWorkflow<String, String>(
                    workflowType = "SuccessfulWorkflow",
                    taskQueue = taskQueue,
                    arg = "neighbor",
                )

            // The failing workflow should fail
            val failException =
                assertFailsWith<Exception> {
                    failingHandle.result(timeout = 10.seconds)
                }
            assertEquals(failException.message?.contains("Failed after sleep"), true)

            // The successful workflow should complete despite its neighbor failing
            val result = successHandle.result(timeout = 10.seconds)
            assertEquals("Success: neighbor", result)

            // Verify both workflows reached their expected terminal states
            failingHandle.assertHistory {
                failed()
            }

            successHandle.assertHistory {
                completed()
            }
        }

    // ================================================================
    // Cancellation Tests
    // ================================================================

    @Test
    fun `workflow cancellation is handled gracefully`() =
        runTemporalTest {
            val taskQueue = "test-cancellation-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<CancellableWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "CancellableWorkflow",
                    taskQueue = taskQueue,
                )

            // Cancel the workflow
            handle.cancel()

            // Cancellation should be reflected in the result
            val exception =
                assertFailsWith<Exception> {
                    handle.result(timeout = 10.seconds)
                }

            // Should be a cancellation-related exception
            // (The exact type depends on how Temporal SDK reports cancellations)
            println("Cancellation exception: ${exception.message}")

            handle.assertHistory {
                // Workflow should be cancelled, not completed
                canceled()
                check("Workflow should not be completed") { !it.isCompleted }
            }
        }

    @Test
    fun `worker continues after workflow cancellation`() =
        runTemporalTest {
            val taskQueue = "test-cancel-resilience-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<CancellableWorkflow>()
                    workflow<SuccessfulWorkflow>()
                }
            }

            val client = client()

            // Start and cancel a workflow
            val cancelledHandle =
                client.startWorkflow<String>(
                    workflowType = "CancellableWorkflow",
                    taskQueue = taskQueue,
                )

            cancelledHandle.cancel()

            assertFailsWith<Exception> {
                cancelledHandle.result(timeout = 10.seconds)
            }

            // Worker should still process new workflows
            val successHandle =
                client.startWorkflow<String, String>(
                    workflowType = "SuccessfulWorkflow",
                    taskQueue = taskQueue,
                    arg = "after cancellation",
                )

            val result = successHandle.result(timeout = 10.seconds)
            assertEquals("Success: after cancellation", result)
        }

    // ================================================================
    // Edge Cases
    // ================================================================

    @Test
    fun `workflow with null pointer exception is handled`() =
        runTemporalTest {
            @Workflow("NullPointerWorkflow")
            class NullPointerWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String {
                    val nullString: String? = null
                    // This will throw NPE
                    return nullString!!.uppercase()
                }
            }

            val taskQueue = "test-npe-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<NullPointerWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "NullPointerWorkflow",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<Exception> {
                    handle.result(timeout = 10.seconds)
                }

            // Should capture the NPE in the failure
            println("NPE captured as: ${exception.message}")

            handle.assertHistory {
                failed()
            }
        }
}
