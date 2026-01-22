package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [WorkflowCoroutineDispatcher].
 *
 * These tests verify that the custom dispatcher correctly captures workflow
 * commands by running workflows synchronously on the activation thread until
 * suspension points.
 */
class WorkflowCoroutineDispatcherTimeTest {
    /**
     * Simple workflow that sleeps once - validates basic timer command capture.
     */
    @Workflow("SingleTimerWorkflow")
    class SingleTimerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(50.milliseconds)
            return "Timer fired"
        }
    }

    /**
     * Workflow with multiple sequential timers - validates command capture
     * across multiple suspension points.
     */
    @Workflow("MultipleTimersWorkflow")
    class MultipleTimersWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(count: Int): String {
            repeat(count) {
                sleep(25.milliseconds)
            }
            return "All $count timers fired"
        }
    }

    /**
     * Workflow that uses async/await within the workflow context.
     * This validates that the custom dispatcher handles concurrent
     * coroutines correctly within the deterministic execution model.
     */
    @Workflow("AsyncWorkflow")
    class AsyncWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Launch concurrent work using the workflow's CoroutineScope
            val deferred1 =
                async {
                    sleep(30.milliseconds)
                    "A"
                }
            val deferred2 =
                async {
                    sleep(30.milliseconds)
                    "B"
                }

            val results = awaitAll(deferred1, deferred2)
            return results.joinToString(",")
        }
    }

    /**
     * Workflow that does computation before and after a timer.
     * Validates that non-suspending code executes correctly.
     */
    @Workflow("ComputeAndSleepWorkflow")
    class ComputeAndSleepWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: Int): Int {
            val doubled = input * 2
            sleep(25.milliseconds)
            val tripled = doubled * 3
            return tripled
        }
    }

    /**
     * Workflow that returns immediately without any suspension.
     * Validates that workflows without timers/activities complete correctly.
     */
    @Workflow("ImmediateReturnWorkflow")
    class ImmediateReturnWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(value: String): String = "Immediate: $value"
    }

    /**
     * Workflow with a long timer that would take hours in real time.
     * Used to test time-skipping functionality.
     */
    @Workflow("LongTimerWorkflow")
    class LongTimerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // This would take 1 hour in real time, but with time-skipping
            // it should complete almost instantly
            sleep(1.hours)
            return "Waited 1 hour"
        }
    }

    /**
     * Workflow with multiple long timers for time-skipping test.
     */
    @Workflow("MultipleLongTimersWorkflow")
    class MultipleLongTimersWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(30.seconds)
            sleep(1.hours)
            sleep(30.seconds)
            return "Completed all timers"
        }
    }

    /**
     * Workflow that uses kotlinx.coroutines.delay() instead of WorkflowContext.sleep().
     * This now works correctly - delay() is intercepted and creates a durable timer.
     */
    @Workflow("DelayWorkflow")
    class DelayWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // delay() is intercepted and creates a durable timer
            delay(100)
            return "Delay completed!"
        }
    }

    // ================================================================
    // Delay Interception Tests
    // ================================================================

    @Test
    fun `delay is intercepted and creates durable timer`() =
        runTemporalTest {
            val taskQueue = "test-dispatcher-delay-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(DelayWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "DelayWorkflow",
                    taskQueue = taskQueue,
                )

            // delay() should work correctly now - it creates a durable timer
            val result = handle.result(timeout = 30.seconds)
            assertEquals("Delay completed!", result)

            // Verify a timer was created
            handle.assertHistory {
                completed()
                hasTimerStarted()
                hasTimerFired()
                timerCount(1)
            }
        }

    // ================================================================
    // Timer Tests
    // ================================================================

    @Test
    fun `single timer command is captured and workflow completes`() =
        runTemporalTest {
            val taskQueue = "test-dispatcher-single-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SingleTimerWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "SingleTimerWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Timer fired", result)

            handle.assertHistory {
                completed()
                hasTimerStarted()
                hasTimerFired()
                timerCount(1)
            }
        }

    @Test
    fun `multiple sequential timers are captured correctly`() =
        runTemporalTest {
            val taskQueue = "test-dispatcher-multi-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(MultipleTimersWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, Int>(
                    workflowType = "MultipleTimersWorkflow",
                    taskQueue = taskQueue,
                    arg = 3,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("All 3 timers fired", result)

            handle.assertHistory {
                completed()
                timerCount(3)
            }
        }

    @Test
    fun `async operations within workflow context work correctly`() =
        runTemporalTest {
            val taskQueue = "test-dispatcher-async-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(AsyncWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "AsyncWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            // Both async branches should complete
            assertTrue(result.contains("A"))
            assertTrue(result.contains("B"))

            handle.assertHistory {
                completed()
                // Should have timers from both async branches
                timerCount(2)
            }
        }

    @Test
    fun `computation before and after suspension works correctly`() =
        runTemporalTest {
            val taskQueue = "test-dispatcher-compute-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ComputeAndSleepWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int, Int>(
                    workflowType = "ComputeAndSleepWorkflow",
                    taskQueue = taskQueue,
                    arg = 5,
                )

            val result = handle.result(timeout = 30.seconds)
            // 5 * 2 = 10, then sleep, then 10 * 3 = 30
            assertEquals(30, result)

            handle.assertHistory {
                completed()
                timerCount(1)
            }
        }

    @Test
    fun `workflow without suspension completes immediately`() =
        runTemporalTest {
            val taskQueue = "test-dispatcher-immediate-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ImmediateReturnWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, String>(
                    workflowType = "ImmediateReturnWorkflow",
                    taskQueue = taskQueue,
                    arg = "test",
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("Immediate: test", result)

            handle.assertHistory {
                completed()
                timerCount(0)
                noFailedActivities()
            }
        }

    // ================================================================
    // Time-Skipping Tests
    // ================================================================

    @Test
    fun `time skipping allows 1 hour timer to complete instantly`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-timeskip-long-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(LongTimerWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "LongTimerWorkflow",
                    taskQueue = taskQueue,
                )

            // With time-skipping, a 1-hour timer should complete almost instantly
            // We give it 30 seconds of wall-clock time which should be plenty
            val result = handle.result(timeout = 30.seconds)
            assertEquals("Waited 1 hour", result)

            handle.assertHistory {
                completed()
                hasTimerStarted()
                hasTimerFired()
                timerCount(1)
            }
        }

    @Test
    fun `time skipping handles multiple long timers`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-timeskip-multi-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(MultipleLongTimersWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "MultipleLongTimersWorkflow",
                    taskQueue = taskQueue,
                )

            // Total workflow time: 30s + 1h + 30s = over 1 hour
            // With time-skipping, should complete almost instantly
            val result = handle.result(timeout = 30.seconds)
            assertEquals("Completed all timers", result)

            handle.assertHistory {
                completed()
                timerCount(3)
            }
        }
}
