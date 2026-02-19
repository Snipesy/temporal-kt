package com.surrealdev.temporal.worker.integration

import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.RetryPolicy
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.testing.TemporalTestApplicationBuilder
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests verifying that java.lang.Error thrown from activities and workflows
 * causes the application to shut down after reporting the failure to Temporal.
 *
 * This matches the Java SDK behavior: Errors are reported for visibility, then the worker
 * shuts down since the JVM may be in a compromised state.
 */
@Tag("integration")
class FatalErrorShutdownIntegrationTest {
    // ================================================================
    // Test Activities
    // ================================================================

    class FatalErrorActivities {
        companion object {
            val errorThrown = AtomicBoolean(false)
        }

        @Activity("throwInternalError")
        fun throwInternalError(): String {
            errorThrown.set(true)
            throw InternalError("simulated class verification failure")
        }
    }

    // ================================================================
    // Test Workflows
    // ================================================================

    @Workflow("ActivityInternalErrorWorkflow")
    class ActivityInternalErrorWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startActivity(
                    activityType = "throwInternalError",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 1),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                "activity_failed:${e.applicationFailure?.type ?: "unknown"}"
            }
    }

    @Workflow("WorkflowThrowsErrorDirectly")
    class WorkflowThrowsErrorDirectly {
        @WorkflowRun
        @Suppress("TooGenericExceptionThrown")
        suspend fun WorkflowContext.run(): String = throw InternalError("simulated verify error from workflow code")
    }

    // ================================================================
    // Integration Tests
    // ================================================================

    @Test
    fun `activity throwing Error reports failure then shuts down application`() =
        runTemporalTest(timeSkipping = false, timeout = 30.seconds) {
            val taskQueue = "test-fatal-activity-${UUID.randomUUID()}"
            FatalErrorActivities.errorThrown.set(false)

            application {
                taskQueue(taskQueue) {
                    workflow<ActivityInternalErrorWorkflow>()
                    activity(FatalErrorActivities())
                }
            }

            val client = client()

            // Start a workflow that triggers the InternalError-throwing activity.
            // Don't await the result — application.close() races with the client
            // and may close the core client before result() completes.
            client.startWorkflow(
                workflowType = "ActivityInternalErrorWorkflow",
                taskQueue = taskQueue,
            )

            // The activity should have thrown, and the application should shut down.
            awaitShutdown(taskQueue)
            assertTrue(FatalErrorActivities.errorThrown.get(), "Activity should have executed")
        }

    @Test
    fun `workflow throwing Error reports failure then shuts down application`() =
        runTemporalTest(timeSkipping = false, timeout = 30.seconds) {
            val taskQueue = "test-fatal-workflow-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<WorkflowThrowsErrorDirectly>()
                    activity(FatalErrorActivities())
                }
            }

            val client = client()

            // Start a workflow that throws InternalError directly from workflow code.
            // The error will be caught by WorkflowExecutor, reported as a workflow task
            // failure to core, and fatalError will trigger application shutdown.
            client.startWorkflow(
                workflowType = "WorkflowThrowsErrorDirectly",
                taskQueue = taskQueue,
            )

            // The application should initiate shutdown after the fatal Error.
            awaitShutdown(taskQueue)
        }

    /**
     * Polls [application.isWorkerShuttingDown] until it returns true or timeout is reached.
     */
    private suspend fun TemporalTestApplicationBuilder.awaitShutdown(taskQueue: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!application.isWorkerShuttingDown(taskQueue)) {
            check(System.currentTimeMillis() < deadline) {
                "Worker for $taskQueue did not shut down within 10 seconds"
            }
            delay(50.milliseconds)
        }
        assertTrue(
            application.isWorkerShuttingDown(taskQueue),
            "Worker should be shutting down after fatal Error",
        )
    }
}
