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
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for condition-based workflow operations including awaitCondition and timeouts.
 */
@Tag("integration")
class ConditionDeterminismTest {
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
                    workflow<ConditionWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ConditionWorkflow",
                    taskQueue = taskQueue,
                )

            val result: Int = handle.result(timeout = 30.seconds)

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
                    workflow<SequentialConditionsWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "SequentialConditionsWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)

            assertEquals("phase1,phase2,phase3", result)

            handle.assertHistory {
                completed()
                timerCount(3)
            }
        }

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
                    workflow<ConditionAfterStateChangeWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ConditionAfterStateChangeWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)

            assertEquals("item1,item2,item3", result)

            handle.assertHistory {
                completed()
                timerCount(3)
            }
        }

    // ================================================================
    // awaitCondition with Timeout Tests
    // ================================================================

    /**
     * Workflow that demonstrates awaitCondition timeout functionality.
     * The condition will never be true, so the timeout should fire.
     */
    @Workflow("AwaitConditionTimeoutWorkflow")
    class AwaitConditionTimeoutWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                // Wait for a condition that will never be true, with a short timeout
                awaitCondition(
                    timeout = 100.milliseconds,
                    timeoutSummary = "waiting for approval",
                ) { false }
                "condition met"
            } catch (e: com.surrealdev.temporal.workflow.WorkflowConditionTimeoutException) {
                "timeout: ${e.summary}"
            }
    }

    @Test
    fun `workflow with awaitCondition timeout catches exception`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-await-timeout-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<AwaitConditionTimeoutWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "AwaitConditionTimeoutWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("timeout: waiting for approval", result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow where the condition is satisfied before the timeout.
     */
    @Workflow("ConditionBeforeTimeoutWorkflow")
    class ConditionBeforeTimeoutWorkflow {
        private var approved = false

        @Signal("approve")
        fun approve() {
            approved = true
        }

        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                awaitCondition(
                    timeout = 10.seconds,
                    timeoutSummary = "waiting for approval signal",
                ) { approved }
                "approved"
            } catch (e: com.surrealdev.temporal.workflow.WorkflowConditionTimeoutException) {
                "timeout"
            }
    }

    @Test
    fun `workflow with awaitCondition completes when condition met before timeout`() =
        runTemporalTest {
            val taskQueue = "test-condition-before-timeout-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ConditionBeforeTimeoutWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ConditionBeforeTimeoutWorkflow",
                    taskQueue = taskQueue,
                )

            // Send signal to satisfy condition before timeout
            handle.signal("approve")

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("approved", result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow that demonstrates awaitCondition with no timeout (backwards compatibility).
     */
    @Workflow("AwaitConditionNoTimeoutWorkflow")
    class AwaitConditionNoTimeoutWorkflow {
        private var done = false

        @Signal("complete")
        fun complete() {
            done = true
        }

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Wait indefinitely (no timeout) - original behavior
            awaitCondition { done }
            return "done"
        }
    }

    @Test
    fun `workflow with awaitCondition without timeout waits indefinitely`() =
        runTemporalTest {
            val taskQueue = "test-await-no-timeout-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<AwaitConditionNoTimeoutWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "AwaitConditionNoTimeoutWorkflow",
                    taskQueue = taskQueue,
                )

            // Signal to complete
            handle.signal("complete")

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("done", result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow where the condition is already true when awaitCondition is called.
     */
    @Workflow("ConditionAlreadyTrueWorkflow")
    class ConditionAlreadyTrueWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Condition is already true
            awaitCondition(
                timeout = 1.seconds,
                timeoutSummary = "should not timeout",
            ) { true }
            return "immediate"
        }
    }

    @Test
    fun `workflow with awaitCondition returns immediately when condition already true`() =
        runTemporalTest {
            val taskQueue = "test-condition-already-true-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ConditionAlreadyTrueWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ConditionAlreadyTrueWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("immediate", result)

            // Should complete without any timers being created
            handle.assertHistory {
                completed()
                timerCount(0)
            }
        }
}
