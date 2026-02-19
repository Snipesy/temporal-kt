package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.WorkflowRegistration
import com.surrealdev.temporal.common.toTemporal
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.queryWorkflowJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.testing.runWorkflowUnitTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive tests for query handler functionality.
 *
 * Test categories:
 * 1. Registration tests: name resolution, dynamic handlers, duplicates, validation
 * 2. Execution tests: simple return, with args, with context, suspend
 * 3. Error handling tests: handler not found, throws exception, attempts mutation
 * 4. Integration tests: multiple queries, query after state change
 */
class QueryHandlerTest {
    private val serializer = CompositePayloadSerializer.default()

    // ================================================================
    // Test Workflow Classes
    // ================================================================

    @Workflow("SimpleQueryWorkflow")
    class SimpleQueryWorkflow {
        private var counter = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            counter = 42
            return "done"
        }

        @Query("getCounter")
        fun WorkflowContext.getCounter(): Int = counter
    }

    @Serializable
    data class AllData(
        val status: String,
        val count: Int,
    )

    @Workflow("MultiQueryWorkflow")
    class MultiQueryWorkflow {
        private var status = "initialized"
        private var count = 0

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            status = "running"
            count = 100
            return "done"
        }

        @Query("getStatus")
        fun WorkflowContext.getStatus(): String = status

        @Query("getCount")
        fun WorkflowContext.getCount(): Int = count

        @Query // Uses function name as query name
        fun WorkflowContext.getAll(): AllData = AllData(status = status, count = count)
    }

    @Workflow("DynamicQueryWorkflow")
    class DynamicQueryWorkflow {
        private var data = mutableMapOf<String, String>()

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            data["key1"] = "value1"
            data["key2"] = "value2"
            return "done"
        }

        @Query("getSpecific")
        fun WorkflowContext.getSpecific(): String = "specific"

        @Query(dynamic = true)
        fun WorkflowContext.dynamicHandler(queryType: String): String = "dynamic: $queryType"
    }

    @Workflow("QueryWithArgsWorkflow")
    class QueryWithArgsWorkflow {
        private var items = listOf("a", "b", "c", "d", "e")

        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"

        @Query("getItem")
        fun WorkflowContext.getItem(index: Int): String? = items.getOrNull(index)

        @Query("getRange")
        fun WorkflowContext.getRange(
            start: Int,
            end: Int,
        ): List<String> = items.subList(start.coerceIn(0, items.size), end.coerceIn(0, items.size))
    }

    @Workflow("SuspendQueryWorkflow")
    class SuspendQueryWorkflow {
        private var value = "initial"

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            value = "updated"
            return "done"
        }

        @Query("getValue")
        suspend fun WorkflowContext.getValue(): String {
            // Suspend query handler (unusual but supported)
            return value
        }
    }

    @Workflow("NoContextQueryWorkflow")
    class NoContextQueryWorkflow {
        private var internalValue = 42

        @WorkflowRun
        suspend fun run(): String {
            internalValue = 100
            return "done"
        }

        @Query("getInternalValue")
        fun getInternalValue(): Int = internalValue
    }

    @Workflow("ThrowingQueryWorkflow")
    class ThrowingQueryWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"

        @Query("throwingQuery")
        fun WorkflowContext.throwingQuery(): String = throw RuntimeException("Query handler error")

        @Query("nullPointerQuery")
        fun WorkflowContext.nullPointerQuery(): String {
            val nullStr: String? = null
            return nullStr!!
        }
    }

    @Workflow("MutatingQueryWorkflow")
    class MutatingQueryWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"

        @Query("attemptMutation")
        suspend fun WorkflowContext.attemptMutation(): String {
            // This should fail because sleep() requires sequence number generation
            sleep(1.seconds)
            return "should not reach"
        }
    }

    @Serializable
    data class QueryArg(
        val name: String,
        val value: Int,
    )

    @Serializable
    data class QueryResult(
        val message: String,
        val data: List<String>,
    )

    @Workflow("ComplexQueryWorkflow")
    class ComplexQueryWorkflow {
        private var data = listOf("item1", "item2", "item3")

        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"

        @Query("complexQuery")
        fun WorkflowContext.complexQuery(arg: QueryArg): QueryResult =
            QueryResult(
                message = "Hello ${arg.name}",
                data = data.take(arg.value),
            )
    }

    // ================================================================
    // Registration Tests
    // ================================================================

    @Test
    fun `query handler registration extracts query name from annotation`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "SimpleQueryWorkflow",
                workflowClass = SimpleQueryWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("SimpleQueryWorkflow")
        assertNotNull(methodInfo)
        assertTrue(methodInfo.queryHandlers.containsKey("getCounter"))
    }

    @Test
    fun `query handler registration uses function name when annotation name is blank`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "MultiQueryWorkflow",
                workflowClass = MultiQueryWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("MultiQueryWorkflow")
        assertNotNull(methodInfo)
        // getAll uses function name since @Query has no name parameter
        assertTrue(methodInfo.queryHandlers.containsKey("getAll"))
    }

    @Test
    fun `query handler registration supports multiple queries`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "MultiQueryWorkflow",
                workflowClass = MultiQueryWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("MultiQueryWorkflow")
        assertNotNull(methodInfo)
        assertEquals(3, methodInfo.queryHandlers.size)
        assertTrue(methodInfo.queryHandlers.containsKey("getStatus"))
        assertTrue(methodInfo.queryHandlers.containsKey("getCount"))
        assertTrue(methodInfo.queryHandlers.containsKey("getAll"))
    }

    @Test
    fun `query handler registration supports dynamic handler with null key`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "DynamicQueryWorkflow",
                workflowClass = DynamicQueryWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("DynamicQueryWorkflow")
        assertNotNull(methodInfo)
        assertTrue(methodInfo.queryHandlers.containsKey(null), "Dynamic handler should have null key")
        assertTrue(methodInfo.queryHandlers.containsKey("getSpecific"))
    }

    @Test
    fun `query handler info captures method metadata correctly`() {
        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = "QueryWithArgsWorkflow",
                workflowClass = QueryWithArgsWorkflow::class,
            ),
        )

        val methodInfo = registry.lookup("QueryWithArgsWorkflow")
        assertNotNull(methodInfo)

        val getItemHandler = methodInfo.queryHandlers["getItem"]
        assertNotNull(getItemHandler)
        assertTrue(getItemHandler.hasContextReceiver)
        assertEquals(1, getItemHandler.parameterTypes.size)
        assertEquals(typeOf<Int>(), getItemHandler.parameterTypes[0])

        val getRangeHandler = methodInfo.queryHandlers["getRange"]
        assertNotNull(getRangeHandler)
        assertEquals(2, getRangeHandler.parameterTypes.size)
    }

    @Test
    fun `duplicate query names throw exception`() {
        @Workflow("DuplicateQueryWorkflow")
        class DuplicateQueryWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @Query("sameName")
            fun query1(): String = "one"

            @Query("sameName")
            fun query2(): String = "two"
        }

        val registry = WorkflowRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                WorkflowRegistration(
                    workflowType = "DuplicateQueryWorkflow",
                    workflowClass = DuplicateQueryWorkflow::class,
                ),
            )
        }
    }

    @Test
    fun `multiple dynamic handlers throw exception`() {
        @Workflow("MultipleDynamicWorkflow")
        class MultipleDynamicWorkflow {
            @WorkflowRun
            suspend fun run(): String = "done"

            @Query(dynamic = true)
            fun handler1(): String = "one"

            @Query(dynamic = true)
            fun handler2(): String = "two"
        }

        val registry = WorkflowRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register(
                WorkflowRegistration(
                    workflowType = "MultipleDynamicWorkflow",
                    workflowClass = MultipleDynamicWorkflow::class,
                ),
            )
        }
    }

    // ================================================================
    // Execution Tests
    // ================================================================

    @Test
    fun `query handler returns result successfully`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(SimpleQueryWorkflow())

            // Process query
            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getCounter")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val commands = completion.successful.commandsList
            assertTrue(commands.any { it.hasRespondToQuery() })

            val queryResult = commands.first { it.hasRespondToQuery() }.respondToQuery
            assertEquals("q1", queryResult.queryId)
            assertTrue(queryResult.hasSucceeded())
        }

    @Test
    fun `query handler with arguments deserializes args correctly`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(QueryWithArgsWorkflow())

            // Serialize the argument
            val indexArg = serializer.serialize(2)

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            queryWorkflowJob(
                                queryId = "q1",
                                queryType = "getItem",
                                arguments = listOf(indexArg),
                            ),
                        ),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            // Deserialize and verify the result
            val result =
                serializer.deserialize<String>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals("c", result) // items[2] = "c"
        }

    @Test
    fun `query handler with multiple arguments works correctly`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(QueryWithArgsWorkflow())

            val startArg = serializer.serialize(1)
            val endArg = serializer.serialize(3)

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            queryWorkflowJob(
                                queryId = "q1",
                                queryType = "getRange",
                                arguments = listOf(startArg, endArg),
                            ),
                        ),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            val result =
                serializer.deserialize<List<String>>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals(listOf("b", "c"), result)
        }

    @Test
    fun `suspend query handler executes correctly`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(SuspendQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getValue")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())
        }

    @Test
    fun `query handler without context receiver works`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(NoContextQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getInternalValue")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())
        }

    @Test
    fun `complex query with serializable types works`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(ComplexQueryWorkflow())

            val arg = QueryArg(name = "Test", value = 2)
            val argPayload = serializer.serialize(arg)

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            queryWorkflowJob(
                                queryId = "q1",
                                queryType = "complexQuery",
                                arguments = listOf(argPayload),
                            ),
                        ),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            val result =
                serializer.deserialize<QueryResult>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals("Hello Test", result.message)
            assertEquals(listOf("item1", "item2"), result.data)
        }

    // ================================================================
    // Dynamic Handler Tests
    // ================================================================

    @Test
    fun `dynamic handler is used for unknown query types`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(DynamicQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "unknownQuery")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())
        }

    @Test
    fun `specific handler takes precedence over dynamic handler`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(DynamicQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getSpecific")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            val result =
                serializer.deserialize<String>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals("specific", result)
        }

    // ================================================================
    // Error Handling Tests
    // ================================================================

    @Test
    fun `unknown query type returns failure when no dynamic handler`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(SimpleQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "nonExistentQuery")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertEquals("q1", queryResult.queryId)
            assertTrue(queryResult.hasFailed())
            assertTrue(queryResult.failed.message.contains("Unknown query type"))
        }

    @Test
    fun `query handler exception returns failure result`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(ThrowingQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "throwingQuery")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasFailed())
            assertTrue(queryResult.failed.message.contains("Query failed"))
        }

    @Test
    fun `query handler null pointer exception returns failure result`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(ThrowingQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "nullPointerQuery")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasFailed())
        }

    // ================================================================
    // Multiple Query Tests
    // ================================================================

    @Test
    fun `multiple queries in same activation are all processed`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(MultiQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            queryWorkflowJob(queryId = "q1", queryType = "getStatus"),
                            queryWorkflowJob(queryId = "q2", queryType = "getCount"),
                            queryWorkflowJob(queryId = "q3", queryType = "getAll"),
                        ),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryCommands = completion.successful.commandsList.filter { it.hasRespondToQuery() }
            assertEquals(3, queryCommands.size)

            val queryIds = queryCommands.map { it.respondToQuery.queryId }.toSet()
            assertEquals(setOf("q1", "q2", "q3"), queryIds)

            // All should succeed
            queryCommands.forEach { cmd ->
                assertTrue(cmd.respondToQuery.hasSucceeded(), "Query ${cmd.respondToQuery.queryId} should succeed")
            }
        }

    @Test
    fun `mixed success and failure queries in same activation`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(SimpleQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            queryWorkflowJob(queryId = "q1", queryType = "getCounter"), // Should succeed
                            queryWorkflowJob(queryId = "q2", queryType = "nonExistent"), // Should fail
                        ),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryCommands = completion.successful.commandsList.filter { it.hasRespondToQuery() }
            assertEquals(2, queryCommands.size)

            val q1Result = queryCommands.first { it.respondToQuery.queryId == "q1" }.respondToQuery
            assertTrue(q1Result.hasSucceeded())

            val q2Result = queryCommands.first { it.respondToQuery.queryId == "q2" }.respondToQuery
            assertTrue(q2Result.hasFailed())
        }

    // ================================================================
    // Runtime Query Handler Tests
    // ================================================================

    @Workflow("RuntimeQueryWorkflow")
    class RuntimeQueryWorkflow {
        private var runtimeValue = "initial"

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register a runtime query handler that works with raw Payloads
            setQueryHandlerWithPayloads("runtimeQuery") { _ ->
                serializer.serialize("runtime: $runtimeValue")
            }
            runtimeValue = "updated"
            return "done"
        }
    }

    @Workflow("RuntimeDynamicQueryWorkflow")
    class RuntimeDynamicQueryWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register a runtime dynamic query handler that works with raw Payloads
            setDynamicQueryHandlerWithPayloads { queryType, _ ->
                serializer.serialize<String>("dynamic handler received: $queryType")
            }
            return "done"
        }
    }

    @Workflow("RuntimeOverrideWorkflow")
    class RuntimeOverrideWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Override the annotation-defined handler at runtime
            setQueryHandlerWithPayloads("getStatus") { _ ->
                serializer.serialize<String>("runtime override")
            }
            return "done"
        }

        @Query("getStatus")
        fun WorkflowContext.getStatus(): String = "annotation handler"
    }

    @Serializable
    data class RuntimeQueryResult(
        val message: String,
        val value: Int,
    )

    @Workflow("RuntimeSerializationWorkflow")
    class RuntimeSerializationWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register handlers that return various serializable types as raw Payloads
            setQueryHandlerWithPayloads("getString") { _ -> serializer.serialize("hello world") }
            setQueryHandlerWithPayloads("getInt") { _ -> serializer.serialize(42) }
            setQueryHandlerWithPayloads("getList") { _ -> serializer.serialize(listOf("a", "b", "c")) }
            setQueryHandlerWithPayloads("getComplex") { _ ->
                serializer.serialize(RuntimeQueryResult("test", 123))
            }
            setQueryHandlerWithPayloads("getNull") { _ ->
                io.temporal.api.common.v1.Payload
                    .getDefaultInstance()
                    .toTemporal()
            }
            return "done"
        }
    }

    @Workflow("RuntimeUnregisterWorkflow")
    class RuntimeUnregisterWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            // Register then unregister a handler
            setQueryHandlerWithPayloads("tempQuery") { _ -> serializer.serialize("temporary") }
            setQueryHandlerWithPayloads("tempQuery", null) // Unregister
            return "done"
        }
    }

    @Test
    fun `runtime query handler is invoked correctly`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "runtimeQuery")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded(), "Runtime query should succeed")

            val result =
                serializer.deserialize<String>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals("runtime: updated", result)
        }

    @Test
    fun `runtime dynamic query handler catches unknown queries`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeDynamicQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "anyUnknownQuery")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded(), "Runtime dynamic query should succeed")

            val result =
                serializer.deserialize<String>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals("dynamic handler received: anyUnknownQuery", result)
        }

    @Test
    fun `runtime handler takes precedence over annotation handler`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeOverrideWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getStatus")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            val result =
                serializer.deserialize<String>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals("runtime override", result)
        }

    @Test
    fun `runtime handler serializes string correctly`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeSerializationWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getString")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            val result =
                serializer.deserialize<String>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals("hello world", result)
        }

    @Test
    fun `runtime handler serializes int correctly`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeSerializationWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getInt")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            val result =
                serializer.deserialize<Int>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals(42, result)
        }

    @Test
    fun `runtime handler serializes list correctly`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeSerializationWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getList")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            val result =
                serializer.deserialize<List<String>>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals(listOf("a", "b", "c"), result)
        }

    @Test
    fun `runtime handler serializes complex type correctly`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeSerializationWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getComplex")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())

            val result =
                serializer.deserialize<RuntimeQueryResult>(
                    queryResult.succeeded.response.toTemporal(),
                )
            assertEquals(RuntimeQueryResult("test", 123), result)
        }

    @Test
    fun `runtime handler returning null produces empty payload`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeSerializationWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "getNull")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasSucceeded())
            // Null returns empty/default payload
            assertTrue(
                queryResult.succeeded.response.data.isEmpty ||
                    queryResult.succeeded.response ==
                    io.temporal.api.common.v1.Payload
                        .getDefaultInstance(),
            )
        }

    @Test
    fun `unregistered runtime handler falls through to annotation or fails`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(RuntimeUnregisterWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "tempQuery")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            // Should fail since handler was unregistered and no annotation fallback
            assertTrue(queryResult.hasFailed())
            assertTrue(queryResult.failed.message.contains("Unknown query type"))
        }

    // ================================================================
    // Read-Only Enforcement Tests
    // ================================================================

    @Test
    fun `query handler state mutation attempt returns failure`() =
        runWorkflowUnitTest {
            val (executor, runId) = createInitializedExecutor(MutatingQueryWorkflow())

            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryId = "q1", queryType = "attemptMutation")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
            val queryResult =
                completion.successful.commandsList
                    .first { it.hasRespondToQuery() }
                    .respondToQuery
            assertTrue(queryResult.hasFailed())
            assertTrue(
                queryResult.failed.message.contains("read-only") ||
                    queryResult.failed.message.contains("Query attempted state mutation"),
            )
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
        executor.activate(initActivation).completion

        return executor to runId
    }
}
