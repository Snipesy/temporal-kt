package com.surrealdev.temporal.application

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for virtual thread execution of workflows and activities.
 *
 * These tests verify that:
 * - Workflows run on dedicated virtual threads (named workflow-{taskQueue}-N)
 * - Activities run on dedicated virtual threads (named activity-{taskQueue}-N)
 * - Different task queues have separate virtual thread naming
 */
@Tag("integration")
class DispatcherConfigurationTest {
    companion object {
        // Thread tracking keyed by task queue name to allow parallel test execution
        private val workflowThreadsByQueue = ConcurrentHashMap<String, MutableList<String>>()
        private val activityThreadsByQueue = ConcurrentHashMap<String, MutableList<String>>()

        fun recordWorkflowThread(
            taskQueue: String,
            threadName: String,
        ) {
            workflowThreadsByQueue.computeIfAbsent(taskQueue) { mutableListOf() }.add(threadName)
        }

        fun recordActivityThread(
            taskQueue: String,
            threadName: String,
        ) {
            activityThreadsByQueue.computeIfAbsent(taskQueue) { mutableListOf() }.add(threadName)
        }

        fun getWorkflowThreads(taskQueue: String): List<String> = workflowThreadsByQueue[taskQueue] ?: emptyList()

        fun getActivityThreads(taskQueue: String): List<String> = activityThreadsByQueue[taskQueue] ?: emptyList()

        fun clearThreadTracking(taskQueue: String) {
            workflowThreadsByQueue.remove(taskQueue)
            activityThreadsByQueue.remove(taskQueue)
        }
    }

