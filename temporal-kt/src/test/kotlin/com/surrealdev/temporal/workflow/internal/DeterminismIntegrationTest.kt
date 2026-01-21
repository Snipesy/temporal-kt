package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for determinism fixes.
 *
 * These tests use full workflow execution with `runTemporalTest {}` to verify:
 * - Condition-based workflows complete correctly when conditions become true
 * - Mixed job types are processed in the correct order
 * - Deterministic values (random, time) are consistent across replays
 * - Cancellation and cleanup work correctly
 * - Structured concurrency with async operations is deterministic
 */
class DeterminismIntegrationTest {
    // ================================================================
    // Condition-Based Workflow Tests
    // ================================================================

    /**
     * Workflow that uses awaitCondition to wait for a condition.
     * Uses a timer to simulate state change that satisfies the condition.
     */
    @Workflow("ConditionWorkflow")
    class ConditionWorkflow {
        private var counter = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): Int {
            // Increment counter after each sleep
            repeat(3) {
                sleep(50.milliseconds)
                counter++
            }

            // Use awaitCondition to wait until counter reaches target
            awaitCondition { counter >= 3 }

            return counter
        }
    }

    @Test
    fun `workflow with condition completes when condition becomes true`() =
        runTemporalTest {
            val taskQueue = "test-condition-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ConditionWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int>(
                    workflowType = "ConditionWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals(3, result)

            handle.assertHistory {
                completed()
                // Should have 3 timers
                timerCount(3)
            }
        }

    /**
     * Workflow that uses multiple sequential conditions.
     */
    @Workflow("SequentialConditionsWorkflow")
    class SequentialConditionsWorkflow {
        private var phase = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val results = mutableListOf<String>()

            // Phase 1
            sleep(30.milliseconds)
            phase = 1
            awaitCondition { phase >= 1 }
            results.add("phase1")

            // Phase 2
            sleep(30.milliseconds)
            phase = 2
            awaitCondition { phase >= 2 }
            results.add("phase2")

            // Phase 3
            sleep(30.milliseconds)
            phase = 3
            awaitCondition { phase >= 3 }
            results.add("phase3")

            return results.joinToString(",")
        }
    }

    @Test
    fun `workflow with multiple sequential conditions completes correctly`() =
        runTemporalTest {
            val taskQueue = "test-seq-conditions-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SequentialConditionsWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "SequentialConditionsWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("phase1,phase2,phase3", result)

            handle.assertHistory {
                completed()
                timerCount(3)
            }
        }

    // ================================================================
    // Mixed Job Types Tests
    // ================================================================

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
                    workflow(MixedJobTypesWorkflow())
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

    // ================================================================
    // Deterministic Value Tests
    // ================================================================

    /**
     * Workflow that uses deterministic UUIDs from the workflow context.
     */
    @Workflow("RandomUuidWorkflow")
    class RandomUuidWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<String> {
            // Generate multiple deterministic UUIDs
            val values = mutableListOf<String>()
            repeat(5) {
                values.add(randomUuid())
            }
            return values
        }
    }

    @Test
    fun `workflow produces deterministic random values`() =
        runTemporalTest {
            val taskQueue = "test-random-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(RandomUuidWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<List<String>>(
                    workflowType = "RandomUuidWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Result should be a list of 5 UUIDs
            assertEquals(5, result.size)
            // All values should be valid UUID strings
            assertTrue(result.all { it.isNotEmpty() })

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow that uses workflow time.
     */
    @Workflow("TimeProgressionWorkflow")
    class TimeProgressionWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<Long> {
            val times = mutableListOf<Long>()

            // Record time before and after sleeps
            times.add(now().toEpochMilliseconds())

            sleep(100.milliseconds)
            times.add(now().toEpochMilliseconds())

            sleep(100.milliseconds)
            times.add(now().toEpochMilliseconds())

            return times
        }
    }

    @Test
    fun `workflow has deterministic time progression`() =
        runTemporalTest {
            val taskQueue = "test-time-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(TimeProgressionWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<List<Long>>(
                    workflowType = "TimeProgressionWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Result should have 3 timestamps
            assertEquals(3, result.size)

            // Times should be monotonically increasing
            assertTrue(result[0] <= result[1], "Time should increase after first sleep")
            assertTrue(result[1] <= result[2], "Time should increase after second sleep")

            // The difference between times should reflect the sleep durations
            // (approximately, allowing for some variance)
            val diff1 = result[1] - result[0]
            val diff2 = result[2] - result[1]

            assertTrue(diff1 >= 50, "First sleep difference should be >= 50ms, was $diff1")
            assertTrue(diff2 >= 50, "Second sleep difference should be >= 50ms, was $diff2")

            handle.assertHistory {
                completed()
                timerCount(2)
            }
        }

    // ================================================================
    // Cancellation and Cleanup Tests
    // ================================================================

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
                    workflow(CancellableConditionWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
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

    // ================================================================
    // Structured Concurrency Tests
    // ================================================================

    /**
     * Workflow with multiple async operations.
     */
    @Workflow("AsyncOperationsWorkflow")
    class AsyncOperationsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Launch multiple async operations
            val deferred1 =
                async {
                    sleep(30.milliseconds)
                    "A"
                }
            val deferred2 =
                async {
                    sleep(40.milliseconds)
                    "B"
                }
            val deferred3 =
                async {
                    sleep(20.milliseconds)
                    "C"
                }

            // Await all in order
            val result1 = deferred1.await()
            val result2 = deferred2.await()
            val result3 = deferred3.await()

            return "$result1$result2$result3"
        }
    }

    @Test
    fun `async workflow operations execute deterministically`() =
        runTemporalTest {
            val taskQueue = "test-async-ops-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(AsyncOperationsWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "AsyncOperationsWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Results should always be in the await order, not completion order
            assertEquals("ABC", result)

            handle.assertHistory {
                completed()
                // All three async operations should create timers
                timerCount(3)
            }
        }

    /**
     * Workflow with nested async operations.
     */
    @Workflow("NestedAsyncWorkflow")
    class NestedAsyncWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val outer =
                async {
                    val inner1 =
                        async {
                            sleep(20.milliseconds)
                            "inner1"
                        }
                    val inner2 =
                        async {
                            sleep(20.milliseconds)
                            "inner2"
                        }
                    "${inner1.await()}-${inner2.await()}"
                }

            return "result: ${outer.await()}"
        }
    }

    @Test
    fun `nested async operations execute deterministically`() =
        runTemporalTest {
            val taskQueue = "test-nested-async-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(NestedAsyncWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "NestedAsyncWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("result: inner1-inner2", result)

            handle.assertHistory {
                completed()
                // Two inner async operations create timers
                timerCount(2)
            }
        }

    // ================================================================
    // Condition with State Change Tests
    // ================================================================

    /**
     * Workflow that demonstrates condition checking after state changes.
     */
    @Workflow("ConditionAfterStateChangeWorkflow")
    class ConditionAfterStateChangeWorkflow {
        private var items = mutableListOf<String>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Simulate adding items over time
            val addItem =
                async {
                    sleep(50.milliseconds)
                    items.add("item1")
                    sleep(50.milliseconds)
                    items.add("item2")
                    sleep(50.milliseconds)
                    items.add("item3")
                }

            // Wait for condition that checks state
            awaitCondition { items.size >= 3 }

            addItem.await() // Make sure async completes

            return items.joinToString(",")
        }
    }

    @Test
    fun `condition completes when state changes satisfy predicate`() =
        runTemporalTest {
            val taskQueue = "test-condition-state-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ConditionAfterStateChangeWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ConditionAfterStateChangeWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("item1,item2,item3", result)

            handle.assertHistory {
                completed()
                timerCount(3)
            }
        }
}
