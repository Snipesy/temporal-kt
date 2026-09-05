package com.surrealdev.temporal.application

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.history.TemporalHistoryEvent
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.core.internal.DefaultIdentity
import com.surrealdev.temporal.testing.assertHistoryAndReturn
import com.surrealdev.temporal.testing.awaitHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.signal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the identity the SDK reports to the server.
 *
 * Identity is more than a display label: Core refuses to build a worker on an empty client
 * identity, and derives the sticky task queue name from it. These tests pin down what actually
 * lands in history, since that is the only place the two identities are distinguishable -
 * `WorkflowExecutionStarted` carries the *client's* identity (who started the workflow) while
 * `WorkflowTaskStarted` carries the *worker's* (who picked the task up).
 *
 * The division of labour matches sdk-python: the client resolves `pid@hostname` at connect time,
 * and a worker with no identity of its own inherits it rather than computing its own.
 */
class WorkerIdentityTest {
    @Workflow("IdentityWorkflow")
    class IdentityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"
    }

    @Workflow("IdentitySignalWorkflow")
    class IdentitySignalWorkflow {
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return "done"
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `client and worker default to pid at hostname in history`() =
        runTemporalTest {
            val taskQueue = "test-identity-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<IdentityWorkflow>()
                }
            }

            val handle =
                client().startWorkflow(
                    workflowType = "IdentityWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 10.seconds)
            assertEquals("done", result)

            val history = handle.assertHistoryAndReturn { completed() }

            val started = history.filterByType<TemporalHistoryEvent.WorkflowExecutionStarted>().single()
            assertEquals(
                DefaultIdentity.value,
                started.identity,
                "client identity should reach history; an empty value here means connect() never set it",
            )

            val taskStarted = history.filterByType<TemporalHistoryEvent.WorkflowTaskStarted>().first()
            assertEquals(
                DefaultIdentity.value,
                taskStarted.identity,
                "worker with no identity of its own should inherit the client's",
            )

            assertTrue(
                DefaultIdentity.value.startsWith("${ProcessHandle.current().pid()}@"),
                "identity should be pid@hostname, was '${DefaultIdentity.value}'",
            )
        }

    @Test
    fun `signal stamps the client identity`() =
        runTemporalTest {
            val taskQueue = "test-identity-signal-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<IdentitySignalWorkflow>()
                }
            }

            val handle =
                client().startWorkflow(
                    workflowType = "IdentitySignalWorkflow",
                    taskQueue = taskQueue,
                )

            handle.signal("complete")
            val result: String = handle.result(timeout = 10.seconds)
            assertEquals("done", result)

            val history = handle.assertHistoryAndReturn { completed() }
            assertEquals(
                DefaultIdentity.value,
                history.filterByType<TemporalHistoryEvent.WorkflowExecutionSignaled>().single().identity,
                "SignalWorkflowExecution should stamp the client identity",
            )
        }

    @Test
    fun `terminate stamps the client identity`() =
        runTemporalTest {
            val taskQueue = "test-identity-terminate-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<IdentitySignalWorkflow>()
                }
            }

            // Never signalled, so it stays running until terminated.
            val handle =
                client().startWorkflow(
                    workflowType = "IdentitySignalWorkflow",
                    taskQueue = taskQueue,
                )
            handle.awaitHistory(description = "the workflow to start running") {
                it.filterByType<TemporalHistoryEvent.WorkflowTaskCompleted>().isNotEmpty()
            }

            handle.terminate("identity test")

            val history =
                handle.awaitHistory(description = "a WorkflowExecutionTerminated event") {
                    it.filterByType<TemporalHistoryEvent.WorkflowExecutionTerminated>().isNotEmpty()
                }
            assertEquals(
                DefaultIdentity.value,
                history.filterByType<TemporalHistoryEvent.WorkflowExecutionTerminated>().single().identity,
                "TerminateWorkflowExecution should stamp the client identity",
            )
        }

    @Test
    fun `worker inherits an explicitly configured connection identity`() =
        runTemporalTest {
            val taskQueue = "test-identity-inherited-${UUID.randomUUID()}"
            val custom = "connection-${UUID.randomUUID()}"
            val address = targetUrl
            val app =
                TemporalApplication {
                    connection {
                        target = "http://$address"
                        identity = custom
                    }
                }
            app.taskQueue(taskQueue) { workflow<IdentityWorkflow>() }
            try {
                app.start()
                val handle = app.client().startWorkflow(workflowType = "IdentityWorkflow", taskQueue = taskQueue)
                assertEquals("done", handle.result<String>(timeout = 10.seconds))
                val history = handle.assertHistoryAndReturn { completed() }
                assertEquals(custom, history.filterByType<TemporalHistoryEvent.WorkflowTaskStarted>().first().identity)
            } finally {
                app.close()
            }
        }

    @Test
    fun `explicit worker identity overrides the worker but not the client`() =
        runTemporalTest {
            val taskQueue = "test-identity-override-${UUID.randomUUID()}"
            val custom = "payment-worker-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workerIdentity = custom
                    workflow<IdentityWorkflow>()
                }
            }

            val handle =
                client().startWorkflow(
                    workflowType = "IdentityWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 10.seconds)
            assertEquals("done", result)

            val history = handle.assertHistoryAndReturn { completed() }

            assertEquals(
                custom,
                history.filterByType<TemporalHistoryEvent.WorkflowTaskStarted>().first().identity,
                "configured workerIdentity should win for the worker",
            )
            assertEquals(
                DefaultIdentity.value,
                history.filterByType<TemporalHistoryEvent.WorkflowExecutionStarted>().single().identity,
                "a worker override must not leak into the client's identity",
            )
        }
}
