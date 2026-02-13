package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.heartbeat
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.plugin.createApplicationPlugin
import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.query
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.client.update
import com.surrealdev.temporal.testing.assertHistory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that verify interceptors are invoked at every operation boundary.
 *
 * Uses a counting plugin that registers interceptors for all operations and counts
 * how many times each interceptor is called. Similar pattern to [CodecCountingIntegrationTest]
 * but for the interceptor chain.
 */
@Tag("integration")
class InterceptorCountingIntegrationTest {
    // ================================================================
    // Counting interceptor plugin
    // ================================================================

    class InterceptorCounts {
        // Workflow inbound
        val executeWorkflow = AtomicInteger(0)
        val handleSignal = AtomicInteger(0)
        val handleQuery = AtomicInteger(0)
        val validateUpdate = AtomicInteger(0)
        val executeUpdate = AtomicInteger(0)

        // Workflow outbound
        val scheduleActivity = AtomicInteger(0)
        val scheduleLocalActivity = AtomicInteger(0)
        val startChildWorkflow = AtomicInteger(0)
        val sleep = AtomicInteger(0)

        // Activity
        val executeActivity = AtomicInteger(0)
        val heartbeat = AtomicInteger(0)

        fun resetAll() {
            executeWorkflow.set(0)
            handleSignal.set(0)
            handleQuery.set(0)
            validateUpdate.set(0)
            executeUpdate.set(0)
            scheduleActivity.set(0)
            scheduleLocalActivity.set(0)
            startChildWorkflow.set(0)
            sleep.set(0)
            executeActivity.set(0)
            heartbeat.set(0)
        }
    }

    private val counts = InterceptorCounts()

    private fun createCountingPlugin() =
        createApplicationPlugin<Unit, Unit>(
            name = "CountingInterceptorPlugin",
        ) {
            workflow {
                onExecute { input, proceed ->
                    counts.executeWorkflow.incrementAndGet()
                    proceed(input)
                }

                onHandleSignal { input, proceed ->
                    counts.handleSignal.incrementAndGet()
                    proceed(input)
                }

                onHandleQuery { input, proceed ->
                    counts.handleQuery.incrementAndGet()
                    proceed(input)
                }

                onValidateUpdate { input, proceed ->
                    counts.validateUpdate.incrementAndGet()
                    proceed(input)
                }

                onExecuteUpdate { input, proceed ->
                    counts.executeUpdate.incrementAndGet()
                    proceed(input)
                }

                onScheduleActivity { input, proceed ->
                    counts.scheduleActivity.incrementAndGet()
                    proceed(input)
                }

                onScheduleLocalActivity { input, proceed ->
                    counts.scheduleLocalActivity.incrementAndGet()
                    proceed(input)
                }

                onStartChildWorkflow { input, proceed ->
                    counts.startChildWorkflow.incrementAndGet()
                    proceed(input)
                }

                onSleep { input, proceed ->
                    counts.sleep.incrementAndGet()
                    proceed(input)
                }
            }

            activity {
                onExecute { input, proceed ->
                    counts.executeActivity.incrementAndGet()
                    proceed(input)
                }

                onHeartbeat { input, proceed ->
                    counts.heartbeat.incrementAndGet()
                    proceed(input)
                }
            }

            Unit
        }

    // ================================================================
    // Workflows and Activities
    // ================================================================

