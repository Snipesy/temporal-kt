package com.surrealdev.temporal.workflow.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.fireTimerJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobBackoff
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobCancelled
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobCompleted
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobFailed
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.ActivityCancelledException
import com.surrealdev.temporal.workflow.LocalActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.startLocalActivity
import coresdk.workflow_commands.WorkflowCommands
import coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for local activity activation processing.
 *
 * These tests verify:
 * - Local activity resolution handling (completed, failed, cancelled, backoff)
 * - Backoff timer and reschedule flow with NEW sequence numbers
 * - Command generation for ScheduleLocalActivity
 */
class LocalActivityActivationTest {
    private val serializer = KotlinxJsonSerializer()

    // ================================================================
    // Test Workflows
    // ================================================================

    @Workflow("SimpleLocalActivityWorkflow")
    class SimpleLocalActivityWorkflow {
        var localActivityResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            localActivityResult =
                startLocalActivity<String>(
                    activityType = "localGreet",
                    startToCloseTimeout = 10.seconds,
                ).result()
            return "done"
        }
    }

    @Workflow("FailingLocalActivityWorkflow")
    class FailingLocalActivityWorkflow {
        var caughtException: Exception? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                startLocalActivity<String>(
                    activityType = "failingLocalActivity",
                    startToCloseTimeout = 10.seconds,
                ).result()
            } catch (e: Exception) {
                caughtException = e
            }
            return "done"
        }
    }

    @Workflow("RetryableLocalActivityWorkflow")
    class RetryableLocalActivityWorkflow {
        var handleResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            handleResult =
                startLocalActivity<String>(
                    activityType = "retryableLocalActivity",
                    startToCloseTimeout = 10.seconds,
                ).result()
            return "done"
        }
    }

    @Workflow("LocalActivityWithArgsWorkflow")
    class LocalActivityWithArgsWorkflow {
        var handleResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            handleResult =
                startLocalActivity<String, String>(
                    activityType = "localActivityWithArgs",
                    arg = "test-argument",
                    startToCloseTimeout = 10.seconds,
                ).result()
            return "done"
        }
    }

    @Workflow("CancellableLocalActivityWorkflow")
    class CancellableLocalActivityWorkflow {
        var caughtException: Exception? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                startLocalActivity<String>(
                    activityType = "cancellableLocalActivity",
                    startToCloseTimeout = 10.seconds,
                ).result()
            } catch (e: Exception) {
                caughtException = e
            }
            return "done"
        }
    }

    @Workflow("ConfiguredLocalActivityWorkflow")
    class ConfiguredLocalActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val options =
                LocalActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    scheduleToCloseTimeout = 2.minutes,
                    scheduleToStartTimeout = 10.seconds,
                    activityId = "custom-la-id",
                    localRetryThreshold = 90.seconds,
                )
            startLocalActivity<Unit>(
                activityType = "configuredLocalActivity",
                options = options,
            )
            return "done"
        }
    }

    @Workflow("CancellingLocalActivityWorkflow")
    class CancellingLocalActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val handle =
                startLocalActivity<String>(
                    activityType = "longRunningActivity",
                    startToCloseTimeout = 60.seconds,
                )
            handle.cancel("Test cancellation")
            try {
                handle.result()
            } catch (_: Exception) {
            }
            return "done"
        }
    }

    // ================================================================
    // Helper Methods
    // ================================================================

    private data class ExecutorResult(
        val executor: WorkflowExecutor,
        val runId: String,
        val workflow: Any,
        val completion: WorkflowActivationCompletion,
    )

    private suspend inline fun <reified T : Any> createExecutorWithWorkflow(workflowType: String): ExecutorResult {
        val workflow = T::class.constructors.first().call()
        val runMethod =
            T::class
                .members
                .first { it.name == "run" } as KFunction<*>

        val workflowMethodInfo =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                workflowClass = T::class,
                instanceFactory = { workflow },
                parameterTypes = emptyList(),
                returnType = typeOf<String>(),
                hasContextReceiver = true,
                isSuspend = true,
            )

        val runId = "test-run-${UUID.randomUUID()}"
        val executor =
            createTestWorkflowExecutor(
                runId = runId,
                methodInfo = workflowMethodInfo,
                serializer = serializer,
            )

        // Initialize the workflow
        val initActivation =
            createActivation(
                runId = runId,
                jobs = listOf(initializeWorkflowJob(workflowType = workflowType)),
                isReplaying = false,
            )
        val completion = executor.activate(initActivation)

        return ExecutorResult(executor, runId, workflow, completion)
    }

    private fun createPayload(data: String): Payload =
        Payload
            .newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8(data))
            .build()

    private fun getCommandsFromCompletion(
        completion: WorkflowActivationCompletion,
    ): List<WorkflowCommands.WorkflowCommand> =
        if (completion.hasSuccessful()) {
            completion.successful.commandsList
        } else {
            emptyList()
        }

    // ================================================================
    // Tests
    // ================================================================

    /**
     * Tests that a local activity completion is properly resolved.
     */
    @Test
    fun `local activity resolution completed resolves handle`() =
        runTest {
            val (executor, runId, workflow, initCompletion) =
                createExecutorWithWorkflow<SimpleLocalActivityWorkflow>("SimpleLocalActivityWorkflow")

            // Verify the ScheduleLocalActivity command was generated
            val commands = getCommandsFromCompletion(initCompletion)
            assertEquals(1, commands.size)
            assertTrue(commands[0].hasScheduleLocalActivity())

            val scheduleCmd = commands[0].scheduleLocalActivity
            assertEquals(1, scheduleCmd.seq)
            assertEquals("localGreet", scheduleCmd.activityType)
            assertEquals(1, scheduleCmd.attempt) // Initial attempt is 1

            // Resolve the local activity with a completed result
            val resultPayload = createPayload("\"Hello from local activity\"")
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(resolveLocalActivityJobCompleted(seq = 1, result = resultPayload)),
                )
            executor.activate(resolveActivation)

            assertEquals("Hello from local activity", (workflow as SimpleLocalActivityWorkflow).localActivityResult)
        }

    /**
     * Tests that a local activity failure is properly handled.
     */
    @Test
    fun `local activity resolution failed throws exception`() =
        runTest {
            val (executor, runId, workflow, _) =
                createExecutorWithWorkflow<FailingLocalActivityWorkflow>("FailingLocalActivityWorkflow")

            // Resolve with failure
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(resolveLocalActivityJobFailed(seq = 1, message = "Local activity failed!")),
                )
            executor.activate(resolveActivation)

            assertNotNull((workflow as FailingLocalActivityWorkflow).caughtException)
            assertTrue(workflow.caughtException!!.message!!.contains("failed"))
        }

    /**
     * Tests that backoff resolution triggers a timer and reschedule with a NEW sequence number.
     * This is a critical behavior - each ScheduleLocalActivity must have a unique seq.
     */
    @Test
    fun `local activity backoff uses new sequence number`() =
        runTest {
            val (executor, runId, workflow, initCompletion) =
                createExecutorWithWorkflow<RetryableLocalActivityWorkflow>("RetryableLocalActivityWorkflow")

            // Verify initial ScheduleLocalActivity with seq=1
            var commands = getCommandsFromCompletion(initCompletion)
            assertEquals(1, commands.size)
            assertTrue(commands[0].hasScheduleLocalActivity())
            assertEquals(1, commands[0].scheduleLocalActivity.seq)

            // Send backoff resolution (attempt 2, 5s backoff)
            val originalScheduleTime =
                com.google.protobuf.Timestamp
                    .newBuilder()
                    .setSeconds(1000)
                    .build()
            val backoffActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            resolveLocalActivityJobBackoff(
                                seq = 1,
                                attempt = 2,
                                backoffSeconds = 5,
                                originalScheduleTime = originalScheduleTime,
                            ),
                        ),
                )
            val backoffCompletion = executor.activate(backoffActivation)

            // Should have a StartTimer command for the backoff duration
            commands = getCommandsFromCompletion(backoffCompletion)
            assertTrue(commands.any { it.hasStartTimer() }, "Should have StartTimer command for backoff")

            val timerCmd = commands.first { it.hasStartTimer() }
            assertEquals(5, timerCmd.startTimer.startToFireTimeout.seconds)

            // Fire the timer
            val fireTimerActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(fireTimerJob(seq = timerCmd.startTimer.seq)),
                )
            val fireTimerCompletion = executor.activate(fireTimerActivation)

            // Should have a NEW ScheduleLocalActivity with different seq (not 1)
            commands = getCommandsFromCompletion(fireTimerCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() }, "Should have new ScheduleLocalActivity")

            val newScheduleCmd = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertTrue(newScheduleCmd.seq != 1, "New schedule must have different seq than original")
            assertEquals(2, newScheduleCmd.attempt) // Attempt from backoff
            assertTrue(newScheduleCmd.hasOriginalScheduleTime())
            assertEquals("retryableLocalActivity", newScheduleCmd.activityType)

            // Complete the retry
            val resultPayload = createPayload("\"Retry succeeded\"")
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(resolveLocalActivityJobCompleted(seq = newScheduleCmd.seq, result = resultPayload)),
                )
            executor.activate(resolveActivation)

            assertEquals("Retry succeeded", (workflow as RetryableLocalActivityWorkflow).handleResult)
        }

    /**
     * Tests that arguments are preserved when rescheduling after backoff.
     * This is critical - without arguments, the retry would fail.
     */
    @Test
    fun `local activity backoff preserves arguments on reschedule`() =
        runTest {
            val (executor, runId, workflow, initCompletion) =
                createExecutorWithWorkflow<LocalActivityWithArgsWorkflow>("LocalActivityWithArgsWorkflow")

            // Verify initial command has the argument
            var commands = getCommandsFromCompletion(initCompletion)
            val initialCmd = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertEquals(1, initialCmd.argumentsCount, "Initial command should have 1 argument")

            // Send backoff
            val backoffActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            resolveLocalActivityJobBackoff(
                                seq = 1,
                                attempt = 2,
                                backoffSeconds = 1,
                            ),
                        ),
                )
            val backoffCompletion = executor.activate(backoffActivation)

            // Get timer seq and fire it
            commands = getCommandsFromCompletion(backoffCompletion)
            val timerCmd = commands.first { it.hasStartTimer() }
            val fireTimerActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(fireTimerJob(seq = timerCmd.startTimer.seq)),
                )
            val fireTimerCompletion = executor.activate(fireTimerActivation)

            // Verify retry command preserves the argument
            commands = getCommandsFromCompletion(fireTimerCompletion)
            val retryCmd = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertEquals(1, retryCmd.argumentsCount, "Retry command should preserve arguments")
            assertEquals(
                initialCmd.getArguments(0).data,
                retryCmd.getArguments(0).data,
                "Argument data should be preserved on retry",
            )
        }

    /**
     * Tests that cancellation is properly handled.
     */
    @Test
    fun `local activity cancellation resolves with cancelled exception`() =
        runTest {
            val (executor, runId, workflow, _) =
                createExecutorWithWorkflow<CancellableLocalActivityWorkflow>("CancellableLocalActivityWorkflow")

            // Resolve with cancellation
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(resolveLocalActivityJobCancelled(seq = 1)),
                )
            executor.activate(resolveActivation)

            assertNotNull((workflow as CancellableLocalActivityWorkflow).caughtException)
            assertTrue(workflow.caughtException is ActivityCancelledException)
        }

    /**
     * Tests that LocalActivityOptions are properly applied to the command.
     */
    @Test
    fun `local activity options are properly applied to command`() =
        runTest {
            val (_, _, _, initCompletion) =
                createExecutorWithWorkflow<ConfiguredLocalActivityWorkflow>("ConfiguredLocalActivityWorkflow")

            val commands = getCommandsFromCompletion(initCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() }, "Should have ScheduleLocalActivity command")

            val cmd = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertEquals("custom-la-id", cmd.activityId)
            assertEquals("configuredLocalActivity", cmd.activityType)
            assertEquals(30, cmd.startToCloseTimeout.seconds)
            assertEquals(120, cmd.scheduleToCloseTimeout.seconds)
            assertEquals(10, cmd.scheduleToStartTimeout.seconds)
            assertEquals(90, cmd.localRetryThreshold.seconds)
            // Default cancellation type should be WAIT_CANCELLATION_COMPLETED for local activities
            assertEquals(WorkflowCommands.ActivityCancellationType.WAIT_CANCELLATION_COMPLETED, cmd.cancellationType)
        }

    /**
     * Tests that requesting cancel sends the appropriate command.
     */
    @Test
    fun `cancel sends RequestCancelLocalActivity command`() =
        runTest {
            val (_, _, _, initCompletion) =
                createExecutorWithWorkflow<CancellingLocalActivityWorkflow>("CancellingLocalActivityWorkflow")

            val commands = getCommandsFromCompletion(initCompletion)

            // Should have both ScheduleLocalActivity and RequestCancelLocalActivity
            assertTrue(commands.any { it.hasScheduleLocalActivity() })
            assertTrue(commands.any { it.hasRequestCancelLocalActivity() })

            val cancelCmd = commands.first { it.hasRequestCancelLocalActivity() }
            assertEquals(1, cancelCmd.requestCancelLocalActivity.seq)
        }
}
