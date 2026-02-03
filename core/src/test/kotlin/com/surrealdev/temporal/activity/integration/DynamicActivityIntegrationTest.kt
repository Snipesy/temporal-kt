package com.surrealdev.temporal.activity.integration

import com.surrealdev.temporal.activity.heartbeat
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.RetryPolicy
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for dynamic activity functionality.
 */
@Tag("integration")
class DynamicActivityIntegrationTest {
    /**
     * A workflow that calls activities by type name.
     * Some of these activity types will be handled by the dynamic handler.
     */
    @Workflow("DynamicActivityTestWorkflow")
    class DynamicActivityTestWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(
            activityType: String,
            input: String,
        ): String =
            startActivity<String, String>(
                activityType = activityType,
                arg = input,
                options = ActivityOptions(startToCloseTimeout = 1.minutes),
            ).result()
    }

    /**
     * A workflow that calls multiple dynamic activities.
     */
    @Workflow("MultiDynamicActivityWorkflow")
    class MultiDynamicActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(inputs: List<String>): List<String> {
            val results = mutableListOf<String>()
            for (input in inputs) {
                val result =
                    startActivity<String, String>(
                        activityType = "dynamicEcho",
                        arg = input,
                        options = ActivityOptions(startToCloseTimeout = 1.minutes),
                    ).result()
                results.add(result)
            }
            return results
        }
    }

    /**
     * A static activity to verify registered activities take precedence.
     */
    class StaticActivities {
        @Activity("staticGreet")
        fun greet(name: String): String = "Static: Hello, $name!"
    }

    @Test
    fun `dynamic activity handles unregistered activity type`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "dynamic-activity-test-${UUID.randomUUID()}"
            val dynamicResults = mutableListOf<String>()

            application {
                taskQueue(taskQueue) {
                    workflow<DynamicActivityTestWorkflow>()

                    dynamicActivity { activityType, payloads ->
                        dynamicResults.add("called: $activityType")
                        val input = payloads.decode<String>(0)
                        serializer.serialize("Dynamic: $input")
                    }
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String, String, String>(
                    workflowType = "DynamicActivityTestWorkflow",
                    taskQueue = taskQueue,
                    arg1 = "unregisteredActivity",
                    arg2 = "test-input",
                )

            val result = handle.result(timeout = 1.minutes)

            assertEquals("Dynamic: test-input", result)
            assertTrue(dynamicResults.contains("called: unregisteredActivity"))
        }

    @Test
    fun `registered activity takes precedence over dynamic handler`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "dynamic-precedence-test-${UUID.randomUUID()}"
            var dynamicCalled = false

            application {
                taskQueue(taskQueue) {
                    workflow<DynamicActivityTestWorkflow>()
                    activity(StaticActivities())

                    dynamicActivity { activityType, payloads ->
                        dynamicCalled = true
                        val input = payloads.decode<String>(0)
                        serializer.serialize("Dynamic: $input")
                    }
                }
            }

            val client = client()

            // Call the statically registered activity
            val handle =
                client.startWorkflow<String, String, String>(
                    workflowType = "DynamicActivityTestWorkflow",
                    taskQueue = taskQueue,
                    arg1 = "staticGreet",
                    arg2 = "World",
                )

            val result = handle.result(timeout = 1.minutes)

            // Should use the static activity, not the dynamic handler
            assertEquals("Static: Hello, World!", result)
            assertEquals(false, dynamicCalled, "Dynamic handler should not be called for registered activity")
        }

    @Test
    fun `dynamic activity can route based on activity type`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "dynamic-routing-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<DynamicActivityTestWorkflow>()

                    dynamicActivity { activityType, payloads ->
                        val input = payloads.decode<String>(0)
                        val result =
                            when (activityType) {
                                "dynamicUpper" -> input.uppercase()
                                "dynamicLower" -> input.lowercase()
                                "dynamicReverse" -> input.reversed()
                                else -> "unknown: $input"
                            }
                        serializer.serialize(result)
                    }
                }
            }

            val client = client()

            // Test uppercase
            val upperHandle =
                client.startWorkflow<String, String, String>(
                    workflowType = "DynamicActivityTestWorkflow",
                    taskQueue = taskQueue,
                    arg1 = "dynamicUpper",
                    arg2 = "hello",
                )
            assertEquals("HELLO", upperHandle.result(timeout = 1.minutes))

            // Test lowercase
            val lowerHandle =
                client.startWorkflow<String, String, String>(
                    workflowType = "DynamicActivityTestWorkflow",
                    taskQueue = taskQueue,
                    arg1 = "dynamicLower",
                    arg2 = "WORLD",
                )
            assertEquals("world", lowerHandle.result(timeout = 1.minutes))

            // Test reverse
            val reverseHandle =
                client.startWorkflow<String, String, String>(
                    workflowType = "DynamicActivityTestWorkflow",
                    taskQueue = taskQueue,
                    arg1 = "dynamicReverse",
                    arg2 = "abc",
                )
            assertEquals("cba", reverseHandle.result(timeout = 1.minutes))
        }

    @Test
    fun `dynamic activity can access activity context`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "dynamic-context-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<DynamicActivityTestWorkflow>()

                    dynamicActivity { activityType, payloads ->
                        // Access activity info from context
                        val activityInfo = info
                        val result = "type=${activityInfo.activityType}, attempt=${activityInfo.attempt}"
                        serializer.serialize(result)
                    }
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String, String, String>(
                    workflowType = "DynamicActivityTestWorkflow",
                    taskQueue = taskQueue,
                    arg1 = "contextTest",
                    arg2 = "ignored",
                )

            val result = handle.result(timeout = 1.minutes)

            assertTrue(result.contains("type=contextTest"))
            assertTrue(result.contains("attempt=1"))
        }

    @Test
    fun `dynamic activity handles multiple payloads`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "dynamic-multi-payload-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<MultiDynamicActivityWorkflow>()

                    dynamicActivity { activityType, payloads ->
                        val input = payloads.decode<String>(0)
                        serializer.serialize("echo: $input")
                    }
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<List<String>, List<String>>(
                    workflowType = "MultiDynamicActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = listOf("a", "b", "c"),
                )

            val results = handle.result(timeout = 1.minutes)

            assertEquals(listOf("echo: a", "echo: b", "echo: c"), results)
        }

    // ================================================================
    // Additional Edge Case Tests
    // ================================================================

    /**
     * Workflow that calls a void activity (returns Unit/null).
     */
    @Workflow("DynamicVoidActivityWorkflow")
    class DynamicVoidActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            startActivity<Unit, String>(
                activityType = "dynamicVoid",
                arg = "ignored",
                options = ActivityOptions(startToCloseTimeout = 1.minutes),
            ).result()
            return "completed"
        }
    }

    /**
     * Workflow that calls a long-running activity with heartbeat.
     */
    @Workflow("DynamicHeartbeatWorkflow")
    class DynamicHeartbeatWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(itemCount: Int): String =
            startActivity<String, Int>(
                activityType = "dynamicHeartbeating",
                arg = itemCount,
                options =
                    ActivityOptions(
                        startToCloseTimeout = 1.minutes,
                        heartbeatTimeout = 10.seconds,
                    ),
            ).result()
    }

    /**
     * Workflow that calls an activity with retry support (for testing heartbeat details on retry).
     */
    @Workflow("DynamicRetryActivityWorkflow")
    class DynamicRetryActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(
            activityType: String,
            input: String,
        ): String =
            startActivity<String, String>(
                activityType = activityType,
                arg = input,
                options =
                    ActivityOptions(
                        startToCloseTimeout = 1.minutes,
                        heartbeatTimeout = 10.seconds,
                        retryPolicy = RetryPolicy(maximumAttempts = 3),
                    ),
            ).result()
    }

    @Test
    fun `dynamic activity can send heartbeats`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "dynamic-heartbeat-test-${UUID.randomUUID()}"
            val heartbeatValues = mutableListOf<Int>()

            application {
                taskQueue(taskQueue) {
                    workflow<DynamicHeartbeatWorkflow>()

                    dynamicActivity { activityType, payloads ->
                        val itemCount = payloads.decode<Int>(0)
                        for (i in 1..itemCount) {
                            delay(10)
                            heartbeat(i) // Send heartbeat with progress
                            heartbeatValues.add(i)
                        }
                        serializer.serialize("processed $itemCount items")
                    }
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String, Int>(
                    workflowType = "DynamicHeartbeatWorkflow",
                    taskQueue = taskQueue,
                    arg = 5,
                )

            val result = handle.result(timeout = 1.minutes)

            assertEquals("processed 5 items", result)
            assertEquals(listOf(1, 2, 3, 4, 5), heartbeatValues)
        }

    @Test
    fun `dynamic activity can return null for void activities`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "dynamic-void-test-${UUID.randomUUID()}"
            var activityExecuted = false

            application {
                taskQueue(taskQueue) {
                    workflow<DynamicVoidActivityWorkflow>()

                    dynamicActivity { activityType, payloads ->
                        activityExecuted = true
                        null // Return null for void activity
                    }
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String>(
                    workflowType = "DynamicVoidActivityWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 1.minutes)

            assertEquals("completed", result)
            assertTrue(activityExecuted, "Dynamic activity should have been executed")
        }

    @Test
    fun `dynamic activity has access to heartbeat details from previous attempt`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "dynamic-heartbeat-details-test-${UUID.randomUUID()}"
            var attemptNumber = 0

            application {
                taskQueue(taskQueue) {
                    workflow<DynamicRetryActivityWorkflow>()

                    dynamicActivity { activityType, payloads ->
                        attemptNumber++
                        val previousProgress = info.heartbeatDetails?.get<Int>()

                        if (attemptNumber == 1) {
                            // First attempt: heartbeat progress then fail
                            heartbeat(42)
                            throw RuntimeException("Failing first attempt")
                        }

                        // Second attempt: check we got the heartbeat details
                        val result = "attempt=$attemptNumber, previousProgress=$previousProgress"
                        serializer.serialize(result)
                    }
                }
            }

            val client = client()

            val handle =
                client.startWorkflow<String, String, String>(
                    workflowType = "DynamicRetryActivityWorkflow",
                    taskQueue = taskQueue,
                    arg1 = "dynamicWithRetry",
                    arg2 = "input",
                )

            val result = handle.result(timeout = 1.minutes)

            assertEquals(2, attemptNumber)
            assertTrue(result.contains("attempt=2"))
            assertTrue(result.contains("previousProgress=42"))
        }
}
