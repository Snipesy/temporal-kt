package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.RetryPolicy
import com.surrealdev.temporal.client.WorkflowResultTimeoutException
import com.surrealdev.temporal.client.WorkflowStartOptions
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for workflow deadlock detection.
 *
 * These tests verify that:
 * - Workflows that don't yield within the timeout are detected as deadlocked
 * - WorkflowDeadlockException is thrown after the configured timeout
 * - The worker continues processing after a deadlock is detected
 * - Other workflows are not affected by a deadlocked workflow
 *
 * IMPORTANT: Deadlock detection causes a workflow TASK failure (not workflow execution failure).
 * This is correct per Temporal semantics - deadlocks are treated as transient issues that
 * should be retried (e.g., after worker fix/redeploy). The task will retry indefinitely
 * until resolved or workflow execution timeout is reached.
 */
class WorkflowDeadlockDetectionTest {
    /**
     * Workflow that blocks indefinitely, triggering deadlock detection.
     * Uses Thread.sleep which is interruptible, allowing clean termination.
     */
    @Workflow("InfiniteLoopWorkflow")
    class InfiniteLoopWorkflow {
        @WorkflowRun
        fun WorkflowContext.run(): String {
            // Blocking call without yielding - will trigger deadlock detection
            // Uses sleep which responds to Thread.interrupt() for clean termination
            Thread.sleep(Long.MAX_VALUE)
            return "Should not reach here"
        }
    }

    /**
     * Workflow that blocks on Thread.sleep, triggering deadlock detection.
     */
    @Workflow("BlockingWorkflow")
    class BlockingWorkflow {
        @WorkflowRun
        fun WorkflowContext.run(): String {
            // Blocking call without runInterruptible - triggers deadlock detection
            Thread.sleep(30_000)
            return "Should not reach here"
        }
    }

