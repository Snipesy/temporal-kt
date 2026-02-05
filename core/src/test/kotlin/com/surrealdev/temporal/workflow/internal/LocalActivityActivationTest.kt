package com.surrealdev.temporal.workflow.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.common.exceptions.WorkflowActivityCancelledException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityTimeoutException
import com.surrealdev.temporal.common.toTemporal
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.fireTimerJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobBackoff
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobCancelled
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobCompleted
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobFailed
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobFailedWithDetails
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveLocalActivityJobTimeout
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.LocalActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startLocalActivity
import coresdk.workflow_commands.WorkflowCommands
import coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion
import io.temporal.api.common.v1.Payload
import io.temporal.api.enums.v1.TimeoutType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
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
                startLocalActivity(
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
                startLocalActivity(
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
                startLocalActivity(
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
                startLocalActivity(
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
                startLocalActivity(
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
            startLocalActivity(
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
                startLocalActivity(
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

    @Workflow("MultipleBackoffWorkflow")
    class MultipleBackoffWorkflow {
        var handleResult: String? = null
        var attemptsObserved = mutableListOf<Int>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            handleResult =
                startLocalActivity(
                    activityType = "flakyActivity",
                    startToCloseTimeout = 60.seconds,
                ).result()
            return "done"
        }
    }

    @Workflow("TimeoutLocalActivityWorkflow")
    class TimeoutLocalActivityWorkflow {
        var caughtException: Exception? = null
        var timeoutType: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                startLocalActivity(
                    activityType = "slowActivity",
                    startToCloseTimeout = 1.seconds,
                ).result()
            } catch (e: WorkflowActivityTimeoutException) {
                caughtException = e
                timeoutType = e.timeoutType.name
            } catch (e: Exception) {
                caughtException = e
            }
            return "done"
        }
    }

    @Workflow("FailureDetailsWorkflow")
    class FailureDetailsWorkflow {
        var caughtException: Exception? = null
        var failureMessage: String? = null
        var failureType: String? = null
        var applicationErrorType: String? = null
        var hasCause: Boolean = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                startLocalActivity(
                    activityType = "detailedFailingActivity",
                    startToCloseTimeout = 10.seconds,
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                caughtException = e
                failureMessage = e.message
                failureType = e.failureType
                applicationErrorType = e.applicationFailure?.type
                hasCause = e.cause != null
            } catch (e: Exception) {
                caughtException = e
            }
            return "done"
        }
    }

    @Workflow("UnitReturnWorkflow")
    class UnitReturnWorkflow {
        var completed = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            startLocalActivity(
                activityType = "voidActivity",
                startToCloseTimeout = 10.seconds,
            ).result<Unit>()
            completed = true
            return "done"
        }
    }

    @Serializable
    data class ComplexResult(
        val id: Int,
        val name: String,
        val tags: List<String>,
    )

    @Workflow("ComplexReturnWorkflow")
    class ComplexReturnWorkflow {
        var result: ComplexResult? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            result =
                startLocalActivity(
                    activityType = "complexActivity",
                    startToCloseTimeout = 10.seconds,
                ).result()
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
                    jobs = listOf(resolveLocalActivityJobCompleted(seq = 1, result = resultPayload.toTemporal())),
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
                    jobs =
                        listOf(
                            resolveLocalActivityJobCompleted(
                                seq = newScheduleCmd.seq,
                                result = resultPayload.toTemporal(),
                            ),
                        ),
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
            val (executor, runId, _, initCompletion) =
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
            assertTrue(workflow.caughtException is WorkflowActivityCancelledException)
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

    // ================================================================
    // New Unit Tests
    // ================================================================

    /**
     * Tests multiple consecutive backoffs (attempt 2 → 3 → 4).
     * Verifies that each reschedule gets a new sequence number and
     * the attempt number increments correctly.
     */
    @Test
    fun `multiple consecutive backoffs use new sequence numbers`() =
        runTest {
            val (executor, runId, _, initCompletion) =
                createExecutorWithWorkflow<MultipleBackoffWorkflow>("MultipleBackoffWorkflow")

            // Initial ScheduleLocalActivity with seq=1, attempt=1
            var commands = getCommandsFromCompletion(initCompletion)
            assertEquals(1, commands.size)
            assertTrue(commands[0].hasScheduleLocalActivity())
            assertEquals(1, commands[0].scheduleLocalActivity.seq)
            assertEquals(1, commands[0].scheduleLocalActivity.attempt)

            val originalScheduleTime =
                com.google.protobuf.Timestamp
                    .newBuilder()
                    .setSeconds(1000)
                    .build()

            // First backoff: attempt 2
            val backoff1Completion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                resolveLocalActivityJobBackoff(
                                    seq = 1,
                                    attempt = 2,
                                    backoffSeconds = 1,
                                    originalScheduleTime = originalScheduleTime,
                                ),
                            ),
                    ),
                )

            commands = getCommandsFromCompletion(backoff1Completion)
            val timer1Seq = commands.first { it.hasStartTimer() }.startTimer.seq

            // Fire timer 1
            val fireTimer1Completion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs = listOf(fireTimerJob(seq = timer1Seq)),
                    ),
                )

            commands = getCommandsFromCompletion(fireTimer1Completion)
            val schedule2 = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertTrue(schedule2.seq > 1, "Second schedule should have seq > 1")
            assertEquals(2, schedule2.attempt, "Second schedule should have attempt=2")
            assertTrue(schedule2.hasOriginalScheduleTime(), "Should preserve originalScheduleTime")

            // Second backoff: attempt 3
            val backoff2Completion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                resolveLocalActivityJobBackoff(
                                    seq = schedule2.seq,
                                    attempt = 3,
                                    backoffSeconds = 2,
                                    originalScheduleTime = originalScheduleTime,
                                ),
                            ),
                    ),
                )

            commands = getCommandsFromCompletion(backoff2Completion)
            val timer2Seq = commands.first { it.hasStartTimer() }.startTimer.seq

            // Fire timer 2
            val fireTimer2Completion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs = listOf(fireTimerJob(seq = timer2Seq)),
                    ),
                )

            commands = getCommandsFromCompletion(fireTimer2Completion)
            val schedule3 = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertTrue(schedule3.seq > schedule2.seq, "Third schedule should have seq > second")
            assertEquals(3, schedule3.attempt, "Third schedule should have attempt=3")

            // Third backoff: attempt 4
            val backoff3Completion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                resolveLocalActivityJobBackoff(
                                    seq = schedule3.seq,
                                    attempt = 4,
                                    backoffSeconds = 3,
                                    originalScheduleTime = originalScheduleTime,
                                ),
                            ),
                    ),
                )

            commands = getCommandsFromCompletion(backoff3Completion)
            val timer3Seq = commands.first { it.hasStartTimer() }.startTimer.seq

            // Fire timer 3
            val fireTimer3Completion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs = listOf(fireTimerJob(seq = timer3Seq)),
                    ),
                )

            commands = getCommandsFromCompletion(fireTimer3Completion)
            val schedule4 = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertTrue(schedule4.seq > schedule3.seq, "Fourth schedule should have seq > third")
            assertEquals(4, schedule4.attempt, "Fourth schedule should have attempt=4")

            // Finally complete
            val resultPayload = createPayload("\"Success after 4 attempts\"")
            executor.activate(
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            resolveLocalActivityJobCompleted(seq = schedule4.seq, result = resultPayload.toTemporal()),
                        ),
                ),
            )
        }

    /**
     * Tests that timeout resolution produces WorkflowActivityTimeoutException.
     */
    @Test
    fun `local activity timeout resolution throws WorkflowActivityTimeoutException`() =
        runTest {
            val (executor, runId, workflow, _) =
                createExecutorWithWorkflow<TimeoutLocalActivityWorkflow>("TimeoutLocalActivityWorkflow")

            // Resolve with timeout
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            resolveLocalActivityJobTimeout(
                                seq = 1,
                                timeoutType = TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE,
                                message = "Activity timed out after 1s",
                            ),
                        ),
                )
            executor.activate(resolveActivation)

            val wf = workflow as TimeoutLocalActivityWorkflow
            assertNotNull(wf.caughtException, "Should have caught exception")
            assertTrue(
                wf.caughtException is WorkflowActivityTimeoutException,
                "Should be WorkflowActivityTimeoutException",
            )
            assertEquals("START_TO_CLOSE", wf.timeoutType, "Should capture timeout type")
        }

    /**
     * Tests that failure details (message, type, cause) are properly propagated.
     */
    @Test
    fun `local activity failure preserves error details`() =
        runTest {
            val (executor, runId, workflow, _) =
                createExecutorWithWorkflow<FailureDetailsWorkflow>("FailureDetailsWorkflow")

            // Resolve with detailed failure
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            resolveLocalActivityJobFailedWithDetails(
                                seq = 1,
                                message = "Database connection failed",
                                errorType = "DatabaseException",
                                causeMessage = "Connection refused on port 5432",
                            ),
                        ),
                )
            executor.activate(resolveActivation)

            val wf = workflow as FailureDetailsWorkflow
            assertNotNull(wf.caughtException, "Should have caught exception")
            assertTrue(
                wf.caughtException is WorkflowActivityFailureException,
                "Should be WorkflowActivityFailureException",
            )
            assertNotNull(wf.failureMessage, "Should have failure message")
            assertTrue(
                wf.failureMessage!!.contains("Database connection failed"),
                "Should preserve error message",
            )
            assertEquals("ApplicationFailure", wf.failureType, "Should identify as ApplicationFailure")
            assertEquals("DatabaseException", wf.applicationErrorType, "Should preserve application error type")
            assertTrue(wf.hasCause, "Should have cause")
        }

    /**
     * Tests that Unit return type works correctly.
     */
    @Test
    fun `local activity with Unit return type completes successfully`() =
        runTest {
            val (executor, runId, workflow, initCompletion) =
                createExecutorWithWorkflow<UnitReturnWorkflow>("UnitReturnWorkflow")

            // Verify command generated
            val commands = getCommandsFromCompletion(initCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() })

            // Resolve with empty payload (Unit)
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(resolveLocalActivityJobCompleted(seq = 1)),
                )
            executor.activate(resolveActivation)

            assertTrue((workflow as UnitReturnWorkflow).completed, "Workflow should complete with Unit result")
        }

    /**
     * Tests that complex return types are properly deserialized.
     */
    @Test
    fun `local activity with complex return type deserializes correctly`() =
        runTest {
            val (executor, runId, workflow, _) =
                createExecutorWithWorkflow<ComplexReturnWorkflow>("ComplexReturnWorkflow")

            // Create a complex result payload
            val complexJson = """{"id":42,"name":"test-item","tags":["tag1","tag2","tag3"]}"""
            val resultPayload = createPayload(complexJson)

            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(resolveLocalActivityJobCompleted(seq = 1, result = resultPayload.toTemporal())),
                )
            executor.activate(resolveActivation)

            val wf = workflow as ComplexReturnWorkflow
            assertNotNull(wf.result, "Should have result")
            assertEquals(42, wf.result?.id)
            assertEquals("test-item", wf.result?.name)
            assertEquals(listOf("tag1", "tag2", "tag3"), wf.result?.tags)
        }

    /**
     * Tests that backoff + replay produces deterministic commands.
     * Run the same activation sequence twice and verify same commands.
     */
    @Test
    fun `local activity backoff is deterministic on replay`() =
        runTest {
            // First execution
            val (executor1, runId1, _, initCompletion1) =
                createExecutorWithWorkflow<RetryableLocalActivityWorkflow>("RetryableLocalActivityWorkflow")

            val commands1 = getCommandsFromCompletion(initCompletion1)

            // Second execution (simulating replay)
            val (executor2, runId2, _, initCompletion2) =
                createExecutorWithWorkflow<RetryableLocalActivityWorkflow>("RetryableLocalActivityWorkflow")

            val commands2 = getCommandsFromCompletion(initCompletion2)

            // Both should generate same initial commands
            assertEquals(commands1.size, commands2.size, "Should have same number of commands")
            assertEquals(
                commands1.map { it.variantCase },
                commands2.map { it.variantCase },
                "Command types should match",
            )

            // Now simulate backoff on both
            val originalScheduleTime =
                com.google.protobuf.Timestamp
                    .newBuilder()
                    .setSeconds(1000)
                    .build()

            val backoffCompletion1 =
                executor1.activate(
                    createActivation(
                        runId = runId1,
                        jobs =
                            listOf(
                                resolveLocalActivityJobBackoff(
                                    seq = 1,
                                    attempt = 2,
                                    backoffSeconds = 5,
                                    originalScheduleTime = originalScheduleTime,
                                ),
                            ),
                    ),
                )

            val backoffCompletion2 =
                executor2.activate(
                    createActivation(
                        runId = runId2,
                        jobs =
                            listOf(
                                resolveLocalActivityJobBackoff(
                                    seq = 1,
                                    attempt = 2,
                                    backoffSeconds = 5,
                                    originalScheduleTime = originalScheduleTime,
                                ),
                            ),
                    ),
                )

            val backoffCmds1 = getCommandsFromCompletion(backoffCompletion1)
            val backoffCmds2 = getCommandsFromCompletion(backoffCompletion2)

            assertEquals(
                backoffCmds1.map { it.variantCase },
                backoffCmds2.map { it.variantCase },
                "Backoff commands should be deterministic",
            )

            // Both should have StartTimer with same duration
            val timer1 = backoffCmds1.first { it.hasStartTimer() }.startTimer
            val timer2 = backoffCmds2.first { it.hasStartTimer() }.startTimer
            assertEquals(
                timer1.startToFireTimeout.seconds,
                timer2.startToFireTimeout.seconds,
                "Timer durations should match",
            )
        }

    /**
     * Tests scheduleToClose timeout resolution.
     */
    @Test
    fun `local activity scheduleToClose timeout throws correct exception`() =
        runTest {
            val (executor, runId, workflow, _) =
                createExecutorWithWorkflow<TimeoutLocalActivityWorkflow>("TimeoutLocalActivityWorkflow")

            // Resolve with scheduleToClose timeout
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            resolveLocalActivityJobTimeout(
                                seq = 1,
                                timeoutType = TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
                                message = "Schedule to close timeout exceeded",
                            ),
                        ),
                )
            executor.activate(resolveActivation)

            val wf = workflow as TimeoutLocalActivityWorkflow
            assertNotNull(wf.caughtException, "Should have caught exception")
            assertTrue(wf.caughtException is WorkflowActivityTimeoutException)
            assertEquals("SCHEDULE_TO_CLOSE", wf.timeoutType)
        }
}
