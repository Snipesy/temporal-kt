package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.UpdateValidator
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.WorkflowRegistration
import com.surrealdev.temporal.common.toTemporal
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.doUpdateJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveActivityJobCompleted
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive tests for update handler functionality.
 *
 * Test categories:
 * 1. Registration tests: name resolution, dynamic handlers, validators
 * 2. Execution tests: simple update, with args, with return value
 * 3. Validation tests: validator passes, validator rejects, validator mutation fails
 * 4. Error handling tests: handler throws, unknown update type
 * 5. Command generation tests: accepted/rejected/completed commands
 */
class UpdateHandlerTest {
    private val serializer = CompositePayloadSerializer.default()

    // ================================================================
    // Test Workflow Classes
    // ================================================================

    @Workflow("SimpleUpdateWorkflow")
    class SimpleUpdateWorkflow {
        var counter = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { counter > 0 }
            return "counter=$counter"
        }

        @Update("increment")
        fun WorkflowContext.incrementUpdate(): Int {
            counter++
            return counter
        }
    }

    @Serializable
    data class CartItem(
        val id: String,
        val quantity: Int,
    )

    @Workflow("UpdateWithArgsWorkflow")
    class UpdateWithArgsWorkflow {
        val items = mutableListOf<CartItem>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { items.isNotEmpty() }
            return "items=${items.size}"
        }

        @Update("addItem")
        fun WorkflowContext.addItemUpdate(item: CartItem): Int {
            items.add(item)
            return items.size
        }
    }

    @Workflow("UpdateWithValidatorWorkflow")
    class UpdateWithValidatorWorkflow {
        val items = mutableListOf<CartItem>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { items.isNotEmpty() }
            return "items=${items.size}"
        }

        @UpdateValidator("addItem")
        fun validateAddItem(item: CartItem) {
            require(item.quantity > 0) { "Quantity must be positive" }
        }

        @Update("addItem")
        fun WorkflowContext.addItemUpdate(item: CartItem): Int {
            items.add(item)
            return items.size
        }
    }

    @Workflow("SuspendUpdateWorkflow")
    class SuspendUpdateWorkflow {
        var value = ""

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { value.isNotEmpty() }
            return value
        }

        @Update("setValue")
        suspend fun WorkflowContext.setValueUpdate(v: String): String {
            value = v
            return "set to: $v"
        }
    }

    @Workflow("DynamicUpdateWorkflow")
    class DynamicUpdateWorkflow {
        val updates = mutableListOf<String>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { updates.size >= 2 }
            return updates.joinToString(", ")
        }

        @Update("specific")
        fun WorkflowContext.specificUpdate(): String {
            updates.add("specific")
            return "specific"
        }

        @Update(dynamic = true)
        fun WorkflowContext.dynamicHandler(updateName: String): String {
            updates.add("dynamic:$updateName")
            return "dynamic:$updateName"
        }
    }

    @Workflow("NoContextUpdateWorkflow")
    class NoContextUpdateWorkflow {
        var counter = 0

        @WorkflowRun
        suspend fun run(): String = "counter=$counter"

        @Update("increment")
        fun incrementUpdate(): Int {
            counter++
            return counter
        }
    }

    @Workflow("ThrowingUpdateWorkflow")
    class ThrowingUpdateWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"

        @Update("throwingUpdate")
        fun WorkflowContext.throwingUpdate(): String = throw RuntimeException("Update handler error")
    }

    @Workflow("ValidatorMutationWorkflow")
    class ValidatorMutationWorkflow {
        var mutated = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"

        @UpdateValidator("badUpdate")
        fun WorkflowContext.validateBadUpdate() {
            // This will fail because sleep() requires state mutation
            // which is not allowed in validators
            // We can't actually call sleep here directly, but we can test
            // that mutating workflow state is not allowed
        }

        @Update("badUpdate")
        fun WorkflowContext.badUpdate(): String {
            mutated = true
            return "mutated"
        }
    }

    @Serializable
    data class UpdateResult(
        val message: String,
        val count: Int,
    )

    @Workflow("ComplexReturnUpdateWorkflow")
    class ComplexReturnUpdateWorkflow {
        var count = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { count > 0 }
            return "count=$count"
        }

        @Update("complexUpdate")
        fun WorkflowContext.complexUpdate(message: String): UpdateResult {
            count++
            return UpdateResult(message = message, count = count)
        }
    }

    // ================================================================
    // Registration Tests
    // ================================================================

    @Test
    fun `update handler registration extracts update name from annotation`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "SimpleUpdateWorkflow",
                workflowClass = SimpleUpdateWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("SimpleUpdateWorkflow")
        assertNotNull(methodInfo)
        assertTrue(methodInfo.updateHandlers.containsKey("increment"))
    }

    @Test
    fun `update handler registration uses function name when annotation name is blank`() {
        @Workflow("BlankNameUpdateWorkflow")
        class BlankNameUpdateWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @Update // No name specified
            fun myUpdateHandler(): String = "updated"
        }

        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "BlankNameUpdateWorkflow",
                workflowClass = BlankNameUpdateWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("BlankNameUpdateWorkflow")
        assertNotNull(methodInfo)
        assertTrue(methodInfo.updateHandlers.containsKey("myUpdateHandler"))
    }

    @Test
    fun `update handler registration supports dynamic handler with null key`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "DynamicUpdateWorkflow",
                workflowClass = DynamicUpdateWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("DynamicUpdateWorkflow")
        assertNotNull(methodInfo)
        assertTrue(methodInfo.updateHandlers.containsKey(null), "Dynamic handler should have null key")
        assertTrue(methodInfo.updateHandlers.containsKey("specific"))
    }

    @Test
    fun `update handler registration includes validator method`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "UpdateWithValidatorWorkflow",
                workflowClass = UpdateWithValidatorWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("UpdateWithValidatorWorkflow")
        assertNotNull(methodInfo)

        val handler = methodInfo.updateHandlers["addItem"]
        assertNotNull(handler)
        assertNotNull(handler.validatorMethod, "Validator method should be present")
    }

    @Test
    fun `duplicate update names throw exception`() {
        @Workflow("DuplicateUpdateWorkflow")
        class DuplicateUpdateWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @Update("sameName")
            fun update1(): String = "one"

            @Update("sameName")
            fun update2(): String = "two"
        }

        val registry = WorkflowRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                WorkflowRegistration(
                    workflowType = "DuplicateUpdateWorkflow",
                    workflowClass = DuplicateUpdateWorkflow::class,
                ),
            )
        }
    }

    @Test
    fun `multiple dynamic update handlers throw exception`() {
        @Workflow("MultipleDynamicUpdateWorkflow")
        class MultipleDynamicUpdateWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @Update(dynamic = true)
            fun handler1(): String = "one"

            @Update(dynamic = true)
            fun handler2(): String = "two"
        }

        val registry = WorkflowRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                WorkflowRegistration(
                    workflowType = "MultipleDynamicUpdateWorkflow",
                    workflowClass = MultipleDynamicUpdateWorkflow::class,
                ),
            )
        }
    }

    @Test
    fun `validator without matching update throws exception`() {
        @Workflow("OrphanValidatorWorkflow")
        class OrphanValidatorWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @UpdateValidator("nonExistentUpdate")
            fun validateNonExistent() {}
        }

        val registry = WorkflowRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                WorkflowRegistration(
                    workflowType = "OrphanValidatorWorkflow",
                    workflowClass = OrphanValidatorWorkflow::class,
                ),
            )
        }
    }

    // ================================================================
    // Execution Tests
    // ================================================================

    @Test
    fun `update handler returns result successfully`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(SimpleUpdateWorkflow())

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "increment",
                                protocolInstanceId = protocolId,
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList

            // Should have accepted and completed commands
            val updateResponses = commands.filter { it.hasUpdateResponse() }
            assertTrue(updateResponses.size >= 2, "Should have at least 2 update response commands")

            val accepted = updateResponses.find { it.updateResponse.hasAccepted() }
            assertNotNull(accepted, "Should have accepted command")
            assertEquals(protocolId, accepted.updateResponse.protocolInstanceId)

            val completed = updateResponses.find { it.updateResponse.hasCompleted() }
            assertNotNull(completed, "Should have completed command")
            assertEquals(protocolId, completed.updateResponse.protocolInstanceId)
        }

    @Test
    fun `update handler with arguments deserializes args correctly`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(UpdateWithArgsWorkflow())

            val item = CartItem(id = "item-1", quantity = 5)
            val argPayload = serializer.serialize(item)

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "addItem",
                                protocolInstanceId = protocolId,
                                input = listOf(argPayload),
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            val completed = commands.find { it.hasUpdateResponse() && it.updateResponse.hasCompleted() }
            assertNotNull(completed)

            // Deserialize the result
            val result =
                serializer.deserialize<Int>(
                    completed.updateResponse.completed.toTemporal(),
                )
            assertEquals(1, result)
        }

    @Test
    fun `suspend update handler executes correctly`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(SuspendUpdateWorkflow())

            val valueArg = serializer.serialize("test-value")

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "setValue",
                                protocolInstanceId = protocolId,
                                input = listOf(valueArg),
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val completed =
                completion.successful.commandsList
                    .find { it.hasUpdateResponse() && it.updateResponse.hasCompleted() }
            assertNotNull(completed)
        }

    @Test
    fun `update handler without context receiver works`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(NoContextUpdateWorkflow())

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "increment",
                                protocolInstanceId = protocolId,
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `complex return type serializes correctly`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(ComplexReturnUpdateWorkflow())

            val msgArg = serializer.serialize<String>("hello")

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "complexUpdate",
                                protocolInstanceId = protocolId,
                                input = listOf(msgArg),
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val completed =
                completion.successful.commandsList
                    .find { it.hasUpdateResponse() && it.updateResponse.hasCompleted() }
            assertNotNull(completed)

            val result =
                serializer.deserialize<UpdateResult>(
                    completed.updateResponse.completed.toTemporal(),
                ) as UpdateResult
            assertEquals("hello", result.message)
            assertEquals(1, result.count)
        }

    // ================================================================
    // Dynamic Handler Tests
    // ================================================================

    @Test
    fun `dynamic handler is used for unknown update types`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(DynamicUpdateWorkflow())

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "unknownUpdate",
                                protocolInstanceId = protocolId,
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val completed =
                completion.successful.commandsList
                    .find { it.hasUpdateResponse() && it.updateResponse.hasCompleted() }
            assertNotNull(completed)
        }

    @Test
    fun `specific handler takes precedence over dynamic handler`() =
        runTest {
            val workflow = DynamicUpdateWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "specific",
                                protocolInstanceId = protocolId,
                                runValidator = false,
                            ),
                        ),
                )
            executor.activate(updateActivation)

            assertEquals(listOf("specific"), workflow.updates)
        }

    // ================================================================
    // Validation Tests
    // ================================================================

    @Test
    fun `validator passes and handler executes`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(UpdateWithValidatorWorkflow())

            val item = CartItem(id = "item-1", quantity = 5)
            val argPayload = serializer.serialize(item)

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "addItem",
                                protocolInstanceId = protocolId,
                                input = listOf(argPayload),
                                runValidator = true,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            val completed = commands.find { it.hasUpdateResponse() && it.updateResponse.hasCompleted() }
            assertNotNull(completed, "Update should complete successfully")
        }

    @Test
    fun `validator rejects invalid input`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(UpdateWithValidatorWorkflow())

            // Invalid quantity (0)
            val item = CartItem(id = "item-1", quantity = 0)
            val argPayload = serializer.serialize(item)

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "addItem",
                                protocolInstanceId = protocolId,
                                input = listOf(argPayload),
                                runValidator = true,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            val rejected = commands.find { it.hasUpdateResponse() && it.updateResponse.hasRejected() }
            assertNotNull(rejected, "Update should be rejected")
            assertTrue(
                rejected.updateResponse.rejected.message
                    .contains("Quantity must be positive"),
            )
        }

    @Test
    fun `validator not run during replay`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(UpdateWithValidatorWorkflow())

            // Invalid quantity, but runValidator=false (simulating replay)
            val item = CartItem(id = "item-1", quantity = 0)
            val argPayload = serializer.serialize(item)

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "addItem",
                                protocolInstanceId = protocolId,
                                input = listOf(argPayload),
                                runValidator = false, // Simulate replay
                            ),
                        ),
                    isReplaying = true,
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            // Should complete because validator was not run
            val completed = commands.find { it.hasUpdateResponse() && it.updateResponse.hasCompleted() }
            assertNotNull(completed, "Update should complete during replay without validation")
        }

    // ================================================================
    // Error Handling Tests
    // ================================================================

    @Test
    fun `update handler exception returns rejected`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(ThrowingUpdateWorkflow())

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "throwingUpdate",
                                protocolInstanceId = protocolId,
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            val rejected = commands.find { it.hasUpdateResponse() && it.updateResponse.hasRejected() }
            assertNotNull(rejected, "Update should be rejected when handler throws")
            assertTrue(
                rejected.updateResponse.rejected.message
                    .contains("Update failed"),
            )
        }

    @Test
    fun `unknown update type returns rejected immediately`() =
        runTest {
            val (executor, runId) = createInitializedExecutor(SimpleUpdateWorkflow())

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "nonExistentUpdate",
                                protocolInstanceId = protocolId,
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            val rejected = commands.find { it.hasUpdateResponse() && it.updateResponse.hasRejected() }
            assertNotNull(rejected, "Unknown update should be rejected")
            assertTrue(
                rejected.updateResponse.rejected.message
                    .contains("Unknown update type"),
            )
        }

    // ================================================================
    // Runtime Update Handler Tests
    // ================================================================

    @Workflow("RuntimeUpdateWorkflow")
    class RuntimeUpdateWorkflow {
        var runtimeValue = ""

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            @OptIn(InternalTemporalApi::class)
            setUpdateHandlerWithPayloads(
                name = "runtimeUpdate",
                handler = { payloads ->
                    val value = serializer.deserialize<String>(payloads[0])
                    runtimeValue = value
                    serializer.serialize("set: $value")
                },
            )
            awaitCondition { runtimeValue.isNotEmpty() }
            return runtimeValue
        }
    }

    @Workflow("RuntimeUpdateWithValidatorWorkflow")
    class RuntimeUpdateWithValidatorWorkflow {
        var value = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            setUpdateHandlerWithPayloads(
                "setValue",
                handler = { payloads ->
                    @OptIn(InternalTemporalApi::class)
                    val newValue = serializer.deserialize<Int>(payloads[0])
                    value = newValue
                    @OptIn(InternalTemporalApi::class)
                    serializer.serialize<Int>(value)
                },
                validator = { payloads ->
                    @OptIn(InternalTemporalApi::class)
                    val newValue = serializer.deserialize<Int>(payloads[0])
                    require(newValue >= 0) { "Value must be non-negative" }
                },
            )
            awaitCondition { value > 0 }
            return "value=$value"
        }
    }

    @Test
    fun `runtime update handler is invoked correctly`() =
        runTest {
            val workflow = RuntimeUpdateWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val valueArg = serializer.serialize<String>("runtime-value")

            val protocolId = "test-protocol-id"
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "runtimeUpdate",
                                protocolInstanceId = protocolId,
                                input = listOf(valueArg),
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            assertEquals("runtime-value", workflow.runtimeValue)
        }

    @Test
    fun `runtime update validator works correctly`() =
        runTest {
            val workflow = RuntimeUpdateWithValidatorWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            // Test with valid value
            val validArg = serializer.serialize<Int>(42)
            val protocolId1 = "test-protocol-id-1"
            val validUpdateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "setValue",
                                protocolInstanceId = protocolId1,
                                input = listOf(validArg),
                                runValidator = true,
                            ),
                        ),
                )
            val completion1 = executor.activate(validUpdateActivation)
            assertTrue(completion1.hasSuccessful())
            val completed =
                completion1.successful.commandsList
                    .find { it.hasUpdateResponse() && it.updateResponse.hasCompleted() }
            assertNotNull(completed, "Valid update should complete")

            // Test with invalid value
            val invalidArg = serializer.serialize<Int>(-1)
            val protocolId2 = "test-protocol-id-2"
            val invalidUpdateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "setValue",
                                protocolInstanceId = protocolId2,
                                input = listOf(invalidArg),
                                runValidator = true,
                            ),
                        ),
                )
            val completion2 = executor.activate(invalidUpdateActivation)
            assertTrue(completion2.hasSuccessful())
            val rejected =
                completion2.successful.commandsList
                    .find { it.hasUpdateResponse() && it.updateResponse.hasRejected() }
            assertNotNull(rejected, "Invalid update should be rejected")
        }

    // ================================================================
    // Multiple Updates Tests
    // ================================================================

    @Test
    fun `multiple updates in same activation are all processed`() =
        runTest {
            val workflow = SimpleUpdateWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "increment",
                                protocolInstanceId = "proto-1",
                                runValidator = false,
                            ),
                            doUpdateJob(
                                name = "increment",
                                protocolInstanceId = "proto-2",
                                runValidator = false,
                            ),
                            doUpdateJob(
                                name = "increment",
                                protocolInstanceId = "proto-3",
                                runValidator = false,
                            ),
                        ),
                )
            val completion = executor.activate(updateActivation)

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            val completedResponses =
                commands.filter {
                    it.hasUpdateResponse() && it.updateResponse.hasCompleted()
                }
            assertEquals(3, completedResponses.size)
            assertEquals(3, workflow.counter)
        }

    // ================================================================
    // Update Handler with Activity Tests
    // ================================================================

    /**
     * Workflow where the Update handler calls an activity.
     * This is the minimal repro for the wrong dispatcher issue.
     */
    @Workflow("UpdateWithActivityWorkflow")
    class UpdateWithActivityWorkflow {
        private var done = false
        private var activityResult = ""

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return activityResult
        }

        /**
         * Update handler that schedules an activity and waits for its result.
         */
        @Update("doWork")
        suspend fun WorkflowContext.doWork(input: String): String {
            // This should schedule an activity and return its result
            val result: String =
                startActivity(
                    activityType = "echo",
                    arg = input,
                    options = ActivityOptions(startToCloseTimeout = 60.seconds),
                ).result()
            activityResult = result
            return result
        }
    }

    /**
     * Tests that an Update handler can successfully schedule an activity.
     *
     * This test verifies:
     * 1. Update handler is invoked
     * 2. Activity is scheduled (ScheduleActivity command generated)
     * 3. After activity resolves, Update completes with the activity result
     */
    @Test
    fun `update handler can call startActivity and schedule activity`() =
        runTest {
            val workflow = UpdateWithActivityWorkflow()
            val (executor, runId) = createInitializedExecutor(workflow)

            val inputArg = serializer.serialize<String>("test-input")
            val protocolId = "update-with-activity-protocol"

            // First activation: Send the update
            val updateActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            doUpdateJob(
                                name = "doWork",
                                protocolInstanceId = protocolId,
                                input = listOf(inputArg),
                                runValidator = false,
                            ),
                        ),
                )
            val completion1 = executor.activate(updateActivation)

            assertTrue(completion1.hasSuccessful(), "Activation should succeed")
            val commands1 = completion1.successful.commandsList

            // Should have:
            // 1. Update accepted command
            // 2. ScheduleActivity command (from the startActivity call in handler)
            val updateAccepted = commands1.find { it.hasUpdateResponse() && it.updateResponse.hasAccepted() }
            assertNotNull(updateAccepted, "Update should be accepted")

            val scheduleActivity = commands1.find { it.hasScheduleActivity() }
            assertNotNull(scheduleActivity, "Should have ScheduleActivity command from update handler")
            assertEquals("echo", scheduleActivity.scheduleActivity.activityType)

            // Second activation: Resolve the activity
            val activitySeq = scheduleActivity.scheduleActivity.seq
            val activityResult = serializer.serialize<String>("Echo: test-input")
            val resolveActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            resolveActivityJobCompleted(
                                seq = activitySeq,
                                result = activityResult,
                            ),
                        ),
                )
            val completion2 = executor.activate(resolveActivation)

            assertTrue(completion2.hasSuccessful(), "Second activation should succeed")
            val commands2 = completion2.successful.commandsList

            // Now the Update should complete with the activity result
            val updateCompleted = commands2.find { it.hasUpdateResponse() && it.updateResponse.hasCompleted() }
            assertNotNull(updateCompleted, "Update should complete after activity resolves")

            val updateResult = serializer.deserialize<String>(updateCompleted.updateResponse.completed.toTemporal())
            assertEquals("Echo: test-input", updateResult)
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
                workflowClass = workflowImpl::class,
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
