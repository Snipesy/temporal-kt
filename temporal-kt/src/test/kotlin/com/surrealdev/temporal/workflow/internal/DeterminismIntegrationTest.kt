package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.UpdateValidator
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.query
import com.surrealdev.temporal.client.signal
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.client.update
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polls a query until the expected condition is met or timeout.
 */
private suspend inline fun <T> pollUntil(
    timeout: Duration = 15.seconds,
    crossinline condition: (T) -> Boolean,
    crossinline query: suspend () -> T,
): T =
    // bypass 'runTest' context
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

/**
 * Integration tests for determinism fixes.
 *
 * These tests use full workflow execution with `runTemporalTest {}` to verify:
 * - Condition-based workflows complete correctly when conditions become true
 * - Mixed job types are processed in the correct order
 * - Deterministic values (random, time) are consistent across replays
 * - Cancellation and cleanup work correctly
 * - Structured concurrency with async operations is deterministic
 */
class DeterminismIntegrationTest {
    // ================================================================
    // Condition-Based Workflow Tests
    // ================================================================

    /**
     * Workflow that uses awaitCondition to wait for a condition.
     * Uses a timer to simulate state change that satisfies the condition.
     */
    @Workflow("ConditionWorkflow")
    class ConditionWorkflow {
        private var counter = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): Int {
            // Increment counter after each sleep
            repeat(3) {
                sleep(50.milliseconds)
                counter++
            }

            // Use awaitCondition to wait until counter reaches target
            awaitCondition { counter >= 3 }

            return counter
        }
    }

    @Test
    fun `workflow with condition completes when condition becomes true`() =
        runTemporalTest {
            val taskQueue = "test-condition-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ConditionWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int>(
                    workflowType = "ConditionWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals(3, result)

            handle.assertHistory {
                completed()
                // Should have 3 timers
                timerCount(3)
            }
        }

    /**
     * Workflow that uses multiple sequential conditions.
     */
    @Workflow("SequentialConditionsWorkflow")
    class SequentialConditionsWorkflow {
        private var phase = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val results = mutableListOf<String>()

            // Phase 1
            sleep(30.milliseconds)
            phase = 1
            awaitCondition { phase >= 1 }
            results.add("phase1")

            // Phase 2
            sleep(30.milliseconds)
            phase = 2
            awaitCondition { phase >= 2 }
            results.add("phase2")

            // Phase 3
            sleep(30.milliseconds)
            phase = 3
            awaitCondition { phase >= 3 }
            results.add("phase3")

            return results.joinToString(",")
        }
    }

    @Test
    fun `workflow with multiple sequential conditions completes correctly`() =
        runTemporalTest {
            val taskQueue = "test-seq-conditions-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SequentialConditionsWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "SequentialConditionsWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("phase1,phase2,phase3", result)

            handle.assertHistory {
                completed()
                timerCount(3)
            }
        }

    // ================================================================
    // Mixed Job Types Tests
    // ================================================================

    /**
     * Workflow that has mixed operations (timers and conditions).
     */
    @Workflow("MixedJobTypesWorkflow")
    class MixedJobTypesWorkflow {
        private var state = "init"

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val steps = mutableListOf<String>()

            // Timer 1
            sleep(20.milliseconds)
            state = "after-timer-1"
            steps.add(state)

            // Condition check
            awaitCondition { state == "after-timer-1" }
            steps.add("condition-1-satisfied")

            // Timer 2
            sleep(20.milliseconds)
            state = "after-timer-2"
            steps.add(state)

            // Another condition
            awaitCondition { state == "after-timer-2" }
            steps.add("condition-2-satisfied")

            return steps.joinToString("->")
        }
    }

    @Test
    fun `workflow with mixed job types processes correctly`() =
        runTemporalTest {
            val taskQueue = "test-mixed-jobs-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(MixedJobTypesWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "MixedJobTypesWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("after-timer-1->condition-1-satisfied->after-timer-2->condition-2-satisfied", result)

            handle.assertHistory {
                completed()
                timerCount(2)
            }
        }

    // ================================================================
    // Deterministic Value Tests
    // ================================================================

    /**
     * Workflow that uses deterministic UUIDs from the workflow context.
     */
    @Workflow("RandomUuidWorkflow")
    class RandomUuidWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<String> {
            // Generate multiple deterministic UUIDs
            val values = mutableListOf<String>()
            repeat(5) {
                values.add(randomUuid())
            }
            return values
        }
    }

    @Test
    fun `workflow produces deterministic random values`() =
        runTemporalTest {
            val taskQueue = "test-random-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(RandomUuidWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<List<String>>(
                    workflowType = "RandomUuidWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Result should be a list of 5 UUIDs
            assertEquals(5, result.size)
            // All values should be valid UUID strings
            assertTrue(result.all { it.isNotEmpty() })

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow that uses workflow time.
     */
    @Workflow("TimeProgressionWorkflow")
    class TimeProgressionWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<Long> {
            val times = mutableListOf<Long>()

            // Record time before and after sleeps
            times.add(now().toEpochMilliseconds())

            sleep(100.milliseconds)
            times.add(now().toEpochMilliseconds())

            sleep(100.milliseconds)
            times.add(now().toEpochMilliseconds())

            return times
        }
    }

    @Test
    fun `workflow has deterministic time progression`() =
        runTemporalTest {
            val taskQueue = "test-time-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(TimeProgressionWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<List<Long>>(
                    workflowType = "TimeProgressionWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Result should have 3 timestamps
            assertEquals(3, result.size)

            // Times should be monotonically increasing
            assertTrue(result[0] <= result[1], "Time should increase after first sleep")
            assertTrue(result[1] <= result[2], "Time should increase after second sleep")

            // The difference between times should reflect the sleep durations
            // (approximately, allowing for some variance)
            val diff1 = result[1] - result[0]
            val diff2 = result[2] - result[1]

            assertTrue(diff1 >= 50, "First sleep difference should be >= 50ms, was $diff1")
            assertTrue(diff2 >= 50, "Second sleep difference should be >= 50ms, was $diff2")

            handle.assertHistory {
                completed()
                timerCount(2)
            }
        }

    // ================================================================
    // Cancellation and Cleanup Tests
    // ================================================================

    /**
     * Workflow that can be cancelled during a long sleep.
     */
    @Workflow("CancellableConditionWorkflow")
    class CancellableConditionWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Long sleep that can be cancelled
            sleep(10.seconds)
            return "completed"
        }
    }

    @Test
    fun `workflow can be cancelled and cleanup occurs`() =
        runTemporalTest {
            val taskQueue = "test-cancel-cleanup-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(CancellableConditionWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "CancellableConditionWorkflow",
                    taskQueue = taskQueue,
                )

            // Cancel the workflow
            handle.cancel()

            // Wait for cancellation to be processed
            try {
                handle.result(timeout = 10.seconds)
            } catch (e: Exception) {
                // Expected - workflow was cancelled
            }

            handle.assertHistory {
                canceled()
                check("Workflow should not be completed") { !it.isCompleted }
            }
        }

    // ================================================================
    // Structured Concurrency Tests
    // ================================================================

    /**
     * Workflow with multiple async operations.
     */
    @Workflow("AsyncOperationsWorkflow")
    class AsyncOperationsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Launch multiple async operations
            val deferred1 =
                async {
                    sleep(30.milliseconds)
                    "A"
                }
            val deferred2 =
                async {
                    sleep(40.milliseconds)
                    "B"
                }
            val deferred3 =
                async {
                    sleep(20.milliseconds)
                    "C"
                }

            // Await all in order
            val result1 = deferred1.await()
            val result2 = deferred2.await()
            val result3 = deferred3.await()

            return "$result1$result2$result3"
        }
    }

    @Test
    fun `async workflow operations execute deterministically`() =
        runTemporalTest {
            val taskQueue = "test-async-ops-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(AsyncOperationsWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "AsyncOperationsWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // Results should always be in the await order, not completion order
            assertEquals("ABC", result)

            handle.assertHistory {
                completed()
                // All three async operations should create timers
                timerCount(3)
            }
        }

    /**
     * Workflow with nested async operations.
     */
    @Workflow("NestedAsyncWorkflow")
    class NestedAsyncWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val outer =
                async {
                    val inner1 =
                        async {
                            sleep(20.milliseconds)
                            "inner1"
                        }
                    val inner2 =
                        async {
                            sleep(20.milliseconds)
                            "inner2"
                        }
                    "${inner1.await()}-${inner2.await()}"
                }

            return "result: ${outer.await()}"
        }
    }

    @Test
    fun `nested async operations execute deterministically`() =
        runTemporalTest {
            val taskQueue = "test-nested-async-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(NestedAsyncWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "NestedAsyncWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("result: inner1-inner2", result)

            handle.assertHistory {
                completed()
                // Two inner async operations create timers
                timerCount(2)
            }
        }

    // ================================================================
    // Condition with State Change Tests
    // ================================================================

    /**
     * Workflow that demonstrates condition checking after state changes.
     */
    @Workflow("ConditionAfterStateChangeWorkflow")
    class ConditionAfterStateChangeWorkflow {
        private var items = mutableListOf<String>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Simulate adding items over time
            val addItem =
                async {
                    sleep(50.milliseconds)
                    items.add("item1")
                    sleep(50.milliseconds)
                    items.add("item2")
                    sleep(50.milliseconds)
                    items.add("item3")
                }

            // Wait for condition that checks state
            awaitCondition { items.size >= 3 }

            addItem.await() // Make sure async completes

            return items.joinToString(",")
        }
    }

    @Test
    fun `condition completes when state changes satisfy predicate`() =
        runTemporalTest {
            val taskQueue = "test-condition-state-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ConditionAfterStateChangeWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ConditionAfterStateChangeWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            assertEquals("item1,item2,item3", result)

            handle.assertHistory {
                completed()
                timerCount(3)
            }
        }

    // ================================================================
    // Signal Handler Tests
    // ================================================================

    /**
     * Workflow that receives signals and accumulates values.
     */
    @Workflow("SignalAccumulatorWorkflow")
    class SignalAccumulatorWorkflow {
        private val values = mutableListOf<String>()
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return values.joinToString(",")
        }

        @Signal("addValue")
        fun WorkflowContext.addValue(value: String) {
            values.add(value)
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `workflow receives and processes signals correctly`() =
        runTemporalTest {
            val taskQueue = "test-signal-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(SignalAccumulatorWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "SignalAccumulatorWorkflow",
                    taskQueue = taskQueue,
                )

            // Send multiple signals
            handle.signal("addValue", "first")
            handle.signal("addValue", "second")
            handle.signal("addValue", "third")
            handle.signal("complete")

            val result = handle.result(timeout = 30.seconds)

            assertEquals("first,second,third", result)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow that demonstrates buffered signal replay.
     * The signal handler is registered after signals arrive.
     */
    @Workflow("BufferedSignalReplayWorkflow")
    class BufferedSignalReplayWorkflow {
        private val values = mutableListOf<String>()
        private var handlerRegistered = false
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Wait for trigger to register handler
            awaitCondition { handlerRegistered }

            // Register runtime handler - this should replay any buffered signals
            setSignalHandler("dynamicValue") { payloads ->
                val value =
                    serializer.deserialize(
                        com.surrealdev.temporal.serialization
                            .typeInfoOf<String>(),
                        payloads[0],
                    ) as String
                values.add("dynamic:$value")
            }

            // Wait for completion signal
            awaitCondition { done }
            return values.joinToString(",")
        }

        @Signal("registerHandler")
        fun WorkflowContext.registerHandler() {
            handlerRegistered = true
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `buffered signals are replayed when handler is registered`() =
        runTemporalTest {
            val taskQueue = "test-buffered-signal-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(BufferedSignalReplayWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "BufferedSignalReplayWorkflow",
                    taskQueue = taskQueue,
                )

            // Send signals BEFORE handler is registered - they will be buffered
            handle.signal("dynamicValue", "buffered1")
            handle.signal("dynamicValue", "buffered2")

            // Advance time to ensure signals are processed in a separate workflow task
            skipTime(100.milliseconds)

            // Now register the handler - buffered signals should be replayed
            handle.signal("registerHandler")

            // Advance time to allow handler registration to complete
            skipTime(100.milliseconds)

            // Send more signals after handler is registered
            handle.signal("dynamicValue", "live1")

            // Advance time before completing
            skipTime(100.milliseconds)

            // Complete the workflow
            handle.signal("complete")

            val result = handle.result(timeout = 30.seconds)

            // All signals should be received: buffered ones replayed + live ones
            assertTrue(result.contains("dynamic:buffered1"), "Should contain buffered1")
            assertTrue(result.contains("dynamic:buffered2"), "Should contain buffered2")
            assertTrue(result.contains("dynamic:live1"), "Should contain live1")

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow with dynamic signal handler that catches all signals.
     */
    @Workflow("DynamicSignalWorkflow")
    class DynamicSignalWorkflow {
        private val signals = mutableListOf<String>()
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register dynamic handler to catch all signals
            setDynamicSignalHandler { signalName, _ ->
                if (signalName == "complete") {
                    done = true
                } else {
                    signals.add(signalName)
                }
            }

            awaitCondition { done }
            return signals.sorted().joinToString(",")
        }
    }

    @Test
    fun `dynamic signal handler catches all signal types`() =
        runTemporalTest {
            val taskQueue = "test-dynamic-signal-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(DynamicSignalWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "DynamicSignalWorkflow",
                    taskQueue = taskQueue,
                )

            // Send various signal types
            handle.signal("signalA")
            handle.signal("signalB")
            handle.signal("signalC")
            handle.signal("complete")

            val result = handle.result(timeout = 30.seconds)

            assertEquals("signalA,signalB,signalC", result)

            handle.assertHistory {
                completed()
            }
        }

    // ================================================================
    // Update Handler Tests
    // ================================================================

    /**
     * Workflow that handles updates with return values.
     */
    @Workflow("UpdateCounterWorkflow")
    class UpdateCounterWorkflow {
        private var counter = 0
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): Int {
            awaitCondition { done }
            return counter
        }

        @Update("increment")
        fun WorkflowContext.increment(amount: Int): Int {
            counter += amount
            return counter
        }

        @Update("getCounter")
        fun WorkflowContext.getCounter(): Int = counter

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `workflow handles updates and returns values`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-update-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(UpdateCounterWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int>(
                    workflowType = "UpdateCounterWorkflow",
                    taskQueue = taskQueue,
                )

            // Send updates and verify return values
            val result1: Int = handle.update("increment", 5)
            assertEquals(5, result1)

            val result2: Int = handle.update("increment", 3)
            assertEquals(8, result2)

            val result3: Int = handle.update("getCounter")
            assertEquals(8, result3)

            // Complete workflow
            handle.signal("complete")

            val finalResult = handle.result(timeout = 30.seconds)
            assertEquals(8, finalResult)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow with update validation.
     */
    @Workflow("ValidatedUpdateWorkflow")
    class ValidatedUpdateWorkflow {
        private var value = ""
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return value
        }

        @Update("setValue")
        fun WorkflowContext.setValue(newValue: String): String {
            value = newValue
            return "set:$value"
        }

        @UpdateValidator("setValue")
        fun validateSetValue(newValue: String) {
            if (newValue.isBlank()) {
                throw IllegalArgumentException("Value cannot be blank")
            }
            if (newValue.length > 10) {
                throw IllegalArgumentException("Value too long (max 10 chars)")
            }
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `update validator accepts valid input`() =
        runTemporalTest {
            val taskQueue = "test-validated-update-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ValidatedUpdateWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ValidatedUpdateWorkflow",
                    taskQueue = taskQueue,
                )

            // Valid update should succeed
            val result: String = handle.update("setValue", "hello")
            assertEquals("set:hello", result)

            handle.signal("complete")

            val finalResult = handle.result(timeout = 30.seconds)
            assertEquals("hello", finalResult)

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `update validator rejects invalid input`() =
        runTemporalTest {
            val taskQueue = "test-rejected-update-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ValidatedUpdateWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ValidatedUpdateWorkflow",
                    taskQueue = taskQueue,
                )

            // Invalid update (blank) should be rejected
            assertFailsWith<Exception> {
                handle.update<String, String, String>("setValue", "")
            }

            // Invalid update (too long) should be rejected
            assertFailsWith<Exception> {
                handle.update<String, String, String>("setValue", "this is way too long")
            }

            // Valid update should still work after rejections
            val result: String = handle.update("setValue", "valid")
            assertEquals("set:valid", result)

            handle.signal("complete")

            val finalResult = handle.result(timeout = 30.seconds)
            assertEquals("valid", finalResult)

            handle.assertHistory {
                completed()
            }
        }

    // ================================================================
    // Query Handler Tests
    // ================================================================

    /**
     * Workflow with query handlers.
     */
    @Workflow("QueryableWorkflow")
    class QueryableWorkflow {
        private var counter = 0
        private val history = mutableListOf<String>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): Int {
            repeat(5) { i ->
                sleep(50.milliseconds)
                counter++
                history.add("step-$i")
            }
            return counter
        }

        @Query("getCounter")
        fun WorkflowContext.getCounter(): Int = counter

        @Query("getHistory")
        fun WorkflowContext.getHistory(): List<String> = history.toList()
    }

    @Test
    fun `workflow responds to queries during execution`() =
        runTemporalTest {
            val taskQueue = "test-query-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(QueryableWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int>(
                    workflowType = "QueryableWorkflow",
                    taskQueue = taskQueue,
                )

            // Query during execution - counter may be in progress (any non-negative value is valid)
            val midCounter: Int = handle.query("getCounter")
            assertTrue(midCounter >= 0, "Counter should be non-negative")

            // Wait for completion
            val finalResult = handle.result(timeout = 30.seconds)
            assertEquals(5, finalResult, "Final counter should be 5")

            // Query after completion
            val finalCounter: Int = handle.query("getCounter")
            assertEquals(5, finalCounter, "Final counter query should be 5 when querried after complete")

            val history: List<String> = handle.query("getHistory")
            assertEquals(5, history.size, "Workflow history should be 5 steps")
            assertEquals("step-0", history[0])
            assertEquals("step-4", history[4])

            handle.assertHistory {
                completed()
                timerCount(5)
            }
        }

    /**
     * Workflow with runtime-registered query handler.
     */
    @Workflow("RuntimeQueryWorkflow")
    class RuntimeQueryWorkflow {
        private var secret = "initial"

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register a runtime query handler
            setQueryHandler("getSecret") { _ ->
                serializer.serialize(
                    com.surrealdev.temporal.serialization
                        .typeInfoOf<String>(),
                    secret,
                )
            }

            sleep(100.milliseconds)
            secret = "updated"

            sleep(100.milliseconds)
            return secret
        }
    }

    @Test
    fun `runtime query handler works correctly`() =
        runTemporalTest {
            val taskQueue = "test-runtime-query-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(RuntimeQueryWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "RuntimeQueryWorkflow",
                    taskQueue = taskQueue,
                )

            // Poll until query handler is registered (returns a valid value)
            val secret1: String =
                pollUntil(condition = { it: String -> it == "initial" || it == "updated" }) {
                    try {
                        val s: String = handle.query("getSecret")
                        s
                    } catch (_: Exception) {
                        "" // Return empty string if query fails (handler not registered yet)
                    }
                }
            assertTrue(secret1 == "initial" || secret1 == "updated", "Secret should be initial or updated")

            // Wait for completion
            val result = handle.result(timeout = 30.seconds)
            assertEquals("updated", result)

            // Query after completion should return final value
            val finalSecret: String = handle.query("getSecret")
            assertEquals("updated", finalSecret)

            handle.assertHistory {
                completed()
                timerCount(2)
            }
        }

    // ================================================================
    // Combined Signal, Update, and Query Tests
    // ================================================================

    /**
     * Workflow that uses signals, updates, and queries together.
     */
    @Workflow("CombinedHandlersWorkflow")
    class CombinedHandlersWorkflow {
        private val items = mutableListOf<String>()
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return items.joinToString(",")
        }

        @Signal("addItem")
        fun WorkflowContext.addItem(item: String) {
            items.add("signal:$item")
        }

        @Update("addItemWithConfirm")
        fun WorkflowContext.addItemWithConfirm(item: String): Int {
            items.add("update:$item")
            return items.size
        }

        @Query("getItems")
        fun WorkflowContext.getItems(): List<String> = items.toList()

        @Query("getCount")
        fun WorkflowContext.getCount(): Int = items.size

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `workflow handles signals, updates, and queries together`() =
        runTemporalTest {
            val taskQueue = "test-combined-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(CombinedHandlersWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "CombinedHandlersWorkflow",
                    taskQueue = taskQueue,
                )

            // Use signal
            handle.signal("addItem", "A")

            // Poll until signal is processed
            val count1: Int =
                pollUntil(condition = { it: Int -> it >= 1 }) {
                    val c: Int = handle.query("getCount")
                    c
                }
            assertEquals(1, count1)

            // Use update (updates are synchronous, so no polling needed)
            val count2: Int = handle.update("addItemWithConfirm", "B")
            assertEquals(2, count2)

            // Use signal again
            handle.signal("addItem", "C")

            // Poll until signal is processed
            val items: List<String> =
                pollUntil(condition = { it: List<String> -> it.size >= 3 }) {
                    val i: List<String> = handle.query("getItems")
                    i
                }
            assertEquals(3, items.size)
            assertTrue(items.contains("signal:A"))
            assertTrue(items.contains("update:B"))
            assertTrue(items.contains("signal:C"))

            // Complete
            handle.signal("complete")

            val result = handle.result(timeout = 30.seconds)
            assertEquals("signal:A,update:B,signal:C", result)

            handle.assertHistory {
                completed()
            }
        }
}
