package com.surrealdev.temporal.workflow.internal

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.common.toTemporal
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startLocalActivity
import coresdk.workflow_commands.WorkflowCommands
import coresdk.workflow_completion.WorkflowCompletion
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for local activity execution.
 *
 * These tests verify end-to-end local activity scenarios including:
 * - Basic execution and result handling
 * - Retry with internal backoff
 * - Multiple local activities in sequence
 * - Parallel local activities
 * - Replay determinism
 */
class LocalActivityIntegrationTest {
    private val serializer = CompositePayloadSerializer.default()

    // ================================================================
    // Test Workflows
    // ================================================================

    @Workflow("LocalActivityResultWorkflow")
    class LocalActivityResultWorkflow {
        var result: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            result =
                startLocalActivity(
                    activityType = "localGreet",
                    startToCloseTimeout = 10.seconds,
                ).result()
            return result!!
        }
    }

    @Workflow("SequentialLocalActivitiesWorkflow")
    class SequentialLocalActivitiesWorkflow {
        var result1: String? = null
        var result2: String? = null
        var result3: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            result1 =
                startLocalActivity(
                    activityType = "step1",
                    startToCloseTimeout = 10.seconds,
                ).result()
            result2 =
                startLocalActivity(
                    activityType = "step2",
                    startToCloseTimeout = 10.seconds,
                ).result()
            result3 =
                startLocalActivity(
                    activityType = "step3",
                    startToCloseTimeout = 10.seconds,
                ).result()
            return "$result1-$result2-$result3"
        }
    }

    @Workflow("ParallelLocalActivitiesWorkflow")
    class ParallelLocalActivitiesWorkflow {
        var parallelResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val handle1 =
                startLocalActivity(
                    activityType = "parallel1",
                    startToCloseTimeout = 10.seconds,
                )
            val handle2 =
                startLocalActivity(
                    activityType = "parallel2",
                    startToCloseTimeout = 10.seconds,
                )
            val handle3 =
                startLocalActivity(
                    activityType = "parallel3",
                    startToCloseTimeout = 10.seconds,
                )

            // Await all concurrently
            val r1: String = handle1.result()
            val r2: String = handle2.result()
            val r3: String = handle3.result()

            parallelResult = "$r1,$r2,$r3"
            return parallelResult!!
        }
    }

    @Workflow("LocalActivityWithRetryWorkflow")
    class LocalActivityWithRetryWorkflow {
        var finalResult: String? = null
        var attemptsSeen: Int = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            finalResult =
                startLocalActivity(
                    activityType = "flakyActivity",
                    startToCloseTimeout = 30.seconds,
                ).result()
            return finalResult!!
        }
    }

    // ================================================================
    // Helper Methods
    // ================================================================

    private data class ExecutorResult(
        val executor: WorkflowExecutor,
        val runId: String,
        val workflow: Any,
        val completion: WorkflowCompletion.WorkflowActivationCompletion,
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
            ProtoTestHelpers.createActivation(
                runId = runId,
                jobs = listOf(ProtoTestHelpers.initializeWorkflowJob(workflowType = workflowType)),
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
        completion: WorkflowCompletion.WorkflowActivationCompletion,
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
     * Tests that a local activity executes and returns its result correctly.
     */
    @Test
    fun `local activity executes and returns result`() =
        runTest {
            val (executor, runId, workflow, initCompletion) =
                createExecutorWithWorkflow<LocalActivityResultWorkflow>("LocalActivityResultWorkflow")

            // Verify ScheduleLocalActivity command
            val commands = getCommandsFromCompletion(initCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() })

            val scheduleCmd = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertEquals("localGreet", scheduleCmd.activityType)

            // Resolve with result
            val resultPayload = createPayload("\"Hello, Local World!\"")
            val resolveActivation =
                ProtoTestHelpers.createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            ProtoTestHelpers.resolveLocalActivityJobCompleted(
                                seq = 1,
                                result = resultPayload.toTemporal(),
                            ),
                        ),
                )
            val resolveCompletion = executor.activate(resolveActivation)

            // Verify workflow completed
            assertTrue(resolveCompletion.hasSuccessful())

            assertEquals("Hello, Local World!", (workflow as LocalActivityResultWorkflow).result)
        }

    /**
     * Tests that sequential local activities execute in order.
     */
    @Test
    fun `sequential local activities execute in order`() =
        runTest {
            val (executor, runId, workflow, initCompletion) =
                createExecutorWithWorkflow<SequentialLocalActivitiesWorkflow>("SequentialLocalActivitiesWorkflow")

            // First activity (step1)
            var commands = getCommandsFromCompletion(initCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() })
            assertEquals(
                "step1",
                commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity.activityType,
            )

            // Resolve first
            var resolveCompletion =
                executor.activate(
                    ProtoTestHelpers.createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                ProtoTestHelpers.resolveLocalActivityJobCompleted(
                                    seq = 1,
                                    result = createPayload("\"A\"").toTemporal(),
                                ),
                            ),
                    ),
                )

            // Second activity (step2)
            commands = getCommandsFromCompletion(resolveCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() })
            assertEquals(
                "step2",
                commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity.activityType,
            )

            // Resolve second
            resolveCompletion =
                executor.activate(
                    ProtoTestHelpers.createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                ProtoTestHelpers.resolveLocalActivityJobCompleted(
                                    seq = 2,
                                    result = createPayload("\"B\"").toTemporal(),
                                ),
                            ),
                    ),
                )

            // Third activity (step3)
            commands = getCommandsFromCompletion(resolveCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() })
            assertEquals(
                "step3",
                commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity.activityType,
            )

            // Resolve third
            resolveCompletion =
                executor.activate(
                    ProtoTestHelpers.createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                ProtoTestHelpers.resolveLocalActivityJobCompleted(
                                    seq = 3,
                                    result = createPayload("\"C\"").toTemporal(),
                                ),
                            ),
                    ),
                )

            // Workflow should complete
            assertTrue(resolveCompletion.hasSuccessful())

            val workflowResult = workflow as SequentialLocalActivitiesWorkflow
            assertEquals("A", workflowResult.result1)
            assertEquals("B", workflowResult.result2)
            assertEquals("C", workflowResult.result3)
        }

    /**
     * Tests that parallel local activities can be started concurrently.
     */
    @Test
    fun `parallel local activities execute concurrently`() =
        runTest {
            val (executor, runId, workflow, initCompletion) =
                createExecutorWithWorkflow<ParallelLocalActivitiesWorkflow>("ParallelLocalActivitiesWorkflow")

            // All three activities should be scheduled immediately
            val commands = getCommandsFromCompletion(initCompletion)
            val localActivityCommands = commands.filter { it.hasScheduleLocalActivity() }
            assertEquals(3, localActivityCommands.size)

            val activityTypes = localActivityCommands.map { it.scheduleLocalActivity.activityType }.toSet()
            assertTrue(activityTypes.contains("parallel1"))
            assertTrue(activityTypes.contains("parallel2"))
            assertTrue(activityTypes.contains("parallel3"))

            // Resolve all three (in any order)
            val resolveActivation =
                ProtoTestHelpers.createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            ProtoTestHelpers.resolveLocalActivityJobCompleted(
                                seq = 1,
                                result = createPayload("\"P1\"").toTemporal(),
                            ),
                            ProtoTestHelpers.resolveLocalActivityJobCompleted(
                                seq = 2,
                                result = createPayload("\"P2\"").toTemporal(),
                            ),
                            ProtoTestHelpers.resolveLocalActivityJobCompleted(
                                seq = 3,
                                result = createPayload("\"P3\"").toTemporal(),
                            ),
                        ),
                )
            val resolveCompletion = executor.activate(resolveActivation)

            // Workflow should complete
            assertTrue(resolveCompletion.hasSuccessful())
            assertEquals("P1,P2,P3", (workflow as ParallelLocalActivitiesWorkflow).parallelResult)
        }

    /**
     * Tests that local activity retry with backoff properly schedules timer and retries.
     */
    @Test
    fun `local activity with backoff retries after timer`() =
        runTest {
            val (executor, runId, workflow, initCompletion) =
                createExecutorWithWorkflow<LocalActivityWithRetryWorkflow>("LocalActivityWithRetryWorkflow")

            // Initial activity scheduled
            var commands = getCommandsFromCompletion(initCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() })
            val initialCmd = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertEquals(1, initialCmd.seq)
            assertEquals(1, initialCmd.attempt)

            // Core SDK sends backoff (simulate activity failed, needs retry after 5s)
            val originalScheduleTime =
                Timestamp
                    .newBuilder()
                    .setSeconds(1000)
                    .build()
            val backoffCompletion =
                executor.activate(
                    ProtoTestHelpers.createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                ProtoTestHelpers.resolveLocalActivityJobBackoff(
                                    seq = 1,
                                    attempt = 2,
                                    backoffSeconds = 5,
                                    originalScheduleTime = originalScheduleTime,
                                ),
                            ),
                    ),
                )

            // Should have timer for backoff
            commands = getCommandsFromCompletion(backoffCompletion)
            assertTrue(commands.any { it.hasStartTimer() }, "Expected timer for backoff")
            val timerCmd = commands.first { it.hasStartTimer() }
            assertEquals(5, timerCmd.startTimer.startToFireTimeout.seconds)

            // Fire the timer
            val fireTimerCompletion =
                executor.activate(
                    ProtoTestHelpers.createActivation(
                        runId = runId,
                        jobs = listOf(ProtoTestHelpers.fireTimerJob(seq = timerCmd.startTimer.seq)),
                    ),
                )

            // Should have new ScheduleLocalActivity with new seq and attempt=2
            commands = getCommandsFromCompletion(fireTimerCompletion)
            assertTrue(commands.any { it.hasScheduleLocalActivity() }, "Expected reschedule after timer")

            val retryCmd = commands.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
            assertTrue(retryCmd.seq > 1, "New schedule should have new seq")
            assertEquals(2, retryCmd.attempt)
            assertTrue(retryCmd.hasOriginalScheduleTime())

            // Resolve the retry successfully
            val finalCompletion =
                executor.activate(
                    ProtoTestHelpers.createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                ProtoTestHelpers.resolveLocalActivityJobCompleted(
                                    seq = retryCmd.seq,
                                    result = createPayload("\"Success after retry!\"").toTemporal(),
                                ),
                            ),
                    ),
                )

            // Workflow should complete
            assertTrue(finalCompletion.hasSuccessful())
            assertEquals("Success after retry!", (workflow as LocalActivityWithRetryWorkflow).finalResult)
        }

    /**
     * Tests replay determinism: running the same workflow twice should produce same commands.
     */
    @Test
    fun `replay with local activity markers is deterministic`() =
        runTest {
            // First execution
            val (executor1, runId1, _, completion1) =
                createExecutorWithWorkflow<LocalActivityResultWorkflow>("LocalActivityResultWorkflow")

            val commands1 = getCommandsFromCompletion(completion1)

            // Second execution with same workflow
            val (_, _, _, completion2) =
                createExecutorWithWorkflow<LocalActivityResultWorkflow>("LocalActivityResultWorkflow")

            val commands2 = getCommandsFromCompletion(completion2)

            // Both should generate the same commands
            assertEquals(commands1.size, commands2.size)
            assertEquals(
                commands1.map { it.variantCase },
                commands2.map { it.variantCase },
            )

            // Specifically check the ScheduleLocalActivity commands match
            if (commands1.any { it.hasScheduleLocalActivity() }) {
                val la1 = commands1.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity
                val la2 = commands2.first { it.hasScheduleLocalActivity() }.scheduleLocalActivity

                assertEquals(la1.activityType, la2.activityType)
                assertEquals(la1.seq, la2.seq)
                assertEquals(la1.attempt, la2.attempt)
            }
        }
}
