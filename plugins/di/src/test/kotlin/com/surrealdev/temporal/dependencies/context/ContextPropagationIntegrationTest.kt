package com.surrealdev.temporal.dependencies.context

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.WorkflowStartOptions
import com.surrealdev.temporal.client.query
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.RetryPolicy
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.LocalActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.signal
import com.surrealdev.temporal.workflow.startActivity
import com.surrealdev.temporal.workflow.startChildWorkflow
import com.surrealdev.temporal.workflow.startLocalActivity
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Tenant(
    val name: String,
)

/**
 * Integration tests for [ContextPropagation] plugin.
 *
 * Tests end-to-end propagation of typed context values across service boundaries:
 * client -> workflow -> activity / child workflow / local activity.
 */
@Tag("integration")
class ContextPropagationIntegrationTest {
    // ================================================================
    // Workflows and Activities
    // ================================================================

    /** Workflow that reads tenant context and returns it. */
    @Workflow("CtxPropEchoWorkflow")
    class EchoWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val tenant = context<Tenant>("tenant")
            return tenant.name
        }
    }

    /** Activity that reads tenant context and returns it. */
    class TenantActivities {
        @Activity("getTenant")
        suspend fun ActivityContext.getTenant(): String {
            val tenant = context<Tenant>("tenant")
            return tenant.name
        }
    }

    /** Workflow that schedules an activity which reads the propagated context. */
    @Workflow("CtxPropActivityWorkflow")
    class ActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val activityResult =
                startActivity(
                    activityType = "getTenant",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result<String>()
            val workflowTenant = context<Tenant>("tenant").name
            return "$workflowTenant|$activityResult"
        }
    }

    /** Workflow that starts a child workflow which reads the propagated context. */
    @Workflow("CtxPropChildCallerWorkflow")
    class ChildCallerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val childResult =
                startChildWorkflow(
                    "CtxPropEchoWorkflow",
                    options = ChildWorkflowOptions(),
                ).result<String>()
            val myTenant = context<Tenant>("tenant").name
            return "$myTenant|$childResult"
        }
    }

    /** Workflow that starts a local activity which reads the propagated context. */
    @Workflow("CtxPropLocalActivityWorkflow")
    class LocalActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val localResult =
                startLocalActivity(
                    activityType = "getTenant",
                    options = LocalActivityOptions(startToCloseTimeout = 30.seconds),
                ).result<String>()
            val myTenant = context<Tenant>("tenant").name
            return "$myTenant|$localResult"
        }
    }

    /** Workflow with signal/query handlers that read propagated context. */
    @Workflow("CtxPropSignalQueryWorkflow")
    class SignalQueryWorkflow {
        private var tenantFromRun = ""
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            tenantFromRun = context<Tenant>("tenant").name
            awaitCondition { done }
            return tenantFromRun
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }

        @Query("getTenant")
        fun WorkflowContext.getTenant(): String = tenantFromRun
    }

    /** Workflow that propagates both passthrough and provider-added context. */
    @Workflow("CtxPropMultiContextWorkflow")
    class MultiContextWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val tenant = context<Tenant>("tenant")
            val extra = context<String>("extra")
            return "${tenant.name}|$extra"
        }
    }

    /** Workflow that reads tenant context, sleeps, then reads again (tests replay). */
    @Workflow("CtxPropTimerWorkflow")
    class TimerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val before = context<Tenant>("tenant").name
            sleep(1.seconds)
            val after = context<Tenant>("tenant").name
            return "$before|$after"
        }
    }

    /** Workflow that reads a "source" context to verify which value won. */
    @Workflow("CtxPropSourceWorkflow")
    class SourceWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = context<String>("source")
    }

    /** Workflow that uses contextOrNull to read an optional context entry. */
    @Workflow("CtxPropContextOrNullWorkflow")
    class ContextOrNullWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val tenant = contextOrNull<Tenant>("tenant")
            val missing = contextOrNull<String>("nonexistent")
            return "${tenant?.name ?: "none"}|${missing ?: "absent"}"
        }
    }

    /** Workflow that reads a null String context value (should fail with checkNotNull). */
    @Workflow("CtxPropNullStringWorkflow")
    class NullStringWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = context<String>("nullValue")
    }

    /** Workflow that reads a nullable String context value via contextOrNull. */
    @Workflow("CtxPropNullableStringWorkflow")
    class NullableStringWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val value = contextOrNull<String>("nullValue")
            return "got:$value"
        }
    }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun `context propagates from client to workflow`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-wf-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                }
                taskQueue(taskQueue) {
                    workflow<EchoWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropEchoWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme", result)
        }

    @Test
    fun `context propagates from workflow to activity`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-act-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                }
                taskQueue(taskQueue) {
                    workflow<ActivityWorkflow>()
                    activity(TenantActivities())
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropActivityWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme|acme", result)
        }

    @Test
    fun `context propagates from workflow to child workflow`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-child-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                }
                taskQueue(taskQueue) {
                    workflow<ChildCallerWorkflow>()
                    workflow<EchoWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropChildCallerWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme|acme", result)
        }

    @Test
    fun `context propagates from workflow to local activity`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-local-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                }
                taskQueue(taskQueue) {
                    workflow<LocalActivityWorkflow>()
                    activity(TenantActivities())
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropLocalActivityWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme|acme", result)
        }

    @Test
    fun `context is available in query handler`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "ctx-prop-query-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                }
                taskQueue(taskQueue) {
                    workflow<SignalQueryWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropSignalQueryWorkflow",
                    taskQueue = taskQueue,
                )

            // Poll until the workflow has started and set the tenant
            var queryResult = ""
            for (i in 1..50) {
                try {
                    queryResult = handle.query<String>("getTenant")
                    if (queryResult.isNotEmpty()) break
                } catch (_: Exception) {
                    // Workflow may not have started yet
                }
                kotlinx.coroutines.delay(100)
            }
            assertEquals("acme", queryResult)

            handle.signal("complete")
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme", result)
        }

    @Test
    fun `application-side provider adds new context alongside passthrough`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-multi-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                    context("extra") { "server-value" }
                }
                taskQueue(taskQueue) {
                    workflow<MultiContextWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropMultiContextWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme|server-value", result)
        }

    // ================================================================
    // ProviderBehavior Tests
    // ================================================================

    @Test
    fun `SKIP_IF_PRESENT preserves client value when app provider has same key`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-skip-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    // Default behavior (SKIP_IF_PRESENT): should use the client's value
                    // since the inbound header already contains "source"
                    context("source") { "from-server" }
                }
                taskQueue(taskQueue) {
                    workflow<SourceWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("source") { "from-client" }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropSourceWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("from-client", result)
        }

    @Test
    fun `ALWAYS_EXECUTE overwrites client value when app provider has same key`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-always-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    context("source", ProviderBehavior.ALWAYS_EXECUTE) { "from-server" }
                }
                taskQueue(taskQueue) {
                    workflow<SourceWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("source") { "from-client" }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropSourceWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("from-server", result)
        }

    @Test
    fun `SKIP_IF_PRESENT still executes provider when no inbound header exists`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-skip-new-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                    // "extra" is not sent by the client, so provider always executes
                    context("extra") { "server-only" }
                }
                taskQueue(taskQueue) {
                    workflow<MultiContextWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                        // Note: client does NOT send "extra"
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropMultiContextWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme|server-only", result)
        }

    @Test
    fun `SKIP_IF_PRESENT non-deterministic provider is safe because inbound header wins`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-skip-nondet-${UUID.randomUUID()}"
            val callCount = AtomicInteger(0)

            application {
                install(ContextPropagation) {
                    // This provider is non-deterministic (returns different values each call).
                    // With SKIP_IF_PRESENT, the inbound header from the client always wins
                    // in buildPropagatedContext, so the non-determinism is safe.
                    context("source") {
                        "from-server-${callCount.incrementAndGet()}"
                    }
                }
                taskQueue(taskQueue) {
                    workflow<SourceWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("source") { "from-client" }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropSourceWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            // Even though the app provider is non-deterministic, SKIP_IF_PRESENT
            // means the client's stable header value is used
            assertEquals("from-client", result)
        }

    @Test
    fun `context survives timer and replay`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-timer-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                }
                taskQueue(taskQueue) {
                    workflow<TimerWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropTimerWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme|acme", result)
        }

    @Test
    fun `context available in signal handler after timer`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "ctx-prop-sig-timer-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                }
                taskQueue(taskQueue) {
                    workflow<SignalQueryWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropSignalQueryWorkflow",
                    taskQueue = taskQueue,
                )

            // Wait for workflow to start
            var ready = false
            for (i in 1..50) {
                try {
                    val q = handle.query<String>("getTenant")
                    if (q.isNotEmpty()) {
                        ready = true
                        break
                    }
                } catch (_: Exception) {
                }
                kotlinx.coroutines.delay(100)
            }
            check(ready) { "Workflow did not start in time" }

            // Signal without context headers (signal doesn't carry them from client)
            // The workflow should still have the original context from startWorkflow
            handle.signal("complete")
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme", result)
        }

    // ================================================================
    // contextOrNull Tests
    // ================================================================

    @Test
    fun `contextOrNull returns value when present and null when missing`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-ornull-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("tenant")
                }
                taskQueue(taskQueue) {
                    workflow<ContextOrNullWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        context("tenant") { Tenant("acme") }
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropContextOrNullWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("acme|absent", result)
        }

    @Test
    fun `contextOrNull returns null when no context entries defined`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-ornull-none-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {}
                taskQueue(taskQueue) {
                    workflow<ContextOrNullWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {}
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropContextOrNullWorkflow",
                    taskQueue = taskQueue,
                )
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("none|absent", result)
        }

    // ================================================================
    // Null value deserialization bug test
    // ================================================================

    @Test
    fun `context with null provider value does not return string null`() =
        runTemporalTest {
            val taskQueue = "ctx-prop-null-str-${UUID.randomUUID()}"

            application {
                install(ContextPropagation) {
                    passThrough("nullValue")
                }
                taskQueue(taskQueue) {
                    workflow<NullStringWorkflow>()
                }
            }

            val client =
                client {
                    install(ContextPropagation) {
                        // omit
                    }
                }

            val handle =
                client.startWorkflow(
                    workflowType = "CtxPropNullStringWorkflow",
                    taskQueue = taskQueue,
                    options =
                        WorkflowStartOptions(
                            retryPolicy =
                                RetryPolicy(
                                    maximumAttempts = 1,
                                ),
                        ),
                )
            // Should fail because the deserialized value is null but context<String>() expects non-null
            assertFailsWith<Exception> {
                handle.result<String>(timeout = 30.seconds)
            }
        }
}
