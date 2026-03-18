package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.exceptions.ClientWorkflowFailedException
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for strict workflow registration mode.
 *
 * Verifies that [com.surrealdev.temporal.workflow.StrictWorkflowRegistrationKey] causes unregistered
 * workflow types to permanently fail the workflow execution (instead of looping on task failures).
 */
@Tag("integration")
class StrictWorkflowRegistrationIntegrationTest {
    @Workflow("RegisteredWorkflow")
    class RegisteredWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "ok"
    }

    // ================================================================
    // Strict mode (default in runTemporalTest)
    // ================================================================

    @Test
    fun `unregistered workflow type fails immediately in strict mode`() =
        runTemporalTest(timeout = 30.seconds) {
            application {
                taskQueue("test-queue") {
                    workflow<RegisteredWorkflow>()
                }
            }

            val client = client()
            // Start a workflow type that is NOT registered on the task queue.
            // In strict mode (default), the server should receive a FailWorkflowExecution
            // command and the result() call should throw rather than hang.
            val handle =
                client.startWorkflow(
                    workflowType = "UnregisteredWorkflowType",
                    taskQueue = "test-queue",
                    workflowId = "strict-reg-${UUID.randomUUID()}",
                )

            val ex = assertFailsWith<ClientWorkflowFailedException> { handle.result<String>() }
            // The cause chain should contain the failure message from the dispatcher
            val causeMessage =
                generateSequence(
                    ex as Throwable,
                ) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertContains(causeMessage, "Workflow type not registered: UnregisteredWorkflowType")
        }

    @Test
    fun `strict mode includes known types in error message`() =
        runTemporalTest(timeout = 30.seconds) {
            application {
                taskQueue("test-queue") {
                    workflow<RegisteredWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "TypoedWorkflow",
                    taskQueue = "test-queue",
                    workflowId = "strict-known-${UUID.randomUUID()}",
                )

            val ex = assertFailsWith<ClientWorkflowFailedException> { handle.result<String>() }
            val causeMessage =
                generateSequence(
                    ex as Throwable,
                ) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            // Known types should appear in the message to help diagnose registration mistakes
            assertContains(causeMessage, "RegisteredWorkflow")
        }

    // ================================================================
    // Strict mode can be disabled
    // ================================================================

    @Test
    fun `strict mode disabled - application starts without error`() =
        runTemporalTest(timeout = 30.seconds) {
            // Opt out of strict mode. We verify the builder accepts the flag and the
            // application still starts normally. We do NOT await an unregistered workflow
            // because in non-strict mode it would retry indefinitely.
            strictWorkflowRegistration(false)

            application {
                taskQueue("test-queue") {
                    workflow<RegisteredWorkflow>()
                }
            }

            // Registered workflow still works when strict mode is disabled
            val result =
                client()
                    .startWorkflow(
                        workflowClass = RegisteredWorkflow::class,
                        taskQueue = "test-queue",
                        workflowId = "no-strict-ok-${UUID.randomUUID()}",
                    ).result<String>()

            kotlin.test.assertEquals("ok", result)
        }

    // ================================================================
    // Registered workflow works normally in strict mode
    // ================================================================

    @Test
    fun `registered workflow completes normally in strict mode`() =
        runTemporalTest(timeout = 30.seconds) {
            application {
                taskQueue("test-queue") {
                    workflow<RegisteredWorkflow>()
                }
            }

            val result =
                client()
                    .startWorkflow(
                        workflowClass = RegisteredWorkflow::class,
                        taskQueue = "test-queue",
                        workflowId = "registered-${UUID.randomUUID()}",
                    ).result<String>()

            kotlin.test.assertEquals("ok", result)
        }
}
