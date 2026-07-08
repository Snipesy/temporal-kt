package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.fireTimerJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cancelling a sleeping coroutine must emit a CancelTimer command, otherwise the
 * server-side timer leaks: it fires later, causing a pointless workflow task
 * (and the pending map entry lingers). Mirrors the Python SDK, which emits
 * cancel_timer from the sleep future's done-callback when cancelled.
 */
class TimerCancellationTest {
    private val serializer = CompositePayloadSerializer.default()

    /**
     * Main coroutine sleeps 1s (timer A); a child coroutine sleeps 10s via
     * [WorkflowContext.sleep] (timer B). When timer A fires, the child is cancelled -
     * the SDK must emit CancelTimer for timer B.
     */
    @Workflow("CancelSleepWorkflow")
    class CancelSleepWorkflow {
        var sleeper: Job? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleeper = launch { sleep(10.seconds) }
            sleep(1.seconds)
            sleeper!!.cancel()
            sleeper!!.join()
            awaitCondition { false }
            return "done"
        }
    }

    /**
     * Same shape but the child uses kotlinx [delay] (the continuation-based timer path).
     */
    @Workflow("CancelDelayWorkflow")
    class CancelDelayWorkflow {
        var sleeper: Job? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleeper = launch { delay(10.seconds) }
            sleep(1.seconds)
            sleeper!!.cancel()
            sleeper!!.join()
            awaitCondition { false }
            return "done"
        }
    }

    private data class Fixture(
        val executor: WorkflowExecutor,
        val runId: String,
    )

    private suspend inline fun <reified T : Any> start(
        workflowType: String,
    ): Pair<Fixture, List<coresdk.workflow_commands.WorkflowCommands.WorkflowCommand>> {
        val workflow = T::class.constructors.first().call()
        val runMethod = T::class.members.first { it.name == "run" } as KFunction<*>
        val methodInfo =
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
        val executor = createTestWorkflowExecutor(runId = runId, methodInfo = methodInfo, serializer = serializer)
        val completion =
            executor
                .activate(
                    createActivation(
                        runId = runId,
                        jobs = listOf(initializeWorkflowJob(workflowType = workflowType)),
                        isReplaying = false,
                    ),
                ).completion
        assertTrue(completion.hasSuccessful())
        return Fixture(executor, runId) to completion.successful.commandsList
    }

    private suspend fun fireTimer(
        fixture: Fixture,
        seq: Int,
    ): List<coresdk.workflow_commands.WorkflowCommands.WorkflowCommand> {
        val completion =
            fixture.executor
                .activate(
                    createActivation(
                        runId = fixture.runId,
                        jobs = listOf(fireTimerJob(seq)),
                        isReplaying = false,
                    ),
                ).completion
        assertTrue(completion.hasSuccessful())
        return completion.successful.commandsList
    }

    @Test
    fun `cancelling a sleeping coroutine emits CancelTimer`() =
        runTest {
            val (fixture, initCommands) = start<CancelSleepWorkflow>("CancelSleepWorkflow")

            val timers = initCommands.filter { it.hasStartTimer() }
            assertEquals(2, timers.size, "expected two StartTimer commands, got: $initCommands")
            val shortTimer = timers.single { it.startTimer.startToFireTimeout.seconds == 1L }
            val longTimer = timers.single { it.startTimer.startToFireTimeout.seconds == 10L }

            val commands = fireTimer(fixture, shortTimer.startTimer.seq)

            val cancelCommands = commands.filter { it.hasCancelTimer() }
            assertEquals(1, cancelCommands.size, "expected a CancelTimer command, got: $commands")
            assertEquals(longTimer.startTimer.seq, cancelCommands.single().cancelTimer.seq)
        }

    @Test
    fun `cancelling a delayed coroutine emits CancelTimer`() =
        runTest {
            val (fixture, initCommands) = start<CancelDelayWorkflow>("CancelDelayWorkflow")

            val timers = initCommands.filter { it.hasStartTimer() }
            assertEquals(2, timers.size, "expected two StartTimer commands, got: $initCommands")
            val shortTimer = timers.single { it.startTimer.startToFireTimeout.seconds == 1L }
            val longTimer = timers.single { it.startTimer.startToFireTimeout.seconds == 10L }

            val commands = fireTimer(fixture, shortTimer.startTimer.seq)

            val cancelCommands = commands.filter { it.hasCancelTimer() }
            assertEquals(1, cancelCommands.size, "expected a CancelTimer command, got: $commands")
            assertEquals(longTimer.startTimer.seq, cancelCommands.single().cancelTimer.seq)
        }

    @Workflow("SleepSummaryWorkflow")
    class SleepSummaryWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(5.seconds, summary = "waiting for approval window")
            return "done"
        }
    }

    @Test
    fun `sleep summary is carried as user metadata on the StartTimer command`() =
        runTest {
            val (_, initCommands) = start<SleepSummaryWorkflow>("SleepSummaryWorkflow")

            val timerCommand = initCommands.single { it.hasStartTimer() }
            assertTrue(timerCommand.hasUserMetadata(), "StartTimer should carry user metadata")
            assertTrue(
                timerCommand.userMetadata.summary.data
                    .toStringUtf8()
                    .contains("waiting for approval window"),
            )
        }

    @Workflow("AwaitConditionSummaryWorkflow")
    class AwaitConditionSummaryWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition(timeout = 5.seconds, timeoutSummary = "waiting for flag") { false }
            return "done"
        }
    }

    @Test
    fun `awaitCondition timeoutSummary is carried as user metadata on the timeout timer`() =
        runTest {
            val (_, initCommands) = start<AwaitConditionSummaryWorkflow>("AwaitConditionSummaryWorkflow")

            val timerCommand = initCommands.single { it.hasStartTimer() }
            assertTrue(timerCommand.hasUserMetadata(), "timeout timer should carry user metadata")
            assertTrue(
                timerCommand.userMetadata.summary.data
                    .toStringUtf8()
                    .contains("waiting for flag"),
            )
        }

    @Workflow("AwaitConditionNoSummaryWorkflow")
    class AwaitConditionNoSummaryWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition(timeout = 5.seconds) { false }
            return "done"
        }
    }

    @Test
    fun `awaitCondition without summary emits a plain timeout timer`() =
        runTest {
            val (_, initCommands) = start<AwaitConditionNoSummaryWorkflow>("AwaitConditionNoSummaryWorkflow")

            val timerCommand = initCommands.single { it.hasStartTimer() }
            assertTrue(!timerCommand.hasUserMetadata(), "no summary means no user metadata")
        }

    @Test
    fun `workflow completion does not emit CancelTimer for pending timers`() =
        runTest {
            // A workflow that completes while a background sleep is still pending must NOT
            // emit CancelTimer commands - histories recorded before/after this behavior
            // must replay identically, and the server drops timers on completion anyway.
            @Workflow("CompleteWithPendingTimerWorkflow")
            class CompleteWithPendingTimerWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String {
                    launch { sleep(10.seconds) }
                    sleep(1.seconds)
                    return "done"
                }
            }

            val workflow = CompleteWithPendingTimerWorkflow()
            val runMethod =
                CompleteWithPendingTimerWorkflow::class
                    .members
                    .first { it.name == "run" } as KFunction<*>
            val methodInfo =
                WorkflowMethodInfo(
                    workflowType = "CompleteWithPendingTimerWorkflow",
                    runMethod = runMethod,
                    workflowClass = CompleteWithPendingTimerWorkflow::class,
                    instanceFactory = { workflow },
                    parameterTypes = emptyList(),
                    returnType = typeOf<String>(),
                    hasContextReceiver = true,
                    isSuspend = true,
                )
            val runId = "test-run-${UUID.randomUUID()}"
            val executor = createTestWorkflowExecutor(runId = runId, methodInfo = methodInfo, serializer = serializer)
            val initCompletion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs = listOf(initializeWorkflowJob(workflowType = "CompleteWithPendingTimerWorkflow")),
                            isReplaying = false,
                        ),
                    ).completion
            val shortTimerSeq =
                initCompletion.successful.commandsList
                    .single { it.hasStartTimer() && it.startTimer.startToFireTimeout.seconds == 1L }
                    .startTimer.seq

            val completion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs = listOf(fireTimerJob(shortTimerSeq)),
                            isReplaying = false,
                        ),
                    ).completion

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            assertTrue(commands.any { it.hasCompleteWorkflowExecution() }, "expected workflow completion: $commands")
            assertTrue(commands.none { it.hasCancelTimer() }, "must not cancel timers on completion: $commands")
        }
}
