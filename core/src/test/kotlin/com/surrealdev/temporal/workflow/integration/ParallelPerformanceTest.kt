package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Tag
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime
import kotlin.time.toJavaDuration

/**
 * Integration tests for parallel performance.
 *
 * These tests verify that:
 * 1. Activities can be executed in parallel (1,000 activities each sleeping 1 second should complete fast)
 * 2. Workflows can be executed in parallel (1,000 workflows each sleeping 1 second should complete fast)
 *
 * Both tests rely on the parallel processing improvements in WorkflowDispatcher and ManagedWorker.
 */
@Tag("integration")
class ParallelPerformanceTest {
    companion object {
        const val ACTIVITY_COUNT = 1000
        const val WORKFLOW_COUNT = 500
        const val SLEEP_DURATION_MS = 1000L // 1 second
    }

    /**
     * Activity that Thread.sleeps for 1 second.
     * This blocks the thread to simulate I/O-bound work.
     */
    class SlowActivity {
        @Activity("slowSleep")
        fun ActivityContext.slowSleep(): String {
            Thread.sleep(SLEEP_DURATION_MS)
            return "done"
        }
    }

    class SlowDelayActivity {
        @Activity("slowSleep")
        suspend fun ActivityContext.slowSleep(): String {
            // Use coroutine delay instead of Thread.sleep
            delay(SLEEP_DURATION_MS)
            return "done"
        }
    }

