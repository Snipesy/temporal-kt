package com.surrealdev.temporal.application

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for dispatcher configuration at application and task-queue levels.
 *
 * These tests verify that:
 * - Workflow dispatchers can be configured per task queue
 * - Activity dispatchers can be configured per task queue
 * - Application-level dispatcher configuration works
 * - Different dispatcher types (single thread, IO, Unconfined) work correctly
 */
@Tag("integration")
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherConfigurationTest {
    companion object {
        // Thread tracking for verification
        val workflowThreads = CopyOnWriteArrayList<String>()
        val activityThreads = CopyOnWriteArrayList<String>()

        fun clearThreadTracking() {
            workflowThreads.clear()
            activityThreads.clear()
        }
    }

    /**
     * Workflow that records which thread it runs on (no arguments).
     */
    @Workflow("ThreadTrackingWorkflow")
    class ThreadTrackingWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val threadName = Thread.currentThread().name
            workflowThreads.add(threadName)
            return "workflow-on-$threadName"
        }
    }

    /**
     * Activity that records which thread it runs on (no arguments).
     */
    class ThreadTrackingActivity {
        @Activity("recordThread")
        suspend fun ActivityContext.recordThread(): String {
            val threadName = Thread.currentThread().name
            activityThreads.add(threadName)
            return "activity-on-$threadName"
        }
    }

    /**
     * Workflow that calls an activity and tracks threads (no arguments).
     */
    @Workflow("WorkflowWithActivity")
    class WorkflowWithActivity {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val workflowThread = Thread.currentThread().name
            workflowThreads.add(workflowThread)

            val activityResult =
                startActivity<String>(
                    activityType = "recordThread",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()

            return "workflow($workflowThread) -> $activityResult"
        }
    }

    @Test
    fun `workflow runs with default dispatcher`() =
        runTemporalTest {
            clearThreadTracking()
            val taskQueue = "test-default-dispatcher-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    // No dispatcher configuration - uses default
                    workflow(ThreadTrackingWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ThreadTrackingWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertTrue(result.startsWith("workflow-on-"))
            assertEquals(1, workflowThreads.size)
            // Default dispatcher uses DefaultDispatcher threads
            assertTrue(
                workflowThreads[0].contains("DefaultDispatcher") ||
                    workflowThreads[0].contains("worker"),
                "Expected DefaultDispatcher thread, got: ${workflowThreads[0]}",
            )
        }

    @Test
    fun `workflow runs on single thread dispatcher when configured`() =
        runTemporalTest {
            clearThreadTracking()
            val taskQueue = "test-single-thread-${UUID.randomUUID()}"
            val singleThreadDispatcher = newSingleThreadContext("TestWorkflowThread")

            try {
                application {
                    taskQueue(taskQueue) {
                        workflowDispatcher = singleThreadDispatcher
                        workflow(ThreadTrackingWorkflow())
                    }
                }

                val client = client()

                // Run multiple workflows to verify they all use the same thread
                repeat(3) { i ->
                    val handle =
                        client.startWorkflow<String>(
                            workflowType = "ThreadTrackingWorkflow",
                            taskQueue = taskQueue,
                            workflowId = "wf-$taskQueue-$i",
                        )
                    handle.result(timeout = 30.seconds)
                }

                assertEquals(3, workflowThreads.size)
                // All workflows should run on the same named thread
                workflowThreads.forEach { thread ->
                    assertTrue(
                        thread.contains("TestWorkflowThread"),
                        "Expected TestWorkflowThread, got: $thread",
                    )
                }
            } finally {
                singleThreadDispatcher.close()
            }
        }

    @Test
    fun `activity runs on IO dispatcher when configured`() =
        runTemporalTest {
            clearThreadTracking()
            val taskQueue = "test-io-activity-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    activityDispatcher = Dispatchers.IO
                    workflow(WorkflowWithActivity())
                    activity(ThreadTrackingActivity())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "WorkflowWithActivity",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertTrue(result.contains("activity-on-"))
            assertEquals(1, activityThreads.size)
            // IO dispatcher uses worker threads
            assertTrue(
                activityThreads[0].contains("DefaultDispatcher") ||
                    activityThreads[0].contains("worker"),
                "Expected IO dispatcher thread, got: ${activityThreads[0]}",
            )
        }

    @Test
    fun `activity runs on single thread dispatcher when configured`() =
        runTemporalTest {
            clearThreadTracking()
            val taskQueue = "test-single-thread-activity-${UUID.randomUUID()}"
            val singleThreadDispatcher = newSingleThreadContext("TestActivityThread")

            try {
                application {
                    taskQueue(taskQueue) {
                        activityDispatcher = singleThreadDispatcher
                        workflow(WorkflowWithActivity())
                        activity(ThreadTrackingActivity())
                    }
                }

                val client = client()

                // Run multiple workflows to verify activities use the same thread
                repeat(3) { i ->
                    val handle =
                        client.startWorkflow<String>(
                            workflowType = "WorkflowWithActivity",
                            taskQueue = taskQueue,
                            workflowId = "wf-activity-$taskQueue-$i",
                        )
                    handle.result(timeout = 30.seconds)
                }

                assertEquals(3, activityThreads.size)
                // All activities should run on the same named thread
                activityThreads.forEach { thread ->
                    assertTrue(
                        thread.contains("TestActivityThread"),
                        "Expected TestActivityThread, got: $thread",
                    )
                }
            } finally {
                singleThreadDispatcher.close()
            }
        }

    @Test
    fun `workflow and activity can have different dispatchers`() =
        runTemporalTest {
            clearThreadTracking()
            val taskQueue = "test-different-dispatchers-${UUID.randomUUID()}"
            val workflowDispatcher = newSingleThreadContext("WorkflowOnly")
            val activityDispatcher = newSingleThreadContext("ActivityOnly")

            try {
                application {
                    taskQueue(taskQueue) {
                        this.workflowDispatcher = workflowDispatcher
                        this.activityDispatcher = activityDispatcher
                        workflow(WorkflowWithActivity())
                        activity(ThreadTrackingActivity())
                    }
                }

                val client = client()
                val handle =
                    client.startWorkflow<String>(
                        workflowType = "WorkflowWithActivity",
                        taskQueue = taskQueue,
                    )

                val result = handle.result(timeout = 30.seconds)

                assertTrue(result.contains("workflow(") && result.contains("activity-on-"))
                assertEquals(1, workflowThreads.size)
                assertEquals(1, activityThreads.size)

                // Verify they ran on different threads
                assertTrue(
                    workflowThreads[0].contains("WorkflowOnly"),
                    "Workflow should run on WorkflowOnly thread, got: ${workflowThreads[0]}",
                )
                assertTrue(
                    activityThreads[0].contains("ActivityOnly"),
                    "Activity should run on ActivityOnly thread, got: ${activityThreads[0]}",
                )
            } finally {
                workflowDispatcher.close()
                activityDispatcher.close()
            }
        }

    @Test
    fun `multiple task queues can have different dispatchers`() =
        runTemporalTest {
            clearThreadTracking()
            val taskQueue1 = "test-queue-1-${UUID.randomUUID()}"
            val taskQueue2 = "test-queue-2-${UUID.randomUUID()}"
            val dispatcher1 = newSingleThreadContext("Queue1Thread")
            val dispatcher2 = newSingleThreadContext("Queue2Thread")

            try {
                application {
                    taskQueue(taskQueue1) {
                        workflowDispatcher = dispatcher1
                        workflow(ThreadTrackingWorkflow())
                    }
                    taskQueue(taskQueue2) {
                        workflowDispatcher = dispatcher2
                        workflow(ThreadTrackingWorkflow())
                    }
                }

                val client = client()

                // Run workflow on queue 1
                val handle1 =
                    client.startWorkflow<String>(
                        workflowType = "ThreadTrackingWorkflow",
                        taskQueue = taskQueue1,
                        workflowId = "wf-q1-${UUID.randomUUID()}",
                    )
                handle1.result(timeout = 30.seconds)

                // Run workflow on queue 2
                val handle2 =
                    client.startWorkflow<String>(
                        workflowType = "ThreadTrackingWorkflow",
                        taskQueue = taskQueue2,
                        workflowId = "wf-q2-${UUID.randomUUID()}",
                    )
                handle2.result(timeout = 30.seconds)

                assertEquals(2, workflowThreads.size)
                assertTrue(
                    workflowThreads[0].contains("Queue1Thread"),
                    "First workflow should run on Queue1Thread, got: ${workflowThreads[0]}",
                )
                assertTrue(
                    workflowThreads[1].contains("Queue2Thread"),
                    "Second workflow should run on Queue2Thread, got: ${workflowThreads[1]}",
                )
            } finally {
                dispatcher1.close()
                dispatcher2.close()
            }
        }
}