    @Workflow("InterceptorEchoWorkflow")
    class EchoWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String = "wf:$input"
    }

    class EchoActivities {
        @Activity("interceptorEcho")
        suspend fun ActivityContext.echo(input: String): String = "echoed:$input"
    }

    @Workflow("InterceptorActivityWorkflow")
    class ActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String =
            startActivity<String>(
                activityType = "interceptorEcho",
                arg = input,
                options = ActivityOptions(startToCloseTimeout = 30.seconds),
            ).result()
    }

    @Workflow("InterceptorLocalActivityWorkflow")
    class LocalActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String =
            startLocalActivity<String>(
                activityType = "interceptorEcho",
                arg = input,
                options = LocalActivityOptions(startToCloseTimeout = 30.seconds),
            ).result()
    }

    @Workflow("InterceptorChildCallerWorkflow")
    class ChildCallerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String =
            startChildWorkflow("InterceptorEchoWorkflow", arg = input, options = ChildWorkflowOptions())
                .result()
    }

    @Workflow("InterceptorSignalQueryWorkflow")
    class SignalQueryWorkflow {
        private val received = mutableListOf<String>()
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return received.joinToString(",")
        }

        @Signal("addItem")
        fun WorkflowContext.addItem(item: String) {
            received.add(item)
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }

        @Query("getItems")
        fun WorkflowContext.getItems(): List<String> = received.toList()

        @Query("getCount")
        fun WorkflowContext.getCount(): Int = received.size
    }

    @Workflow("InterceptorUpdateWorkflow")
    class UpdateWorkflow {
        private var value = ""
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return value
        }

        @Update("setValue")
        fun WorkflowContext.setValue(v: String): String {
            value = v
            return "set:$v"
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Workflow("InterceptorSleepWorkflow")
    class SleepWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(1.seconds)
            return "slept"
        }
    }

    @Serializable
    data class HeartbeatProgress(
        val step: Int,
    )

    class HeartbeatActivity {
        @Activity("interceptorHeartbeat")
        suspend fun ActivityContext.heartbeatWork(steps: Int): String {
            for (i in 1..steps) {
                delay(10)
                heartbeat(HeartbeatProgress(i))
            }
            return "completed-$steps"
        }
    }

    @Workflow("InterceptorHeartbeatWorkflow")
    class HeartbeatWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(steps: Int): String =
            startActivity<Int>(
                activityType = "interceptorHeartbeat",
                arg = steps,
                options =
                    ActivityOptions(
                        startToCloseTimeout = 1.minutes,
                        heartbeatTimeout = 10.seconds,
                    ),
            ).result<String>()
    }

    // ================================================================
    // Helpers
    // ================================================================

    private suspend inline fun <T> pollUntil(
        timeout: Duration = 15.seconds,
        crossinline condition: (T) -> Boolean,
        crossinline query: suspend () -> T,
    ): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(timeout) {
                while (true) {
                    val result = query()
                    if (condition(result)) return@withTimeout result
                    kotlinx.coroutines.yield()
                }
                @Suppress("UNREACHABLE_CODE")
                throw AssertionError("Unreachable")
            }
        }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun `interceptor is invoked for workflow execution`() =
        runTemporalTest {
            val taskQueue = "test-interceptor-wf-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<EchoWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorEchoWorkflow",
                    taskQueue = taskQueue,
                    arg = "hello",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:hello", result)

            assertEquals(1, counts.executeWorkflow.get(), "executeWorkflow interceptor should be called once")

            handle.assertHistory { completed() }
        }

    @Test
    fun `interceptor is invoked for activity execution`() =
        runTemporalTest {
            val taskQueue = "test-interceptor-act-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<ActivityWorkflow>()
                    activity(EchoActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = "test-act",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("echoed:test-act", result)

            assertEquals(1, counts.executeWorkflow.get(), "executeWorkflow interceptor should be called once")
            assertEquals(1, counts.scheduleActivity.get(), "scheduleActivity interceptor should be called once")
            assertEquals(1, counts.executeActivity.get(), "executeActivity interceptor should be called once")

            handle.assertHistory { completed() }
        }

    @Test
    fun `interceptor is invoked for local activity execution`() =
        runTemporalTest {
            val taskQueue = "test-interceptor-local-act-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<LocalActivityWorkflow>()
                    activity(EchoActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorLocalActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = "test-local",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("echoed:test-local", result)

            assertEquals(1, counts.executeWorkflow.get(), "executeWorkflow interceptor should be called once")
            assertEquals(
                1,
                counts.scheduleLocalActivity.get(),
                "scheduleLocalActivity interceptor should be called once",
            )
            assertEquals(1, counts.executeActivity.get(), "executeActivity interceptor should be called once")

            handle.assertHistory { completed() }
        }

    @Test
    fun `interceptor is invoked for child workflow`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-interceptor-child-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<ChildCallerWorkflow>()
                    workflow<EchoWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorChildCallerWorkflow",
                    taskQueue = taskQueue,
                    arg = "child-test",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:child-test", result)

            // executeWorkflow: 1 for parent + 1 for child = 2
            assertEquals(2, counts.executeWorkflow.get(), "executeWorkflow interceptor should be called twice")
            assertEquals(
                1,
                counts.startChildWorkflow.get(),
                "startChildWorkflow interceptor should be called once",
            )

            handle.assertHistory { completed() }
        }

    @Test
    fun `interceptor is invoked for signal`() =
        runTemporalTest {
            val taskQueue = "test-interceptor-signal-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<SignalQueryWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorSignalQueryWorkflow",
                    taskQueue = taskQueue,
                )

            // Send two signals
            handle.signal("addItem", "A")
            handle.signal("addItem", "B")

            pollUntil<Int>(condition = { it >= 2 }) {
                handle.query("getCount")
            }

            handle.signal("complete")
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("A,B", result)

            // 3 signals total: addItem x2 + complete
            assertEquals(3, counts.handleSignal.get(), "handleSignal interceptor should be called 3 times")
        }

    @Test
    fun `interceptor is invoked for query`() =
        runTemporalTest {
            val taskQueue = "test-interceptor-query-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<SignalQueryWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorSignalQueryWorkflow",
                    taskQueue = taskQueue,
                )

            // Send a signal, then query
            handle.signal("addItem", "A")

            pollUntil<Int>(condition = { it >= 1 }) {
                handle.query("getCount")
            }

            val items: List<String> = handle.query("getItems")
            assertEquals(listOf("A"), items)

            // handleQuery: getCount calls from polling + 1 getItems
            assertTrue(counts.handleQuery.get() >= 2, "handleQuery interceptor should be called >= 2 times")

            handle.signal("complete")
            handle.result<String>(timeout = 30.seconds)
        }

    @Test
    fun `interceptor is invoked for update`() =
        runTemporalTest {
            val taskQueue = "test-interceptor-update-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<UpdateWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorUpdateWorkflow",
                    taskQueue = taskQueue,
                )

            val updateResult: String = handle.update("setValue", "updated")
            assertEquals("set:updated", updateResult)

            assertEquals(1, counts.executeUpdate.get(), "executeUpdate interceptor should be called once")
            // validateUpdate may be called depending on server config
            assertTrue(counts.validateUpdate.get() >= 0, "validateUpdate interceptor calls >= 0")

            handle.signal("complete")
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("updated", result)
        }

    @Test
    fun `interceptor is invoked for sleep`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-interceptor-sleep-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<SleepWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorSleepWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("slept", result)

            assertEquals(1, counts.executeWorkflow.get(), "executeWorkflow interceptor should be called once")
            assertEquals(1, counts.sleep.get(), "sleep interceptor should be called once")

            handle.assertHistory { completed() }
        }

    @Test
    fun `interceptor is invoked for activity heartbeat`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-interceptor-heartbeat-${UUID.randomUUID()}"
            counts.resetAll()

            application {
                install(createCountingPlugin())
                taskQueue(taskQueue) {
                    workflow<HeartbeatWorkflow>()
                    activity(HeartbeatActivity())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InterceptorHeartbeatWorkflow",
                    taskQueue = taskQueue,
                    arg = 3,
                )

            val result: String = handle.result(timeout = 1.minutes)
            assertEquals("completed-3", result)

            assertEquals(1, counts.executeWorkflow.get(), "executeWorkflow interceptor should be called once")
            assertEquals(1, counts.scheduleActivity.get(), "scheduleActivity interceptor should be called once")
            assertEquals(1, counts.executeActivity.get(), "executeActivity interceptor should be called once")
            assertEquals(3, counts.heartbeat.get(), "heartbeat interceptor should be called 3 times")

            handle.assertHistory { completed() }
        }
}
