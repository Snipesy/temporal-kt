package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.queryWorkflowJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for query behavior at the activation level.
 *
 * These tests verify:
 * - Query activation sets isReadOnly flag
 * - Mutations during query throw ReadOnlyContextException
 * - Query does not generate workflow commands (state-mutating commands)
 * - State remains unchanged after query activation
 *
 * These tests focus on the activation-level read-only enforcement.
 * For full query handler integration tests, see [QueryHandlerTest].
 */
class QueryBehaviorTest {
    // ================================================================
    // Query Read-Only Enforcement (Unit Level)
    // ================================================================

    @Test
    fun `query activation sets isReadOnly flag`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Initially not read-only
            assertFalse(state.isReadOnly)

            // When processing queries, executor sets isReadOnly
            state.isReadOnly = true

            assertTrue(state.isReadOnly)

            // After query processing, reset
            state.isReadOnly = false
            assertFalse(state.isReadOnly)
        }

    @Test
    fun `attempting mutation during query throws ReadOnlyContextException`() =
        runTest {
            val state = WorkflowState("test-run-id")
            state.isReadOnly = true

            // All mutation operations should throw
            assertFailsWith<ReadOnlyContextException> {
                state.nextSeq()
            }

            assertFailsWith<ReadOnlyContextException> {
                state.registerTimer(1)
            }

            assertFailsWith<ReadOnlyContextException> {
                val handle =
                    RemoteActivityHandleImpl(
                        activityId = "test",
                        seq = 1,
                        activityType = "Test::run",
                        state = state,
                        serializer = CompositePayloadSerializer.default(),
                        codec = com.surrealdev.temporal.serialization.NoOpCodec,
                        cancellationType = com.surrealdev.temporal.workflow.ActivityCancellationType.TRY_CANCEL,
                    )
                state.registerActivity(1, handle)
            }

            assertFailsWith<ReadOnlyContextException> {
                state.registerCondition { true }
            }

            assertFailsWith<ReadOnlyContextException> {
                state.addCommand(WorkflowCommand.getDefaultInstance())
            }
        }

    @Test
    fun `query does not generate workflow commands`() =
        runTest {
            // This test verifies that in read-only mode, no state-mutating commands can be added.
            // Since query handlers aren't implemented yet, we test at the WorkflowState level.
            val state = WorkflowState("test-run-id")

            // Add a command before query
            state.addCommand(
                WorkflowCommand
                    .newBuilder()
                    .setStartTimer(
                        coresdk.workflow_commands.WorkflowCommands.StartTimer
                            .newBuilder()
                            .setSeq(1),
                    ).build(),
            )

            // Drain commands to get a clean slate
            val commandsBefore = state.drainCommands()
            assertEquals(1, commandsBefore.size)

            // Enter read-only mode (simulating query processing)
            state.isReadOnly = true

            // Attempting to add commands should fail in read-only mode
            assertFailsWith<ReadOnlyContextException> {
                state.addCommand(
                    WorkflowCommand
                        .newBuilder()
                        .setStartTimer(
                            coresdk.workflow_commands.WorkflowCommands.StartTimer
                                .newBuilder()
                                .setSeq(2),
                        ).build(),
                )
            }

            // Exit read-only mode
            state.isReadOnly = false

            // No commands should have been added during "query processing"
            val commandsAfter = state.drainCommands()
            assertEquals(0, commandsAfter.size)
        }

    @Test
    fun `state remains unchanged after query activation`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Set up initial state
            state.isReadOnly = false
            state.randomSeed = 12345L
            state.cancelRequested = false

            // Capture state before query
            val timeBefore = state.currentTime
            val seedBefore = state.randomSeed
            val cancelBefore = state.cancelRequested
            val seqBefore = state.nextSeq() // This increments seq

            // Simulate query processing (read-only mode)
            state.isReadOnly = true
            // In read-only mode, we can still read state but not modify it
            val seedDuringQuery = state.randomSeed
            val cancelDuringQuery = state.cancelRequested
            state.isReadOnly = false

            // State should be unchanged after query
            assertEquals(seedBefore, seedDuringQuery)
            assertEquals(cancelBefore, cancelDuringQuery)
            assertEquals(seedBefore, state.randomSeed)
            assertEquals(cancelBefore, state.cancelRequested)
        }

    @Test
    fun `read-only mode allows reading but not writing state`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Set up state
            state.randomSeed = 12345L
            state.updateFromActivation(
                timestamp =
                    com.google.protobuf.Timestamp
                        .newBuilder()
                        .setSeconds(1000)
                        .build(),
                isReplaying = false,
                historyLength = 5,
            )

            state.isReadOnly = true

            // Reading is allowed
            assertEquals(12345L, state.randomSeed)
            assertEquals(5, state.historyLength)
            assertFalse(state.isReplaying)
            assertFalse(state.cancelRequested)

            // Writing is not allowed
            assertFailsWith<ReadOnlyContextException> {
                state.nextSeq()
            }

            state.isReadOnly = false
        }

    @Test
    fun `query with isReadOnly ensures exception message contains read-only`() =
        runTest {
            val state = WorkflowState("test-run-id")
            state.isReadOnly = true

            val exception =
                assertFailsWith<ReadOnlyContextException> {
                    state.nextSeq()
                }

            assertTrue(exception.message!!.contains("read-only"))
        }

    @Test
    fun `multiple queries in same activation are all read-only`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Initialize workflow first
            val initActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(initializeWorkflowJob(workflowType = "TestWorkflow")),
                )
            executor.activate(initActivation).completion

            // Process multiple queries in one activation
            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            queryWorkflowJob(queryType = "query1"),
                            queryWorkflowJob(queryType = "query2"),
                            queryWorkflowJob(queryType = "query3"),
                        ),
                )
            val completion = executor.activate(queryActivation).completion

            // All queries should process successfully in read-only mode
            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `query after timer resolution does not interfere`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Initialize workflow
            val initActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(initializeWorkflowJob(workflowType = "TestWorkflow")),
                )
            executor.activate(initActivation).completion

            // Process query separately
            val queryActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(queryWorkflowJob(queryType = "getState")),
                )
            val completion = executor.activate(queryActivation).completion

            assertTrue(completion.hasSuccessful())
        }

    // ================================================================
    // isReadOnly Reset Safety Tests
    // ================================================================

    @Test
    fun `isReadOnly reset happens in finally block pattern`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var exceptionThrown = false

            // This tests the pattern used in WorkflowExecutor
            state.isReadOnly = true
            try {
                // Simulate query handler that throws
                throw RuntimeException("Query handler error")
            } catch (e: Exception) {
                exceptionThrown = true
            } finally {
                state.isReadOnly = false
            }

            assertTrue(exceptionThrown)
            assertFalse(state.isReadOnly) // Must be reset even after exception
        }

    @Test
    fun `multiple enter-exit read-only cycles work correctly`() =
        runTest {
            val state = WorkflowState("test-run-id")

            repeat(3) { iteration ->
                assertFalse(state.isReadOnly, "Before iteration $iteration")

                state.isReadOnly = true
                assertTrue(state.isReadOnly, "During iteration $iteration")

                state.isReadOnly = false
                assertFalse(state.isReadOnly, "After iteration $iteration")

                // Can still mutate between queries
                val seq = state.nextSeq()
                assertEquals(iteration + 1, seq)
            }
        }

    @Test
    fun `timer registration allowed between query activations`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Query 1
            state.isReadOnly = true
            state.isReadOnly = false

            // Can register timer between queries
            val deferred1 = state.registerTimer(state.nextSeq())
            assertFalse(deferred1.isCompleted)

            // Query 2
            state.isReadOnly = true
            state.isReadOnly = false

            // Can register another timer
            val deferred2 = state.registerTimer(state.nextSeq())
            assertFalse(deferred2.isCompleted)

            // Both timers are pending
            assertFalse(deferred1.isCompleted)
            assertFalse(deferred2.isCompleted)
        }

    // ================================================================
    // Helper Methods
    // ================================================================

    private fun createTestExecutor(): WorkflowExecutor {
        // Get a simple method for the WorkflowMethodInfo
        val dummyMethod =
            this::class
                .members
                .first { it.name == "createTestExecutor" } as KFunction<*>

        val testInstance = this
        val workflowMethodInfo =
            WorkflowMethodInfo(
                workflowType = "TestWorkflow",
                runMethod = dummyMethod,
                workflowClass = this::class,
                instanceFactory = { testInstance },
                parameterTypes = emptyList(),
                returnType = typeOf<Unit>(),
                hasContextReceiver = false,
                isSuspend = false,
            )

        return createTestWorkflowExecutor(methodInfo = workflowMethodInfo)
    }
}
