package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.common.exceptions.WorkflowCancelledException
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.cancelWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.fireTimerJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.signalWorkflowJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.WorkflowContext
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivationJob
import coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Semantics of the workflow coroutine event loop ([WorkflowExecutor.runOnce]) and its
 * interaction with kotlinx.coroutines, driven through raw activations.
 *
 * The Python SDK's `_run_once` is the reference for these behaviors.
 */
class WorkflowCoroutineSemanticsTest {
    private val serializer = CompositePayloadSerializer.default()

    private class Fixture(
        val executor: WorkflowExecutor,
        val runId: String,
    ) {
        suspend fun dispatch(vararg jobs: WorkflowActivationJob): WorkflowDispatchResult =
            executor.activate(createActivation(runId = runId, jobs = jobs.toList(), isReplaying = false))

        suspend fun activate(vararg jobs: WorkflowActivationJob): List<WorkflowCommand> {
            val completion = dispatch(*jobs).completion
            assertTrue(completion.hasSuccessful(), "expected success, got $completion")
            return completion.successful.commandsList
        }

        /**
         * Runs the activation on a helper thread so a regression that blocks the activation
         * thread fails the test instead of hanging the build.
         */
        fun activateWithDeadline(
            vararg jobs: WorkflowActivationJob,
            seconds: Long = 5,
        ): List<WorkflowCommand> {
            var result: List<WorkflowCommand>? = null
            var error: Throwable? = null
            val done = CountDownLatch(1)
            val t =
                thread(isDaemon = true) {
                    try {
                        result = runBlocking { activate(*jobs) }
                    } catch (e: Throwable) {
                        error = e
                    }
                    done.countDown()
                }
            if (!done.await(seconds, TimeUnit.SECONDS)) {
                t.interrupt()
                throw AssertionError("activation did not return within ${seconds}s (activation thread blocked)")
            }
            error?.let { throw it }
            return result!!
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
        return Fixture(
            createTestWorkflowExecutor(runId = runId, methodInfo = methodInfo, serializer = serializer),
            runId,
        )
    }

    private fun List<WorkflowCommand>.timerSeq(seconds: Long): Int =
        single { it.hasStartTimer() && it.startTimer.startToFireTimeout.seconds == seconds }.startTimer.seq

    // ------------------------------------------------------------------
    // Conditions are checked even when the same cycle scheduled a command
    // ------------------------------------------------------------------

    class SignalFlagsAndSchedules {
        var flag = false

        suspend fun WorkflowContext.run(): String {
            setSignalHandlerWithPayloads("go") {
                flag = true
                sleep(10.seconds) // schedules a timer command in the same cycle
            }
            awaitCondition { flag }
            return "resumed"
        }
    }

    @Test
    fun `condition made true by a handler that also schedules a command resumes in the same activation`() =
        runTest {
            val f = fixture(SignalFlagsAndSchedules())
            val init = f.activate(initializeWorkflowJob(workflowType = "SignalFlagsAndSchedules"))
            assertTrue(init.none { it.hasCompleteWorkflowExecution() })

            val commands = f.activate(signalWorkflowJob("go"))
            assertTrue(commands.any { it.hasStartTimer() }, "handler timer expected: $commands")
            assertTrue(
                commands.any { it.hasCompleteWorkflowExecution() },
                "main coroutine should have observed flag==true in this activation, got: $commands",
            )
        }

    // ------------------------------------------------------------------
    // withTimeout-based timers must not emit CancelTimer on terminal completion
    // ------------------------------------------------------------------

    class CompleteWithPendingAwaitConditionTimeout {
        suspend fun WorkflowContext.run(): String {
            launch { awaitCondition(timeout = 10.seconds) { false } }
            sleep(1.seconds)
            return "done"
        }
    }

    @Test
    fun `workflow completion does not emit CancelTimer for a pending awaitCondition timeout`() =
        runTest {
            val f = fixture(CompleteWithPendingAwaitConditionTimeout())
            val init = f.activate(initializeWorkflowJob(workflowType = "CompleteWithPendingAwaitConditionTimeout"))

            val commands = f.activate(fireTimerJob(init.timerSeq(1)))
            assertTrue(commands.any { it.hasCompleteWorkflowExecution() }, "expected completion: $commands")
            assertTrue(commands.none { it.hasCancelTimer() }, "must not cancel timers on completion: $commands")
        }

    // ------------------------------------------------------------------
    // Catching workflow cancellation and returning normally completes the workflow
    // ------------------------------------------------------------------

    class CatchCancelAndReturn {
        suspend fun WorkflowContext.run(): String {
            try {
                awaitCondition { false }
            } catch (e: WorkflowCancelledException) {
                return "cleaned-up"
            }
            return "unreachable"
        }
    }

    @Test
    fun `workflow that catches cancellation and returns normally completes with its return value`() =
        runTest {
            val f = fixture(CatchCancelAndReturn())
            f.activate(initializeWorkflowJob(workflowType = "CatchCancelAndReturn"))

            val commands = f.activate(cancelWorkflowJob("bye"))
            assertFalse(
                commands.any { it.hasCancelWorkflowExecution() },
                "should not be reported as cancelled: $commands",
            )
            val complete = commands.single { it.hasCompleteWorkflowExecution() }
            assertEquals(
                "\"cleaned-up\"",
                complete.completeWorkflowExecution.result.data
                    .toStringUtf8(),
            )
        }

    class CatchCancelAndCleanUp {
        suspend fun WorkflowContext.run(): String {
            try {
                awaitCondition { false }
            } catch (e: WorkflowCancelledException) {
                // Kotlin cancellation is sticky: durable cleanup must opt out of it
                withContext(NonCancellable) { sleep(1.seconds) }
                return "cleaned-up"
            }
            return "unreachable"
        }
    }

    @Test
    fun `cleanup under NonCancellable after cancellation schedules work and then completes`() =
        runTest {
            val f = fixture(CatchCancelAndCleanUp())
            f.activate(initializeWorkflowJob(workflowType = "CatchCancelAndCleanUp"))

            val afterCancel = f.activate(cancelWorkflowJob())
            assertTrue(afterCancel.any { it.hasStartTimer() }, "cleanup timer expected: $afterCancel")
            assertTrue(afterCancel.none { it.hasCancelWorkflowExecution() || it.hasCompleteWorkflowExecution() })

            val afterTimer = f.activate(fireTimerJob(afterCancel.timerSeq(1)))
            assertTrue(afterTimer.any { it.hasCompleteWorkflowExecution() }, "expected completion: $afterTimer")
        }

    class CatchCancelAndFail {
        suspend fun WorkflowContext.run(): String {
            try {
                awaitCondition { false }
            } catch (e: WorkflowCancelledException) {
                throw com.surrealdev.temporal.common.exceptions.ApplicationFailure
                    .failure("gave up", type = "GaveUp")
            }
            return "unreachable"
        }
    }

    @Test
    fun `workflow that catches cancellation and throws a failure fails the workflow`() =
        runTest {
            val f = fixture(CatchCancelAndFail())
            f.activate(initializeWorkflowJob(workflowType = "CatchCancelAndFail"))

            val commands = f.activate(cancelWorkflowJob())
            assertTrue(commands.any { it.hasFailWorkflowExecution() }, "expected FailWorkflowExecution: $commands")
        }

    class PlainCancel {
        suspend fun WorkflowContext.run(): String {
            awaitCondition { false }
            return "unreachable"
        }
    }

    @Test
    fun `workflow that does not catch cancellation is reported as cancelled`() =
        runTest {
            val f = fixture(PlainCancel())
            f.activate(initializeWorkflowJob(workflowType = "PlainCancel"))

            val commands = f.activate(cancelWorkflowJob())
            assertTrue(commands.any { it.hasCancelWorkflowExecution() }, "expected CancelWorkflowExecution: $commands")
        }

    // ------------------------------------------------------------------
    // Waiting on non-Temporal primitives yields the activation (Python semantics)
    // ------------------------------------------------------------------

    class AwaitsUserDeferred {
        val gate = CompletableDeferred<Unit>()

        suspend fun WorkflowContext.run(): String {
            setSignalHandlerWithPayloads("go") { gate.complete(Unit) }
            gate.await() // no tracked pending operation
            return "done"
        }
    }

    @Test
    fun `awaiting a plain CompletableDeferred yields the activation and resumes on the completing signal`() {
        val f = fixture(AwaitsUserDeferred())
        val init = f.activateWithDeadline(initializeWorkflowJob(workflowType = "AwaitsUserDeferred"))
        assertTrue(init.isEmpty(), "idle activation should carry no commands: $init")

        val commands = f.activateWithDeadline(signalWorkflowJob("go"))
        assertTrue(commands.any { it.hasCompleteWorkflowExecution() }, "expected completion: $commands")
    }

    class ChannelBetweenHandlerAndMain {
        val inbox = Channel<String>(Channel.UNLIMITED)

        suspend fun WorkflowContext.run(): String {
            setSignalHandlerWithPayloads("item") { inbox.trySend("x") }
            val first = inbox.receive()
            val second = inbox.receive()
            return first + second
        }
    }

    @Test
    fun `channel receive in the main coroutine is fed by signal handlers across activations`() {
        val f = fixture(ChannelBetweenHandlerAndMain())
        assertTrue(
            f.activateWithDeadline(initializeWorkflowJob(workflowType = "ChannelBetweenHandlerAndMain")).isEmpty(),
        )
        assertTrue(f.activateWithDeadline(signalWorkflowJob("item")).isEmpty())

        val commands = f.activateWithDeadline(signalWorkflowJob("item"))
        val complete = commands.single { it.hasCompleteWorkflowExecution() }
        assertEquals(
            "\"xx\"",
            complete.completeWorkflowExecution.result.data
                .toStringUtf8(),
        )
    }

    // ------------------------------------------------------------------
    // Escaped coroutines are still awaited before the activation completes
    // ------------------------------------------------------------------

    class EscapesToIo {
        suspend fun WorkflowContext.run(): String {
            val value =
                withContext(Dispatchers.IO) {
                    delay(50) // real delay off the workflow dispatcher
                    withContext(Dispatchers.Default) { 21 * 2 }
                }
            return "io:$value"
        }
    }

    @Test
    fun `an escaped coroutine is awaited before the activation completes`() {
        val f = fixture(EscapesToIo())
        val commands = f.activateWithDeadline(initializeWorkflowJob(workflowType = "EscapesToIo"))
        val complete = commands.single { it.hasCompleteWorkflowExecution() }
        assertEquals(
            "\"io:42\"",
            complete.completeWorkflowExecution.result.data
                .toStringUtf8(),
        )
    }

    class SneaksBackFromIo {
        suspend fun WorkflowContext.run(): String {
            val workflowCtx = coroutineContext
            withContext(Dispatchers.IO) {
                withContext(workflowCtx) { sleep(1.seconds) }
            }
            return "sneaky"
        }
    }

    @Test
    fun `an escaped coroutine that re-enters the workflow dispatcher completes after its timer fires`() {
        val f = fixture(SneaksBackFromIo())
        val init = f.activateWithDeadline(initializeWorkflowJob(workflowType = "SneaksBackFromIo"))
        assertTrue(init.any { it.hasStartTimer() }, "expected timer: $init")

        val commands = f.activateWithDeadline(fireTimerJob(init.timerSeq(1)))
        assertTrue(commands.any { it.hasCompleteWorkflowExecution() }, "expected completion: $commands")
    }

    class LaunchesOnDefaultDispatcher {
        suspend fun WorkflowContext.run(): String {
            val job = launch(Dispatchers.Default) { delay(30) }
            job.join()
            return "joined"
        }
    }

    @Test
    fun `a child launched on a foreign dispatcher is awaited before the activation completes`() {
        val f = fixture(LaunchesOnDefaultDispatcher())
        val commands = f.activateWithDeadline(initializeWorkflowJob(workflowType = "LaunchesOnDefaultDispatcher"))
        assertTrue(commands.any { it.hasCompleteWorkflowExecution() }, "expected completion: $commands")
    }

    // ------------------------------------------------------------------
    // Plugin-contributed context cannot displace SDK-critical elements
    // ------------------------------------------------------------------

    class Idle {
        suspend fun WorkflowContext.run(): String {
            awaitCondition { false }
            return "unreachable"
        }
    }

    @Test
    fun `plugin coroutine context cannot override the workflow dispatcher or job`() =
        runTest {
            val f = fixture(Idle())
            f.activate(initializeWorkflowJob(workflowType = "Idle"))
            val ctx = assertNotNull(f.executor.context)
            val rogueJob = Job()
            ctx.pluginCoroutineContext = Dispatchers.Default + rogueJob

            assertSame(ctx.workflowDispatcher, ctx.coroutineContext[ContinuationInterceptor])
            assertSame(ctx.job, ctx.coroutineContext[Job])

            var handlerContext: CoroutineContext? = null
            ctx.launchHandler { handlerContext = coroutineContext }
            ctx.workflowDispatcher.processAllWork()
            assertSame(ctx.workflowDispatcher, assertNotNull(handlerContext)[ContinuationInterceptor])
            assertFalse(handlerContext!![Job] === rogueJob, "handler must not run under a plugin-supplied job")
        }

    // ------------------------------------------------------------------
    // Fatal JVM errors in signal handlers surface as fatal task failures
    // ------------------------------------------------------------------

    class SignalHandlerThrowsFatal {
        suspend fun WorkflowContext.run(): String {
            setSignalHandlerWithPayloads("boom") {
                throw OutOfMemoryError("simulated")
            }
            awaitCondition { false }
            return "unreachable"
        }
    }

    @Test
    fun `fatal error in a signal handler fails the task and is surfaced as fatalError`() =
        runTest {
            val f = fixture(SignalHandlerThrowsFatal())
            f.activate(initializeWorkflowJob(workflowType = "SignalHandlerThrowsFatal"))

            val result = f.dispatch(signalWorkflowJob("boom"))
            assertTrue(result.completion.hasFailed(), "expected task failure: ${result.completion}")
            assertTrue(result.fatalError is OutOfMemoryError, "expected fatalError, got ${result.fatalError}")
        }
}
