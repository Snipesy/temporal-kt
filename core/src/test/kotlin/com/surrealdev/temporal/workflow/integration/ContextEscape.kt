package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.startChildWorkflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Tag
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Context escape tests.
 *
 * These tests behavior in workflows when the user code attempts to escape the
 * Temporal coroutine context i.e. with withContext(Dispatchers.IO) { ... }.
 *
 * Due to limitations in the Kotlin coroutines library, we cannot prevent context escapes
 * (see https://github.com/Snipesy/temporal-kt/issues/1) But we can at least detect them by seeing if a workflow
 * executes codes and completes in a non reliable way.
 *
 * These tests verify that our runOnce logic correctly handles escaped coroutines by waiting
 * for them to dispatch back.
 */
@Tag("integration")
class ContextEscape {
    /**
     * Stress test workflow that exercises various context escape patterns.
     */
    @Workflow("ContextEscapeStressTest")
    class ContextEscapeStressTest {
        @WorkflowRun
        suspend fun WorkflowContext.run(): Map<String, String> {
            val results = mutableMapOf<String, String>()

            // 1. Simple context escape to IO
            withContext(Dispatchers.IO) {
                println("Step 1: Dispatchers.IO")
            }
            results["step1"] = "io_complete"

            // 2. Context escape to Default
            withContext(Dispatchers.Default) {
                println("Step 2: Dispatchers.Default")
            }
            results["step2"] = "default_complete"

            // 3. Context escape with work inside
            val computedValue =
                withContext(Dispatchers.Default) {
                    var sum = 0
                    repeat(1000) { sum += it }
                    sum
                }
            results["step3_computed"] = computedValue.toString()

            // 4. Workflow sleep between escapes
            sleep(100.milliseconds)
            results["step4"] = "sleep_complete"

            // 5. Context escape after sleep
            withContext(Dispatchers.IO) {
                println("Step 5: IO after sleep")
            }
            results["step5"] = "io_after_sleep"

            // 6. Nested context escapes
            withContext(Dispatchers.Default) {
                println("Step 6a: Outer Default")
                withContext(Dispatchers.IO) {
                    println("Step 6b: Inner IO")
                }
                println("Step 6c: Back to Default")
            }
            results["step6"] = "nested_complete"

            // 7. Use workflow deterministic operations after escape
            val uuid1 = randomUuid()
            withContext(Dispatchers.Default) {
                println("Step 7: Between UUIDs")
            }
            val uuid2 = randomUuid()
            results["step7_uuid1"] = uuid1
            results["step7_uuid2"] = uuid2

            // 8. Multiple rapid escapes
            repeat(5) { i ->
                withContext(Dispatchers.Default) {
                    println("Step 8: Rapid escape $i")
                }
            }
            results["step8"] = "rapid_escapes_complete"

            // 9. Yield between operations
            yield()
            results["step9"] = "yield_complete"

            // 10. Context escape with delay inside (non-workflow delay - simulates real IO)
            withContext(Dispatchers.IO) {
                delay(50) // kotlinx delay, not workflow sleep
                println("Step 10: IO with delay")
            }
            results["step10"] = "io_with_delay"

            // 11. Another workflow sleep
            sleep(100.milliseconds)
            results["step11"] = "final_sleep"

            // 12. Final context escape
            withContext(Dispatchers.Default) {
                println("Step 12: Final escape")
            }
            results["step12"] = "final_escape"

            return results
        }
    }

    /**
     * Workflow with async operations and context escapes mixed.
     */
    @Workflow("AsyncContextEscapeWorkflow")
    class AsyncContextEscapeWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<String> {
            val results = mutableListOf<String>()

            // Escape, then do async workflow operations
            withContext(Dispatchers.Default) {
                println("Before async operations")
            }
            results.add("escaped")

            // Multiple async operations (these stay on workflow dispatcher)
            val deferred1 =
                async {
                    sleep(50.milliseconds)
                    "async1"
                }
            val deferred2 =
                async {
                    sleep(50.milliseconds)
                    "async2"
                }

            // Escape while asyncs are pending
            withContext(Dispatchers.IO) {
                println("Escaped while asyncs pending")
            }
            results.add("escaped_during_async")

            // Await results
            results.add(deferred1.await())
            results.add(deferred2.await())

            // Final escape
            withContext(Dispatchers.Default) {
                println("Final escape after asyncs")
            }
            results.add("final")

            return results
        }
    }

    @Test
    fun `context escape stress test completes successfully`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-escape-stress-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ContextEscapeStressTest())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Map<String, String>>(
                    workflowType = "ContextEscapeStressTest",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Verify all steps completed
            assertEquals("io_complete", result["step1"])
            assertEquals("default_complete", result["step2"])
            assertEquals("499500", result["step3_computed"]) // sum of 0..999
            assertEquals("sleep_complete", result["step4"])
            assertEquals("io_after_sleep", result["step5"])
            assertEquals("nested_complete", result["step6"])
            assertTrue(result["step7_uuid1"]!!.isNotEmpty())
            assertTrue(result["step7_uuid2"]!!.isNotEmpty())
            assertEquals("rapid_escapes_complete", result["step8"])
            assertEquals("yield_complete", result["step9"])
            assertEquals("io_with_delay", result["step10"])
            assertEquals("final_sleep", result["step11"])
            assertEquals("final_escape", result["step12"])

            handle.assertHistory {
                completed()
                timerCount(2) // Two workflow sleeps
            }
        }

    @Test
    fun `async with context escapes completes successfully`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-async-escape-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(AsyncContextEscapeWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<List<String>>(
                    workflowType = "AsyncContextEscapeWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals(
                listOf("escaped", "escaped_during_async", "async1", "async2", "final"),
                result,
            )

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Simple child workflow for testing.
     */
    @Workflow("EscapeTestChildWorkflow")
    class EscapeTestChildWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            sleep(50.milliseconds)
            return "child:${info.workflowId}"
        }
    }

    /**
     * Workflow that starts child, escapes, then awaits child OUTSIDE the escaped context.
     * This should work correctly.
     */
    @Workflow("ChildAwaitOutsideEscapeWorkflow")
    class ChildAwaitOutsideEscapeWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Start child workflow (returns handle immediately)
            val childHandle =
                startChildWorkflow<String>(
                    "EscapeTestChildWorkflow",
                    ChildWorkflowOptions(),
                )

            // Escape to IO - do some "work"
            withContext(Dispatchers.IO) {
                println("Doing IO work while child is running")
                delay(10)
            }

            // Await child OUTSIDE the escaped context - this should work
            val result = childHandle.result()
            return "parent:$result"
        }
    }

    /**
     * Workflow that escapes to IO and then tries to call a workflow operation (sleep).
     * This SHOULD throw an error because workflow operations shouldn't be called from escaped context.
     */
    @Workflow("WorkflowOpInsideEscapeWorkflow")
    class WorkflowOpInsideEscapeWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Try to call sleep() inside escaped context - this should fail
            withContext(Dispatchers.IO) {
                println("Trying to sleep from wrong dispatcher")
                sleep(100.milliseconds) // This should throw EscapedDispatcherException!
            }

            return "should not reach here"
        }
    }

    @Test
    fun `child workflow await outside escaped context works`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-child-escape-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(EscapeTestChildWorkflow())
                    workflow(ChildAwaitOutsideEscapeWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ChildAwaitOutsideEscapeWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("parent:child:"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `workflow operation inside escaped context should fail`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-escape-op-fail-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(WorkflowOpInsideEscapeWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "WorkflowOpInsideEscapeWorkflow",
                    taskQueue = taskQueue,
                )

            // This should fail - workflow operations from escaped context should throw
            val exception =
                org.junit.jupiter.api.assertThrows<Exception> {
                    handle.result(timeout = 30.seconds)
                }
            // Verify it's our specific exception
            assertTrue(
                exception.message?.contains("TKT1108") == true ||
                    exception.cause?.message?.contains("TKT1108") == true,
                "Expected EscapedDispatcherException with TKT1108, got: ${exception.message}",
            )
            println("Got expected exception: ${exception.message}")
        }

    /**
     * Workflow that escapes to IO, then tries to sneak back by explicitly using the workflow dispatcher.
     * This tests whether someone can circumvent our protection by capturing and reusing the dispatcher.
     */
    @Workflow("SneakyDispatcherWorkflow")
    class SneakyDispatcherWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Capture the workflow's coroutine context (includes the workflow dispatcher)
            val workflowCoroutineContext = coroutineContext

            // Escape to IO
            withContext(Dispatchers.IO) {
                println("Escaped to IO")
                // Now try to sneak back by explicitly using the workflow context
                // SOMEONE WILL TRY THIS
                // anyway we support it. Be happy.
                withContext(workflowCoroutineContext) {
                    println("Snuck back to workflow dispatcher... or did we?")
                    // Try to call a workflow operation - this should work if we're really back
                    sleep(50.milliseconds)
                }
            }

            return "sneaky_success"
        }
    }

    @Test
    fun `sneaking back to workflow dispatcher via withContext should work`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-sneaky-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SneakyDispatcherWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "SneakyDispatcherWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("sneaky_success", result)

            handle.assertHistory {
                completed()
                timerCount(1)
            }
        }
}
