package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.testing.ProtoTestHelpers.cancelWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.fireTimerJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveActivityJobCompleted
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivationJob
import coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Cancelling the coroutine that awaits an activity result must request cancellation of the
 * activity itself, the way cancelling a Java SDK CancellationScope or a Python task awaiting
 * an activity handle does. Callers should not need to call `handle.cancel()` by hand on every
 * cancellation path (timeouts, `select`, structured concurrency, workflow cancellation).
 */
class ActivityScopeCancellationTest {
    private val serializer = CompositePayloadSerializer.default()

    private class Fixture(
        val executor: WorkflowExecutor,
        val runId: String,
    ) {
        suspend fun activate(vararg jobs: WorkflowActivationJob): List<WorkflowCommand> {
            val completion =
                executor.activate(createActivation(runId = runId, jobs = jobs.toList(), isReplaying = false)).completion
            assertTrue(completion.hasSuccessful(), "expected success, got $completion")
            return completion.successful.commandsList
        }
    }

    private inline fun <reified T : Any> fixture(instance: T): Fixture {
        val workflowType = T::class.simpleName!!
        val runMethod = T::class.members.first { it.name == "run" } as KFunction<*>
        val methodInfo =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                workflowClass = T::class,
                instanceFactory = { instance },
                parameterTypes = emptyList(),
                returnType = typeOf<String>(),
                hasContextReceiver = true,
                isSuspend = true,
            )
        val runId = "test-run-${UUID.randomUUID()}"
        val executor = createTestWorkflowExecutor(runId = runId, methodInfo = methodInfo, serializer = serializer)
        return Fixture(executor, runId)
    }

    private fun List<WorkflowCommand>.activitySeq(): Int = single { it.hasScheduleActivity() }.scheduleActivity.seq

    private fun List<WorkflowCommand>.timerSeq(): Int = single { it.hasStartTimer() }.startTimer.seq

    // ------------------------------------------------------------------

    class CancelAwaitingChild {
        suspend fun WorkflowContext.run(): String {
            val handle = startActivity("SlowActivity", ActivityOptions(startToCloseTimeout = 5.minutes))
            val awaiting = launch { handle.result<String>() }
            sleep(1.seconds)
            awaiting.cancel()
            awaiting.join()
            awaitCondition { false }
            return "unreachable"
        }
    }

    @Test
    fun `cancelling the coroutine awaiting an activity requests cancellation of the activity`() =
        runTest {
            val f = fixture(CancelAwaitingChild())
            val init = f.activate(initializeWorkflowJob(workflowType = "CancelAwaitingChild"))
            val activitySeq = init.activitySeq()

            val commands = f.activate(fireTimerJob(init.timerSeq()))

            val cancels = commands.filter { it.hasRequestCancelActivity() }
            assertEquals(1, cancels.size, "expected one RequestCancelActivity, got: $commands")
            assertEquals(activitySeq, cancels.single().requestCancelActivity.seq)
        }

    class AwaitsActivityInMain {
        suspend fun WorkflowContext.run(): String =
            startActivity("SlowActivity", ActivityOptions(startToCloseTimeout = 5.minutes)).result()
    }

    @Test
    fun `workflow cancellation while awaiting an activity requests cancellation of the activity`() =
        runTest {
            val f = fixture(AwaitsActivityInMain())
            val init = f.activate(initializeWorkflowJob(workflowType = "AwaitsActivityInMain"))
            val activitySeq = init.activitySeq()

            val commands = f.activate(cancelWorkflowJob("shutting down"))

            val cancels = commands.filter { it.hasRequestCancelActivity() }
            assertEquals(1, cancels.size, "expected one RequestCancelActivity, got: $commands")
            assertEquals(activitySeq, cancels.single().requestCancelActivity.seq)
            assertTrue(commands.any { it.hasCancelWorkflowExecution() }, "workflow still reports cancelled: $commands")
        }

    class CompletesWithPendingActivity {
        suspend fun WorkflowContext.run(): String {
            val handle = startActivity("SlowActivity", ActivityOptions(startToCloseTimeout = 5.minutes))
            launch { handle.result<String>() }
            sleep(1.seconds)
            return "done"
        }
    }

    @Test
    fun `workflow completion does not request cancellation of activities awaited by leftover coroutines`() =
        runTest {
            // Teardown must not emit commands: histories recorded before/after must replay identically
            val f = fixture(CompletesWithPendingActivity())
            val init = f.activate(initializeWorkflowJob(workflowType = "CompletesWithPendingActivity"))

            val commands = f.activate(fireTimerJob(init.timerSeq()))

            assertTrue(commands.any { it.hasCompleteWorkflowExecution() }, "expected completion: $commands")
            assertTrue(commands.none { it.hasRequestCancelActivity() }, "no cancel on teardown: $commands")
        }

    class CancelAfterResolution {
        suspend fun WorkflowContext.run(): String {
            val handle = startActivity("SlowActivity", ActivityOptions(startToCloseTimeout = 5.minutes))
            val awaiting = launch { handle.result<String>() }
            sleep(1.seconds)
            awaiting.cancel()
            awaiting.join()
            awaitCondition { false }
            return "unreachable"
        }
    }

    @Test
    fun `cancelling after the activity already resolved emits nothing`() =
        runTest {
            val f = fixture(CancelAfterResolution())
            val init = f.activate(initializeWorkflowJob(workflowType = "CancelAfterResolution"))

            // Activity resolves first; the child finishes normally
            val afterResolve = f.activate(resolveActivityJobCompleted(init.activitySeq(), serializer.serialize("ok")))
            assertTrue(afterResolve.none { it.hasRequestCancelActivity() })

            // Timer fires, the (already completed) child is "cancelled": nothing to send
            val afterTimer = f.activate(fireTimerJob(init.timerSeq()))
            assertTrue(afterTimer.none { it.hasRequestCancelActivity() }, "got: $afterTimer")
        }
}