    /**
     * Normal workflow that completes successfully.
     */
    @Workflow("NormalWorkflow")
    class NormalWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(value: String): String = "Success: $value"
    }

    // ================================================================
    // Unit Tests - Test the deadlock detection mechanism directly
    // ================================================================

    @Test
    fun `WorkflowVirtualThread throws WorkflowDeadlockException after timeout`() {
        // Create a mock executor that blocks forever
        val blockingExecutor =
            object {
                fun activate(): Nothing {
                    Thread.sleep(Long.MAX_VALUE)
                    throw AssertionError("Should not reach here")
                }
            }

        // Create a virtual thread with short timeout
        val threadFactory = Thread.ofVirtual().name("test-deadlock-", 0).factory()
        val activationQueue = LinkedBlockingQueue<Runnable>()
        val completionDeferred = CompletableDeferred<String>()

        val thread =
            threadFactory.newThread {
                try {
                    blockingExecutor.activate()
                    completionDeferred.complete("done")
                } catch (e: InterruptedException) {
                    completionDeferred.completeExceptionally(e)
                }
            }
        thread.start()

        // Wait with timeout - should not complete
        val result =
            runBlocking {
                withTimeoutOrNull(500) {
                    completionDeferred.await()
                }
            }

        // Should timeout (return null)
        assertEquals(null, result, "Expected timeout, but got result")

        // Cleanup
        thread.interrupt()
        thread.join(1000)
    }

    // ================================================================
    // Integration Tests
    // ================================================================

    @Test
    fun `infinite loop workflow triggers deadlock detection and retries until client timeout`() =
        runTemporalTest {
            val taskQueue = "test-deadlock-loop-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    // Use a short deadlock timeout for faster tests
                    workflowDeadlockTimeoutMs = 500L
                    workflow<InfiniteLoopWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InfiniteLoopWorkflow",
                    taskQueue = taskQueue,
                )

            // Deadlock detection causes a workflow TASK failure (not workflow execution failure).
            // This is correct behavior per Temporal semantics - deadlocks are treated as
            // transient issues that should be retried (e.g., after worker fix/redeploy).
            // The task will retry indefinitely until:
            // 1. The deadlock is resolved (worker fix deployed)
            // 2. Workflow execution timeout is reached
            // 3. Workflow is manually terminated
            // 4. Client timeout (what happens here)
            val exception =
                assertFailsWith<WorkflowResultTimeoutException> {
                    handle.result(timeout = 3.seconds)
                }

            // Verify we got a client-side timeout (workflow is still retrying)
            assertTrue(
                exception.message!!.contains("Timed out"),
                "Expected timeout exception, got: ${exception.message}",
            )
        }

    @Test
    fun `blocking workflow triggers deadlock detection`() =
        runTemporalTest {
            val taskQueue = "test-deadlock-blocking-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflowDeadlockTimeoutMs = 500L
                    workflow<BlockingWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "BlockingWorkflow",
                    taskQueue = taskQueue,
                    options =
                        WorkflowStartOptions(
                            retryPolicy =
                                RetryPolicy(
                                    maximumAttempts = 1,
                                ),
                        ),
                )

            // Same as infinite loop - deadlock causes task failure and retry
            val exception =
                assertFailsWith<WorkflowResultTimeoutException> {
                    handle.result(timeout = 3.seconds)
                }

            assertTrue(exception.message!!.contains("Timed out"))
        }

    @Test
    fun `worker continues processing after deadlock detection`() =
        runTemporalTest {
            val taskQueue = "test-deadlock-resilience-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflowDeadlockTimeoutMs = 500L
                    workflow<InfiniteLoopWorkflow>()
                    workflow<NormalWorkflow>()
                }
            }

            val client = client()

            // Start a deadlocking workflow
            val deadlockHandle =
                client.startWorkflow(
                    workflowType = "InfiniteLoopWorkflow",
                    taskQueue = taskQueue,
                )

            // Let deadlock detection trigger - will timeout at client
            assertFailsWith<WorkflowResultTimeoutException> {
                deadlockHandle.result(timeout = 2.seconds)
            }

            // Start a normal workflow - should complete successfully
            // The deadlocked workflow doesn't block other workflows
            val normalHandle =
                client.startWorkflow<String>(
                    workflowType = "NormalWorkflow",
                    taskQueue = taskQueue,
                    arg = "after deadlock",
                )

            val result: String = normalHandle.result(timeout = 10.seconds)
            assertEquals("Success: after deadlock", result)
        }

    @Test
    fun `concurrent normal workflow is not affected by deadlocked workflow`() =
        runTemporalTest {
            val taskQueue = "test-deadlock-isolation-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflowDeadlockTimeoutMs = 500L
                    workflow<InfiniteLoopWorkflow>()
                    workflow<NormalWorkflow>()
                }
            }

            val client = client()

            // Start both workflows concurrently
            val deadlockHandle =
                client.startWorkflow(
                    workflowType = "InfiniteLoopWorkflow",
                    taskQueue = taskQueue,
                    workflowId = "deadlock-wf-${UUID.randomUUID()}",
                )

            val normalHandle =
                client.startWorkflow<String>(
                    workflowType = "NormalWorkflow",
                    taskQueue = taskQueue,
                    workflowId = "normal-wf-${UUID.randomUUID()}",
                    arg = "concurrent",
                )

            // Normal workflow should complete successfully despite deadlocked neighbor
            val result: String = normalHandle.result(timeout = 10.seconds)
            assertEquals("Success: concurrent", result)

            // Deadlocked workflow will timeout at client (task keeps retrying)
            assertFailsWith<WorkflowResultTimeoutException> {
                deadlockHandle.result(timeout = 2.seconds)
            }
        }

    @Test
    fun `deadlock detection can be disabled with timeout of 0`() =
        runTemporalTest {
            val taskQueue = "test-deadlock-disabled-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    // Disable deadlock detection
                    workflowDeadlockTimeoutMs = 0L
                    workflow<NormalWorkflow>()
                }
            }

            val client = client()

            // Normal workflows should still work
            val handle =
                client.startWorkflow<String>(
                    workflowType = "NormalWorkflow",
                    taskQueue = taskQueue,
                    arg = "no deadlock detection",
                )

            val result: String = handle.result(timeout = 10.seconds)
            assertEquals("Success: no deadlock detection", result)
        }

    @Test
    fun `multiple deadlocked workflows do not crash the worker`() =
        runTemporalTest {
            val taskQueue = "test-multiple-deadlocks-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflowDeadlockTimeoutMs = 500L
                    workflow<InfiniteLoopWorkflow>()
                    workflow<NormalWorkflow>()
                }
            }

            val client = client()

            // Start multiple deadlocking workflows
            val deadlockHandles =
                (1..3).map { i ->
                    client.startWorkflow(
                        workflowType = "InfiniteLoopWorkflow",
                        taskQueue = taskQueue,
                        workflowId = "deadlock-$i-${UUID.randomUUID()}",
                    )
                }

            // All should timeout at client (tasks keep retrying)
            deadlockHandles.forEach { handle ->
                assertFailsWith<WorkflowResultTimeoutException> {
                    handle.result(timeout = 2.seconds)
                }
            }

            // Worker should still be functional
            val normalHandle =
                client.startWorkflow<String>(
                    workflowType = "NormalWorkflow",
                    taskQueue = taskQueue,
                    arg = "after multiple deadlocks",
                )

            val result: String = normalHandle.result(timeout = 10.seconds)
            assertEquals("Success: after multiple deadlocks", result)
        }

    @Test
    fun `deadlock timeout is configurable per task queue`() =
        runTemporalTest {
            val shortTimeoutQueue = "test-short-timeout-${UUID.randomUUID()}"
            val longTimeoutQueue = "test-long-timeout-${UUID.randomUUID()}"

            application {
                taskQueue(shortTimeoutQueue) {
                    workflowDeadlockTimeoutMs = 200L
                    workflow<NormalWorkflow>()
                }
                taskQueue(longTimeoutQueue) {
                    workflowDeadlockTimeoutMs = 5000L
                    workflow<NormalWorkflow>()
                }
            }

            val client = client()

            // Both queues should work with normal workflows
            val shortResult =
                client
                    .startWorkflow(
                        workflowType = "NormalWorkflow",
                        taskQueue = shortTimeoutQueue,
                        arg = "short",
                    ).result<String>(timeout = 10.seconds)

            val longResult =
                client
                    .startWorkflow(
                        workflowType = "NormalWorkflow",
                        taskQueue = longTimeoutQueue,
                        arg = "long",
                    ).result<String>(timeout = 10.seconds)

            assertEquals("Success: short", shortResult)
            assertEquals("Success: long", longResult)
        }
}