    /**
     * Workflow that records which thread it runs on.
     * Uses the task queue from context to isolate tracking per test.
     */
    @Workflow("ThreadTrackingWorkflow")
    class ThreadTrackingWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val threadName = Thread.currentThread().name
            recordWorkflowThread(info.taskQueue, threadName)
            return "workflow-on-$threadName"
        }
    }

    /**
     * Activity that records which thread it runs on.
     * Uses the task queue from context to isolate tracking per test.
     */
    class ThreadTrackingActivity {
        @Activity("recordThread")
        fun ActivityContext.recordThread(): String {
            val threadName = Thread.currentThread().name
            recordActivityThread(info.taskQueue, threadName)
            return "activity-on-$threadName"
        }
    }

    /**
     * Workflow that calls an activity and tracks threads.
     */
    @Workflow("WorkflowWithActivity")
    class WorkflowWithActivity {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val workflowThread = Thread.currentThread().name
            recordWorkflowThread(info.taskQueue, workflowThread)

            val activityResult: String =
                startActivity(
                    activityType = "recordThread",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()

            return "workflow($workflowThread) -> $activityResult"
        }
    }

    @Test
    fun `workflow runs on virtual thread`() =
        runTemporalTest {
            val taskQueue = "test-default-dispatcher-${UUID.randomUUID()}"
            clearThreadTracking(taskQueue)

            application {
                taskQueue(taskQueue) {
                    workflow<ThreadTrackingWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ThreadTrackingWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)

            assertTrue(result.startsWith("workflow-on-"))
            val threads = getWorkflowThreads(taskQueue)
            assertEquals(1, threads.size)
            // Workflows run on virtual threads named workflow-{taskQueue}-N
            assertTrue(
                threads[0].contains("workflow-$taskQueue"),
                "Expected virtual thread (workflow-$taskQueue-*), got: ${threads[0]}",
            )
        }

    @Test
    fun `multiple workflows run on virtual threads`() =
        runTemporalTest {
            val taskQueue = "test-multiple-workflows-${UUID.randomUUID()}"
            clearThreadTracking(taskQueue)

            application {
                taskQueue(taskQueue) {
                    workflow<ThreadTrackingWorkflow>()
                }
            }

            val client = client()

            // Run multiple workflows to verify they use virtual threads
            repeat(3) { i ->
                val handle =
                    client.startWorkflow(
                        workflowType = "ThreadTrackingWorkflow",
                        taskQueue = taskQueue,
                        workflowId = "wf-$taskQueue-$i",
                    )
                handle.result<String>(timeout = 30.seconds)
            }

            val threads = getWorkflowThreads(taskQueue)
            assertEquals(3, threads.size)
            // All workflows should run on virtual threads
            threads.forEach { thread ->
                assertTrue(
                    thread.contains("workflow-$taskQueue"),
                    "Expected virtual thread (workflow-$taskQueue-*), got: $thread",
                )
            }
        }

    @Test
    fun `activity runs on virtual thread`() =
        runTemporalTest {
            val taskQueue = "test-activity-virtual-thread-${UUID.randomUUID()}"
            clearThreadTracking(taskQueue)

            application {
                taskQueue(taskQueue) {
                    workflow<WorkflowWithActivity>()
                    activity(ThreadTrackingActivity())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)

            assertTrue(result.contains("activity-on-"))
            val threads = getActivityThreads(taskQueue)
            assertEquals(1, threads.size)
            // Activities run on virtual threads named activity-{taskQueue}-N
            assertTrue(
                threads[0].contains("activity-$taskQueue"),
                "Expected virtual thread (activity-$taskQueue-*), got: ${threads[0]}",
            )
        }

    @Test
    fun `multiple activities run on virtual threads`() =
        runTemporalTest {
            val taskQueue = "test-multiple-activities-${UUID.randomUUID()}"
            clearThreadTracking(taskQueue)

            application {
                taskQueue(taskQueue) {
                    workflow<WorkflowWithActivity>()
                    activity(ThreadTrackingActivity())
                }
            }

            val client = client()

            // Run multiple workflows to verify activities run on virtual threads
            repeat(3) { i ->
                val handle =
                    client.startWorkflow(
                        workflowType = "WorkflowWithActivity",
                        taskQueue = taskQueue,
                        workflowId = "wf-activity-$taskQueue-$i",
                    )
                handle.result<String>(timeout = 30.seconds)
            }

            val threads = getActivityThreads(taskQueue)
            assertEquals(3, threads.size)
            // All activities should run on virtual threads
            threads.forEach { thread ->
                assertTrue(
                    thread.contains("activity-$taskQueue"),
                    "Expected virtual thread (activity-$taskQueue-*), got: $thread",
                )
            }
        }

    @Test
    fun `workflow and activity both run on virtual threads`() =
        runTemporalTest {
            val taskQueue = "test-both-virtual-threads-${UUID.randomUUID()}"
            clearThreadTracking(taskQueue)

            application {
                taskQueue(taskQueue) {
                    workflow<WorkflowWithActivity>()
                    activity(ThreadTrackingActivity())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)

            assertTrue(result.contains("workflow(") && result.contains("activity-on-"))
            val workflowThreads = getWorkflowThreads(taskQueue)
            val activityThreads = getActivityThreads(taskQueue)
            assertEquals(1, workflowThreads.size)
            assertEquals(1, activityThreads.size)

            // Verify workflow runs on virtual thread
            assertTrue(
                workflowThreads[0].contains("workflow-$taskQueue"),
                "Workflow should run on virtual thread (workflow-$taskQueue-*), got: ${workflowThreads[0]}",
            )
            // Verify activity runs on virtual thread
            assertTrue(
                activityThreads[0].contains("activity-$taskQueue"),
                "Activity should run on virtual thread (activity-$taskQueue-*), got: ${activityThreads[0]}",
            )
        }

    @Test
    fun `multiple task queues have separate virtual threads`() =
        runTemporalTest {
            val taskQueue1 = "test-queue-1-${UUID.randomUUID()}"
            val taskQueue2 = "test-queue-2-${UUID.randomUUID()}"
            clearThreadTracking(taskQueue1)
            clearThreadTracking(taskQueue2)

            application {
                taskQueue(taskQueue1) {
                    workflow<ThreadTrackingWorkflow>()
                }
                taskQueue(taskQueue2) {
                    workflow<ThreadTrackingWorkflow>()
                }
            }

            val client = client()

            // Run workflow on queue 1
            val handle1 =
                client.startWorkflow(
                    workflowType = "ThreadTrackingWorkflow",
                    taskQueue = taskQueue1,
                    workflowId = "wf-q1-${UUID.randomUUID()}",
                )
            handle1.result<String>(timeout = 30.seconds)

            // Run workflow on queue 2
            val handle2 =
                client.startWorkflow(
                    workflowType = "ThreadTrackingWorkflow",
                    taskQueue = taskQueue2,
                    workflowId = "wf-q2-${UUID.randomUUID()}",
                )
            handle2.result<String>(timeout = 30.seconds)

            val threads1 = getWorkflowThreads(taskQueue1)
            val threads2 = getWorkflowThreads(taskQueue2)
            assertEquals(1, threads1.size)
            assertEquals(1, threads2.size)

            // Each task queue has its own virtual thread naming
            assertTrue(
                threads1[0].contains("workflow-$taskQueue1"),
                "First workflow should run on virtual thread (workflow-$taskQueue1-*), got: ${threads1[0]}",
            )
            assertTrue(
                threads2[0].contains("workflow-$taskQueue2"),
                "Second workflow should run on virtual thread (workflow-$taskQueue2-*), got: ${threads2[0]}",
            )
        }
}
