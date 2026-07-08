package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.cancelWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveActivityJobCompleted
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the workflow cancellation model:
 * - the CancelWorkflow reason is preserved and observable
 * - cancellation is catchable, and `withContext(NonCancellable)` allows post-cancel
 *   cleanup work (e.g. a cleanup activity) before the workflow reports cancelled
 */
class WorkflowCancellationTest {
    private val serializer = CompositePayloadSerializer.default()

    @Workflow("CancelReasonWorkflow")
    class CancelReasonWorkflow {
        var caughtMessage: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                sleep(30.seconds)
            } catch (e: CancellationException) {
                caughtMessage = e.message
                throw e
            }
            return "done"
        }
    }

    @Workflow("CancelCleanupWorkflow")
    class CancelCleanupWorkflow {
        var cleanupResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                sleep(30.seconds)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    cleanupResult =
                        startActivity(
                            activityType = "cleanup",
                            options = ActivityOptions(startToCloseTimeout = 30.seconds),
                        ).result()
                }
                throw e
            }
            return "done"
        }
    }

    private suspend inline fun <reified T : Any> init(workflowType: String): Triple<WorkflowExecutor, String, T> {
        val workflow = T::class.constructors.first().call()
        val runMethod = T::class.members.first { it.name == "run" } as KFunction<*>
        val methodInfo =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                workflowClass = T::class,
                instanceFactory = { workflow },
                parameterTypes = emptyList(),
                returnType = kotlin.reflect.typeOf<String>(),
                hasContextReceiver = true,
                isSuspend = true,
            )
        val runId = "test-run-${UUID.randomUUID()}"
        val executor = createTestWorkflowExecutor(runId = runId, methodInfo = methodInfo, serializer = serializer)
        executor.activate(
            createActivation(
                runId = runId,
                jobs = listOf(initializeWorkflowJob(workflowType = workflowType)),
                isReplaying = false,
            ),
        )
        return Triple(executor, runId, workflow)
    }

    @Test
    fun `cancellation reason is preserved and workflow reports cancelled`() =
        runTest {
            val (executor, runId, workflow) = init<CancelReasonWorkflow>("CancelReasonWorkflow")

            val completion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs = listOf(cancelWorkflowJob(reason = "user requested stop")),
                            isReplaying = false,
                        ),
                    ).completion

            assertTrue(completion.hasSuccessful())
            assertTrue(
                completion.successful.commandsList.any { it.hasCancelWorkflowExecution() },
                "expected CancelWorkflowExecution, got: ${completion.successful.commandsList}",
            )
            assertNotNull(workflow.caughtMessage, "workflow should be able to catch the cancellation")
            assertTrue(
                workflow.caughtMessage!!.contains("user requested stop"),
                "cancel reason should be preserved, got: ${workflow.caughtMessage}",
            )
        }

    @Test
    fun `NonCancellable cleanup activity runs after cancellation before workflow cancels`() =
        runTest {
            val (executor, runId, workflow) = init<CancelCleanupWorkflow>("CancelCleanupWorkflow")

            // Cancel: the workflow catches it and schedules a cleanup activity under
            // NonCancellable. The completion must contain the ScheduleActivity command
            // and must NOT yet be terminal.
            val cancelCompletion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs = listOf(cancelWorkflowJob(reason = "shutdown")),
                            isReplaying = false,
                        ),
                    ).completion

            assertTrue(cancelCompletion.hasSuccessful())
            val cancelCommands = cancelCompletion.successful.commandsList
            val scheduleActivity = cancelCommands.find { it.hasScheduleActivity() }
            assertNotNull(scheduleActivity, "cleanup activity should be scheduled, got: $cancelCommands")
            assertTrue(
                cancelCommands.none { it.hasCancelWorkflowExecution() },
                "workflow must not cancel before cleanup completes: $cancelCommands",
            )

            // Resolve the cleanup activity - now the workflow rethrows and cancels
            val resultPayload =
                io.temporal.api.common.v1.Payload
                    .newBuilder()
                    .putMetadata(
                        "encoding",
                        com.google.protobuf.ByteString
                            .copyFromUtf8("json/plain"),
                    ).setData(
                        com.google.protobuf.ByteString
                            .copyFromUtf8("\"cleaned\""),
                    ).build()
            val finalCompletion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs =
                                listOf(
                                    resolveActivityJobCompleted(
                                        scheduleActivity.scheduleActivity.seq,
                                        com.surrealdev.temporal.common
                                            .TemporalPayload(resultPayload),
                                    ),
                                ),
                            isReplaying = false,
                        ),
                    ).completion

            assertTrue(finalCompletion.hasSuccessful())
            assertTrue(
                finalCompletion.successful.commandsList.any { it.hasCancelWorkflowExecution() },
                "expected CancelWorkflowExecution after cleanup, got: ${finalCompletion.successful.commandsList}",
            )
            assertEquals("cleaned", workflow.cleanupResult)
        }
}
