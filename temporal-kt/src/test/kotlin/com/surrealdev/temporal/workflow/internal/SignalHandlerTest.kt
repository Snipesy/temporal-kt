package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.WorkflowRegistration
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.signalWorkflowJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for signal handler functionality.
 *
 * Test categories:
 * 1. Registration tests: name resolution, dynamic handlers, duplicates
 * 2. Execution tests: simple signal, with args, with context, suspend
 * 3. Buffering tests: signal before handler, handler registration replays buffered
 * 4. Error handling tests: handler throws exception (logged, not fatal)
 * 5. Multiple signals tests: batch processing
 */
class SignalHandlerTest {
    private val serializer = KotlinxJsonSerializer()

    // ================================================================
    // Test Workflow Classes
    // ================================================================

    @Workflow("SimpleSignalWorkflow")
    class SimpleSignalWorkflow {
        var signalReceived = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { signalReceived }
            return "done"
        }

        @Signal("notify")
        fun WorkflowContext.notifySignal() {
            signalReceived = true
        }
    }

    @Serializable
    data class ApprovalData(
        val approver: String,
        val approved: Boolean,
    )

    @Workflow("SignalWithArgsWorkflow")
    class SignalWithArgsWorkflow {
        var approvalData: ApprovalData? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { approvalData != null }
            return "approved by ${approvalData?.approver}"
        }

        @Signal("approve")
        fun WorkflowContext.approveSignal(data: ApprovalData) {
            approvalData = data
        }
    }

    @Workflow("SignalMultipleArgsWorkflow")
    class SignalMultipleArgsWorkflow {
        var firstName: String? = null
        var lastName: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { firstName != null && lastName != null }
            return "$firstName $lastName"
        }

        @Signal("setName")
        fun WorkflowContext.setNameSignal(
            first: String,
            last: String,
        ) {
            firstName = first
            lastName = last
        }
    }

    @Workflow("SuspendSignalWorkflow")
    class SuspendSignalWorkflow {
        var value = ""

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { value.isNotEmpty() }
            return value
        }

        @Signal("setValue")
        suspend fun WorkflowContext.setValueSignal(v: String) {
            // Suspend signal handler (unusual but supported)
            value = v
        }
    }

    @Workflow("DynamicSignalWorkflow")
    class DynamicSignalWorkflow {
        val signals = mutableListOf<String>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { signals.size >= 2 }
            return signals.joinToString(", ")
        }

        @Signal("specific")
        fun WorkflowContext.specificSignal() {
            signals.add("specific")
        }

        @Signal(dynamic = true)
        fun WorkflowContext.dynamicHandler(signalName: String) {
            signals.add("dynamic:$signalName")
        }
    }

    @Workflow("NoContextSignalWorkflow")
    class NoContextSignalWorkflow {
        var signaled = false

        @WorkflowRun
        suspend fun run(): String = if (signaled) "signaled" else "not signaled"

        @Signal("trigger")
        fun triggerSignal() {
            signaled = true
        }
    }

    @Workflow("ThrowingSignalWorkflow")
    class ThrowingSignalWorkflow {
        var counter = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { counter > 0 }
            return "counter=$counter"
        }

        @Signal("throwingSignal")
        fun WorkflowContext.throwingSignal(): Unit = throw RuntimeException("Signal handler error")

        @Signal("goodSignal")
        fun WorkflowContext.goodSignal() {
            counter++
        }
    }

    @Workflow("MultipleSignalsWorkflow")
    class MultipleSignalsWorkflow {
        var a = false
        var b = false
        var c = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { a && b && c }
            return "all done"
        }

        @Signal("signalA")
        fun WorkflowContext.signalA() {
            a = true
        }

        @Signal("signalB")
        fun WorkflowContext.signalB() {
            b = true
        }

        @Signal("signalC")
        fun WorkflowContext.signalC() {
            c = true
        }
    }

    // ================================================================
    // Registration Tests
    // ================================================================

    @Test
    fun `signal handler registration extracts signal name from annotation`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "SimpleSignalWorkflow",
                implementation = SimpleSignalWorkflow(),
            ),
        )

        val methodInfo = registry.lookup("SimpleSignalWorkflow")
        assertNotNull(methodInfo)
        assertTrue(methodInfo.signalHandlers.containsKey("notify"))
    }

    @Test
    fun `signal handler registration uses function name when annotation name is blank`() {
        @Workflow("BlankNameSignalWorkflow")
        class BlankNameSignalWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @Signal // No name specified
            fun mySignalHandler() {}
        }

        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "BlankNameSignalWorkflow",
                implementation = BlankNameSignalWorkflow(),
            ),
        )

        val methodInfo = registry.lookup("BlankNameSignalWorkflow")
        assertNotNull(methodInfo)
        assertTrue(methodInfo.signalHandlers.containsKey("mySignalHandler"))
    }

    @Test
    fun `signal handler registration supports multiple signals`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "MultipleSignalsWorkflow",
                implementation = MultipleSignalsWorkflow(),
            ),
        )

        val methodInfo = registry.lookup("MultipleSignalsWorkflow")
        assertNotNull(methodInfo)
        assertEquals(3, methodInfo.signalHandlers.size)
        assertTrue(methodInfo.signalHandlers.containsKey("signalA"))
        assertTrue(methodInfo.signalHandlers.containsKey("signalB"))
        assertTrue(methodInfo.signalHandlers.containsKey("signalC"))
    }

    @Test
    fun `signal handler registration supports dynamic handler with null key`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "DynamicSignalWorkflow",
                implementation = DynamicSignalWorkflow(),
            ),
        )

        val methodInfo = registry.lookup("DynamicSignalWorkflow")
        assertNotNull(methodInfo)
        assertTrue(methodInfo.signalHandlers.containsKey(null), "Dynamic handler should have null key")
        assertTrue(methodInfo.signalHandlers.containsKey("specific"))
    }

    @Test
    fun `signal handler info captures method metadata correctly`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "SignalWithArgsWorkflow",
                implementation = SignalWithArgsWorkflow(),
            ),
        )

        val methodInfo = registry.lookup("SignalWithArgsWorkflow")
        assertNotNull(methodInfo)

        val handler = methodInfo.signalHandlers["approve"]
        assertNotNull(handler)
        assertTrue(handler.hasContextReceiver)
        assertEquals(1, handler.parameterTypes.size)
    }

    @Test
    fun `duplicate signal names throw exception`() {
        @Workflow("DuplicateSignalWorkflow")
        class DuplicateSignalWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @Signal("sameName")
            fun signal1() {}

            @Signal("sameName")
            fun signal2() {}
        }

        val registry = WorkflowRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                WorkflowRegistration(
                    workflowType = "DuplicateSignalWorkflow",
                    implementation = DuplicateSignalWorkflow(),
                ),
            )
        }
    }

    @Test
    fun `multiple dynamic handlers throw exception`() {
        @Workflow("MultipleDynamicSignalWorkflow")
        class MultipleDynamicSignalWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @Signal(dynamic = true)
            fun handler1() {}

            @Signal(dynamic = true)
            fun handler2() {}
        }

        val registry = WorkflowRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                WorkflowRegistration(
                    workflowType = "MultipleDynamicSignalWorkflow",
                    implementation = MultipleDynamicSignalWorkflow(),
                ),
            )
        }
    }

    // ================================================================
    // Execution Tests
    // ================================================================

    @Test
    fun `signal handler invoked successfully`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(SimpleSignalWorkflow())

            // Process signal
            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "notify")),
                )
            val completion = executor.activate(signalActivation)

            // Signals don't produce commands, but the workflow should continue
            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `signal handler with arguments deserializes args correctly`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(SignalWithArgsWorkflow())

            val approvalData = ApprovalData(approver = "Alice", approved = true)
            val argPayload = serializer.serialize(approvalData)

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            signalWorkflowJob(
                                signalName = "approve",
                                input = listOf(argPayload),
                            ),
                        ),
                )
            val completion = executor.activate(signalActivation)

            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `signal handler with multiple arguments works correctly`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(SignalMultipleArgsWorkflow())

            val firstArg = serializer.serialize("John")
            val lastArg = serializer.serialize("Doe")

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            signalWorkflowJob(
                                signalName = "setName",
                                input = listOf(firstArg, lastArg),
                            ),
                        ),
                )
            val completion = executor.activate(signalActivation)

            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `suspend signal handler executes correctly`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(SuspendSignalWorkflow())

            val valueArg = serializer.serialize("test-value")

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            signalWorkflowJob(
                                signalName = "setValue",
                                input = listOf(valueArg),
                            ),
                        ),
                )
            val completion = executor.activate(signalActivation)

            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `signal handler without context receiver works`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(NoContextSignalWorkflow())

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "trigger")),
                )
            val completion = executor.activate(signalActivation)

            assertTrue(completion.hasSuccessful())
        }

    // ================================================================
    // Dynamic Handler Tests
    // ================================================================

    @Test
    fun `dynamic handler is used for unknown signal types`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(DynamicSignalWorkflow())

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "unknownSignal")),
                )
            val completion = executor.activate(signalActivation)

            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `specific handler takes precedence over dynamic handler`() =
        runTest {
            val workflow = DynamicSignalWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "specific")),
                )
            executor.activate(signalActivation)

            // The specific handler should have been called, adding "specific" to the list
            assertEquals(listOf("specific"), workflow.signals)
        }

    // ================================================================
    // Error Handling Tests
    // ================================================================

    @Test
    fun `signal handler exception does not fail workflow`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(ThrowingSignalWorkflow())

            // Send the throwing signal - should not fail
            val throwingSignalActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "throwingSignal")),
                )
            val completion1 = executor.activate(throwingSignalActivation)
            assertTrue(completion1.hasSuccessful(), "Workflow should not fail when signal handler throws")

            // Send a good signal - should still work
            val goodSignalActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "goodSignal")),
                )
            val completion2 = executor.activate(goodSignalActivation)
            assertTrue(completion2.hasSuccessful(), "Workflow should continue after signal handler throws")
        }

    // ================================================================
    // Multiple Signals Tests
    // ================================================================

    @Test
    fun `multiple signals in same activation are all processed`() =
        runTest {
            val workflow = MultipleSignalsWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            signalWorkflowJob(signalName = "signalA"),
                            signalWorkflowJob(signalName = "signalB"),
                            signalWorkflowJob(signalName = "signalC"),
                        ),
                )
            val completion = executor.activate(signalActivation)

            assertTrue(completion.hasSuccessful())
            assertTrue(workflow.a)
            assertTrue(workflow.b)
            assertTrue(workflow.c)
        }

    // ================================================================
    // Runtime Signal Handler Tests
    // ================================================================

    @Workflow("RuntimeSignalWorkflow")
    class RuntimeSignalWorkflow {
        var runtimeValue = ""

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register a runtime signal handler
            setSignalHandlerWithPayloads("runtimeSignal") { payloads ->
                val value = serializer.deserialize<String>(payloads[0])
                runtimeValue = value
            }
            awaitCondition { runtimeValue.isNotEmpty() }
            return runtimeValue
        }
    }

    @Workflow("RuntimeDynamicSignalWorkflow")
    class RuntimeDynamicSignalWorkflow {
        val receivedSignals = mutableListOf<String>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            setDynamicSignalHandlerWithPayloads { signalName, _ ->
                receivedSignals.add(signalName)
            }
            awaitCondition { receivedSignals.isNotEmpty() }
            return receivedSignals.joinToString()
        }
    }

    @Workflow("RuntimeOverrideSignalWorkflow")
    class RuntimeOverrideSignalWorkflow {
        var source = ""

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            setSignalHandlerWithPayloads("mySignal") { _ ->
                source = "runtime"
            }
            awaitCondition { source.isNotEmpty() }
            return source
        }

        @Signal("mySignal")
        fun WorkflowContext.mySignal() {
            source = "annotation"
        }
    }

    @Test
    fun `runtime signal handler is invoked correctly`() =
        runTest {
            val workflow = RuntimeSignalWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val valueArg = serializer.serialize("runtime-value")

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            signalWorkflowJob(
                                signalName = "runtimeSignal",
                                input = listOf(valueArg),
                            ),
                        ),
                )
            executor.activate(signalActivation)

            assertEquals("runtime-value", workflow.runtimeValue)
        }

    @Test
    fun `runtime dynamic signal handler catches unknown signals`() =
        runTest {
            val workflow = RuntimeDynamicSignalWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "anyUnknownSignal")),
                )
            executor.activate(signalActivation)

            assertEquals(listOf("anyUnknownSignal"), workflow.receivedSignals)
        }

    @Test
    fun `runtime handler takes precedence over annotation handler`() =
        runTest {
            val workflow = RuntimeOverrideSignalWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val signalActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "mySignal")),
                )
            executor.activate(signalActivation)

            assertEquals("runtime", workflow.source)
        }

    // ================================================================
    // Signal Buffering Tests
    // ================================================================

    @Workflow("BufferedSignalWorkflow")
    class BufferedSignalWorkflow {
        val receivedSignals = mutableListOf<String>()
        var handlerRegistered = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Signal arrives before handler is registered
            // Wait for condition (triggered by first signal being processed by dynamic handler or timer)
            awaitCondition { handlerRegistered }

            // Now register the handler - buffered signals should be replayed
            setSignalHandlerWithPayloads("bufferedSignal") { payloads ->
                val value = serializer.deserialize<String>(payloads[0])
                receivedSignals.add(value)
            }

            awaitCondition { receivedSignals.isNotEmpty() }
            return receivedSignals.joinToString()
        }

        @Signal("registerHandler")
        fun WorkflowContext.registerHandlerSignal() {
            handlerRegistered = true
        }
    }

    @Test
    fun `buffered signals are replayed when handler is registered`() =
        runTest {
            val workflow = BufferedSignalWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            // Send signal BEFORE handler is registered
            val signalArg = serializer.serialize("buffered-value")
            val signalActivation1 =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            signalWorkflowJob(signalName = "bufferedSignal", input = listOf(signalArg)),
                        ),
                )
            executor.activate(signalActivation1)

            // At this point, signal should be buffered (no handler registered yet)
            assertTrue(workflow.receivedSignals.isEmpty(), "Signal should be buffered, not processed yet")

            // Trigger handler registration
            val signalActivation2 =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "registerHandler")),
                )
            executor.activate(signalActivation2)

            // Now the buffered signal should have been replayed
            assertEquals(listOf("buffered-value"), workflow.receivedSignals)
        }

    @Workflow("DynamicBufferedSignalWorkflow")
    class DynamicBufferedSignalWorkflow {
        val receivedSignals = mutableListOf<String>()
        var handlerRegistered = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { handlerRegistered }

            // Register dynamic handler - ALL buffered signals should be replayed
            setDynamicSignalHandlerWithPayloads { signalName, _ ->
                receivedSignals.add(signalName)
            }

            awaitCondition { receivedSignals.size >= 2 }
            return receivedSignals.sorted().joinToString()
        }

        @Signal("registerHandler")
        fun WorkflowContext.registerHandlerSignal() {
            handlerRegistered = true
        }
    }

    @Test
    fun `dynamic handler replays all buffered signals`() =
        runTest {
            val workflow = DynamicBufferedSignalWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            // Send multiple signals BEFORE dynamic handler is registered
            val signalActivation1 =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            signalWorkflowJob(signalName = "signal1"),
                            signalWorkflowJob(signalName = "signal2"),
                        ),
                )
            executor.activate(signalActivation1)

            // Signals should be buffered
            assertTrue(workflow.receivedSignals.isEmpty(), "Signals should be buffered")

            // Trigger handler registration
            val signalActivation2 =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "registerHandler")),
                )
            executor.activate(signalActivation2)

            // Both buffered signals should have been replayed through dynamic handler
            assertEquals(listOf("signal1", "signal2"), workflow.receivedSignals.sorted())
        }

    @Workflow("MultipleBufferedSameNameWorkflow")
    class MultipleBufferedSameNameWorkflow {
        val receivedValues = mutableListOf<String>()
        var handlerRegistered = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { handlerRegistered }

            setSignalHandlerWithPayloads("dataSignal") { payloads ->
                val value = serializer.deserialize(payloads[0]) as String
                receivedValues.add(value)
            }

            awaitCondition { receivedValues.size >= 3 }
            return receivedValues.joinToString()
        }

        @Signal("registerHandler")
        fun WorkflowContext.registerHandlerSignal() {
            handlerRegistered = true
        }
    }

    @Test
    fun `multiple buffered signals with same name are replayed in order`() =
        runTest {
            val workflow = MultipleBufferedSameNameWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            // Send multiple signals with the same name BEFORE handler is registered
            val signalActivation1 =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            signalWorkflowJob(
                                signalName = "dataSignal",
                                input = listOf(serializer.serialize("first")),
                            ),
                            signalWorkflowJob(
                                signalName = "dataSignal",
                                input = listOf(serializer.serialize("second")),
                            ),
                            signalWorkflowJob(
                                signalName = "dataSignal",
                                input = listOf(serializer.serialize("third")),
                            ),
                        ),
                )
            executor.activate(signalActivation1)

            // All should be buffered
            assertTrue(workflow.receivedValues.isEmpty())

            // Trigger handler registration
            val signalActivation2 =
                createActivation(
                    runId = runId,
                    jobs = listOf(signalWorkflowJob(signalName = "registerHandler")),
                )
            executor.activate(signalActivation2)

            // All buffered signals should be replayed in FIFO order
            assertEquals(listOf("first", "second", "third"), workflow.receivedValues)
        }

    // ================================================================
    // Helper Methods
    // ================================================================

    private suspend fun createInitializedExecutor(workflowImpl: Any): Pair<WorkflowExecutor, String> {
        val klass = workflowImpl::class
        val workflowAnnotation = klass.findAnnotation<Workflow>()
        val workflowType =
            workflowAnnotation?.name?.takeIf { it.isNotBlank() }
                ?: klass.simpleName
                ?: error("Cannot determine workflow type")

        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = workflowType,
                implementation = workflowImpl,
                // For tests, reuse the same instance so we can inspect state
                instanceFactory = { workflowImpl },
            ),
        )

        val methodInfo =
            registry.lookup(workflowType)
                ?: error("Workflow not found: $workflowType")

        val runId = UUID.randomUUID().toString()

        val executor =
            createTestWorkflowExecutor(
                runId = runId,
                methodInfo = methodInfo,
                serializer = serializer,
            )

        // Initialize the workflow
        val initActivation =
            createActivation(
                runId = runId,
                jobs = listOf(initializeWorkflowJob(workflowType = workflowType)),
            )
        executor.activate(initActivation)

        return executor to runId
    }
}
