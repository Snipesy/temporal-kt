package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for workflow failure semantics, matching mainline Temporal SDK behavior:
 *
 * - Failure-typed exceptions (ApplicationFailure, activity/child workflow failures, etc.)
 *   fail the WORKFLOW (permanent, FailWorkflowExecution command).
 * - All other exceptions (bugs: NPE, IllegalStateException, ...) fail the workflow TASK
 *   (retryable, completion.failed) so the workflow can recover after a worker fix/redeploy.
 */
class WorkflowFailureSemanticsTest {
    private val serializer = CompositePayloadSerializer.default()

    @Workflow("PlainExceptionWorkflow")
    class PlainExceptionWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = throw IllegalStateException("some bug in workflow code")
    }

    @Workflow("ApplicationFailureWorkflow")
    class ApplicationFailureWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = throw ApplicationFailure.failure("business failure", "MyFailure")
    }

    @Workflow("PlainExceptionInAsyncWorkflow")
    class PlainExceptionInAsyncWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val deferred =
                async {
                    throw IllegalArgumentException("bug inside async block")
                }
            deferred.await()
            return "unreachable"
        }
    }

    @Workflow("SleepThenFailWorkflow")
    class SleepThenFailWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(5.seconds)
            throw IllegalStateException("bug after sleep")
        }
    }

    private suspend inline fun <reified T : Any> initWorkflow(
        workflowType: String,
    ): coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion {
        val workflow = T::class.constructors.first().call()
        val runMethod = T::class.members.first { it.name == "run" } as KFunction<*>

        val methodInfo =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                workflowClass = T::class,
                instanceFactory = { workflow },
                parameterTypes = emptyList(),
                returnType = runMethod.returnType,
                hasContextReceiver = true,
                isSuspend = true,
            )

        val runId = "test-run-${UUID.randomUUID()}"
        val executor =
            createTestWorkflowExecutor(
                runId = runId,
                methodInfo = methodInfo,
                serializer = serializer,
            )

        return executor
            .activate(
                createActivation(
                    runId = runId,
                    jobs = listOf(initializeWorkflowJob(workflowType = workflowType)),
                    isReplaying = false,
                ),
            ).completion
    }

    @Test
    fun `plain exception fails the workflow task not the workflow`() =
        runTest {
            val completion = initWorkflow<PlainExceptionWorkflow>("PlainExceptionWorkflow")

            // A non-failure-typed exception is a bug, not a business failure: it must fail
            // the workflow TASK (retryable) so the workflow survives until the worker is fixed.
            assertTrue(completion.hasFailed(), "expected workflow task failure completion")
            assertTrue(
                completion.failed.failure.message
                    .contains("some bug in workflow code"),
            )
            assertFalse(completion.hasSuccessful())
        }

    @Test
    fun `ApplicationFailure fails the workflow permanently`() =
        runTest {
            val completion = initWorkflow<ApplicationFailureWorkflow>("ApplicationFailureWorkflow")

            assertTrue(completion.hasSuccessful())
            val failCommand =
                completion.successful.commandsList.single { it.hasFailWorkflowExecution() }
            assertEquals("MyFailure", failCommand.failWorkflowExecution.failure.applicationFailureInfo.type)
        }

    @Test
    fun `plain exception inside async block fails the workflow task`() =
        runTest {
            val completion = initWorkflow<PlainExceptionInAsyncWorkflow>("PlainExceptionInAsyncWorkflow")

            // Structured-concurrency cancellation wrappers must be unwrapped to the root
            // cause before deciding, and the root cause here is a plain exception.
            assertTrue(completion.hasFailed(), "expected workflow task failure completion")
            assertTrue(
                completion.failed.failure.message
                    .contains("bug inside async block"),
            )
        }

    @Test
    fun `plain exception after a command was scheduled still fails the task`() =
        runTest {
            // Workflow sleeps first (StartTimer command in first activation), then throws
            // when the timer fires. The second activation must be a task failure.
            val workflow = SleepThenFailWorkflow()
            val runMethod = SleepThenFailWorkflow::class.members.first { it.name == "run" } as KFunction<*>
            val methodInfo =
                WorkflowMethodInfo(
                    workflowType = "SleepThenFailWorkflow",
                    runMethod = runMethod,
                    workflowClass = SleepThenFailWorkflow::class,
                    instanceFactory = { workflow },
                    parameterTypes = emptyList(),
                    returnType = runMethod.returnType,
                    hasContextReceiver = true,
                    isSuspend = true,
                )
            val runId = "test-run-${UUID.randomUUID()}"
            val executor =
                createTestWorkflowExecutor(
                    runId = runId,
                    methodInfo = methodInfo,
                    serializer = serializer,
                )

            val initCompletion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs = listOf(initializeWorkflowJob(workflowType = "SleepThenFailWorkflow")),
                            isReplaying = false,
                        ),
                    ).completion
            assertTrue(initCompletion.hasSuccessful())
            val timerSeq =
                initCompletion.successful.commandsList
                    .single { it.hasStartTimer() }
                    .startTimer.seq

            val fireCompletion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs =
                                listOf(
                                    com.surrealdev.temporal.testing.ProtoTestHelpers
                                        .fireTimerJob(timerSeq),
                                ),
                            isReplaying = false,
                        ),
                    ).completion

            assertTrue(fireCompletion.hasFailed(), "expected workflow task failure completion")
        }
}