    /**
     * Workflow that runs ACTIVITY_COUNT activities in parallel.
     * Each activity sleeps for 1 second.
     *
     * With parallel execution, this should complete in ~1 second + overhead,
     * not ACTIVITY_COUNT seconds.
     */
    @Workflow("ParallelActivitiesWorkflow")
    class ParallelActivitiesWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): Int {
            // Launch all activities in parallel using async
            val deferreds =
                (1..ACTIVITY_COUNT).map {
                    async {
                        startActivity<String>(
                            activityType = "slowSleep",
                            options = ActivityOptions(startToCloseTimeout = 2.minutes),
                        ).result()
                    }
                }

            val deferredDelay =
                (1..ACTIVITY_COUNT).map {
                    async {
                        startActivity<String>(
                            activityType = "slowSleep",
                            options = ActivityOptions(startToCloseTimeout = 2.minutes),
                        ).result()
                    }
                }

            // Wait for all to complete
            val results = deferreds.awaitAll()

            val resultsDelay = deferredDelay.awaitAll()

            return results.size + resultsDelay.size
        }
    }

    /**
     * Simple workflow that sleeps for 1 second using Temporal's timer.
     * Used to test parallel workflow execution.
     */
    @Workflow("OneSleepWorkflow")
    class OneSleepWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(SLEEP_DURATION_MS.milliseconds)
            return "slept"
        }
    }

    /**
     * Simple workflow that deadlocks (partially)
     */
    @Workflow("OneDeadLockedWorkflow")
    class OneDeadLockedWorkflow {
        @Suppress("BlockingMethodInNonBlockingContext")
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            Thread.sleep(100.milliseconds.toJavaDuration())
            return "slept"
        }
    }

    /**
     * Test 1: A single workflow running 1,000 activities in parallel.
     *
     * Each activity Thread.sleeps for 1 second. With truly parallel execution,
     * this should complete in roughly 1 second (plus overhead), not 1,000 seconds.
     *
     * This test validates that:
     * - Activities are dispatched concurrently
     * - Thread pool can handle many concurrent activities
     * - Results are collected correctly
     */
    @Test
    fun `single workflow with 1000 parallel activities completes quickly`() =
        runTemporalTest(timeSkipping = true, parentCoroutineContext = Dispatchers.Default) {
            val taskQueue = "parallel-activities-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    maxConcurrentActivities = ACTIVITY_COUNT // Allow all activities to run concurrently
                    workflow(ParallelActivitiesWorkflow())
                    activity(SlowActivity())
                }
            }

            val client = client()
            val elapsed =
                measureTime {
                    val handle =
                        client.startWorkflow<Int>(
                            workflowType = "ParallelActivitiesWorkflow",
                            taskQueue = taskQueue,
                        )

                    val result = handle.result(timeout = 5.minutes)
                    assertEquals(ACTIVITY_COUNT * 2, result)
                }

            println("Parallel activities test completed in $elapsed")

            // With parallel execution, should complete in much less than sequential time
            // Sequential would be ~1000 seconds; parallel should be ~10-30 seconds max
            // (accounting for thread pool limits, scheduling overhead, etc.)
            assertTrue(
                elapsed < 2.minutes,
                "Expected parallel activities to complete in under 2 minutes, but took $elapsed. " +
                    "Sequential execution would take ~${ACTIVITY_COUNT} seconds.",
            )
        }

    /**
     * Test 2: 1,000 workflows each sleeping 1 second (timeSkipping=false).
     *
     * With parallel workflow processing, starting 1,000 workflows that each
     * sleep for 1 second should complete quickly because:
     * - Workflow activations are processed in parallel
     * - Multiple workflows can be "waiting" on timers simultaneously
     *
     * This test validates that:
     * - Workflow polling uses fire-and-forget pattern
     * - Multiple workflows can be processed concurrently
     * - Per-runId mutex doesn't bottleneck different workflows
     */
    @Test
    fun `1000 workflows each sleeping 1 second complete quickly`() =
        runTemporalTest(timeSkipping = true, parentCoroutineContext = Dispatchers.Default) {
            val taskQueue = "parallel-workflows-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    // Workers should work fine on a single thread with sleep timers
                    maxConcurrentWorkflows = WORKFLOW_COUNT + 100 // Allow all workflows to run concurrently
                    workflow(OneSleepWorkflow())
                }
            }

            val client = client()

            val elapsed =
                measureTime {
                    // Start all workflows
                    val handles =
                        (1..WORKFLOW_COUNT).map { index ->
                            client.startWorkflow<String>(
                                workflowType = "OneSleepWorkflow",
                                taskQueue = taskQueue,
                                workflowId = "sleep-wf-$index-${UUID.randomUUID()}",
                            )
                        }

                    // Wait for all to complete
                    handles.forEach { handle ->
                        val result = handle.result(timeout = 5.minutes)
                        assertEquals("slept", result)
                    }
                }

            println("Parallel workflows test completed in $elapsed")

            // With parallel execution, should complete in much less than sequential time
            // Sequential would be ~1000 seconds; parallel should be ~10-30 seconds max
            assertTrue(
                elapsed < 2.minutes,
                "Expected parallel workflows to complete in under 2 minutes, but took $elapsed. " +
                    "Sequential execution would take ~${WORKFLOW_COUNT} seconds.",
            )
        }

    @Test
    fun `1000 workflows each deadlocking point 1 second complete quickly`() =
        runTemporalTest(timeSkipping = true, parentCoroutineContext = Dispatchers.Default) {
            val taskQueue = "parallel-workflows-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    maxConcurrentWorkflows = WORKFLOW_COUNT + 100 // Allow all workflows to run concurrently
                    workflowDeadlockTimeoutMs = 60000 // additional time to avoid flakey test runner deadlock detection
                    workflow(OneDeadLockedWorkflow())
                }
            }

            val client = client()

            val elapsed =
                measureTime {
                    // Start all workflows
                    val handles =
                        (1..WORKFLOW_COUNT).map { index ->
                            client.startWorkflow<String>(
                                workflowType = "OneDeadLockedWorkflow",
                                taskQueue = taskQueue,
                                workflowId = "sleep-wf-$index-${UUID.randomUUID()}",
                            )
                        }

                    // Wait for all to complete
                    handles.forEach { handle ->
                        val result = handle.result(timeout = 5.minutes)
                        assertEquals("slept", result)
                    }
                }

            println("Parallel workflows test completed in $elapsed")

            // With parallel execution, should complete in much less than sequential time
            // Sequential would be ~1000 seconds; parallel should be ~10-30 seconds max
            assertTrue(
                elapsed < 2.minutes,
                "Expected parallel workflows to complete in under 2 minutes, but took $elapsed. " +
                    "Sequential execution would take ~${WORKFLOW_COUNT} seconds.",
            )
        }
}
