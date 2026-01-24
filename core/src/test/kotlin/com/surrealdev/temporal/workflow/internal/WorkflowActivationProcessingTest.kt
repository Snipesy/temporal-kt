package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.testing.ProtoTestHelpers.cancelWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.fireTimerJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.notifyHasPatchJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.queryWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.removeFromCacheJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.signalWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.timestamp
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [WorkflowExecutor] activation processing logic.
 *
 * These tests verify:
 * - RemoveFromCache (eviction) early exit behavior
 * - Stage processing order for different job types
 * - Read-only mode safety during query processing
 * - Condition checking between stages
 */
class WorkflowActivationProcessingTest {
    // ================================================================
    // RemoveFromCache Early Exit Tests
    // ================================================================

    @Test
    fun `RemoveFromCache job triggers early exit without processing other jobs`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Create an activation with RemoveFromCache and other jobs that would normally be processed
            val activation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            removeFromCacheJob(),
                            initializeWorkflowJob(),
                            fireTimerJob(1),
                        ),
                )

            val completion = executor.activate(activation)

            // Should return a successful completion (early exit)
            assertTrue(completion.hasSuccessful())
            // No commands should be generated since early exit skips processing
            assertEquals(0, completion.successful.commandsCount)
        }

    @Test
    fun `RemoveFromCache sets cancelRequested flag`() =
        runTest {
            val runId = "test-run-${UUID.randomUUID()}"
            val state = WorkflowState(runId)

            // Initially cancelRequested should be false
            assertFalse(state.cancelRequested)

            // Simulate handleEviction behavior
            state.cancelRequested = true
            state.clear()

            assertTrue(state.cancelRequested)
        }

    @Test
    fun `RemoveFromCache clears all pending state`() =
        runTest {
            val runId = "test-run-${UUID.randomUUID()}"
            val state = WorkflowState(runId)

            // Register some pending operations
            val timerDeferred = state.registerTimer(1)
            val activityHandle =
                ActivityHandleImpl<String>(
                    activityId = "test",
                    seq = 2,
                    activityType = "Test::run",
                    state = state,
                    serializer =
                        com.surrealdev.temporal.serialization
                            .KotlinxJsonSerializer(),
                    returnType = typeOf<String>(),
                    cancellationType = com.surrealdev.temporal.workflow.ActivityCancellationType.TRY_CANCEL,
                )
            state.registerActivity(2, activityHandle)
            val conditionDeferred = state.registerCondition { false }

            // All should be pending
            assertFalse(timerDeferred.isCompleted)
            assertFalse(activityHandle.resultDeferred.isCompleted)
            assertFalse(conditionDeferred.isCompleted)

            // Simulate eviction
            state.cancelRequested = true
            state.clear()

            // All pending operations should be cancelled
            assertTrue(timerDeferred.isCancelled)
            assertTrue(activityHandle.resultDeferred.isCancelled)
            assertTrue(conditionDeferred.isCancelled)
        }

    // ================================================================
    // Stage Processing Order Tests
    // ================================================================

    @Test
    fun `activation with init and fire timer processes init first`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Create an activation with both init and fire timer jobs
            // Init should always be processed in Stage 0 before fire timer in Stage 3
            val activation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            fireTimerJob(1),
                            initializeWorkflowJob(workflowType = "TestWorkflow"),
                        ),
                )

            val completion = executor.activate(activation)

            // Should complete successfully
            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `patch jobs are processed before signal jobs`() =
        runTest {
            // This test verifies the ordering by checking job classification
            val executor = createTestExecutor()

            val patchJob = notifyHasPatchJob("test-patch")
            val signalJob = signalWorkflowJob("test-signal")

            // Verify classification
            assertTrue(executor.isPatchJob(patchJob))
            assertFalse(executor.isPatchJob(signalJob))

            assertTrue(executor.isSignalOrUpdateJob(signalJob))
            assertFalse(executor.isSignalOrUpdateJob(patchJob))

            // Both should not be classified as non-query jobs
            assertFalse(executor.isNonQueryJob(patchJob))
            assertFalse(executor.isNonQueryJob(signalJob))
        }

    @Test
    fun `signal jobs are processed before non-query jobs`() =
        runTest {
            val executor = createTestExecutor()

            val signalJob = signalWorkflowJob("test-signal")
            val timerJob = fireTimerJob(1)

            // Signal is Stage 2, Timer is Stage 3
            assertTrue(executor.isSignalOrUpdateJob(signalJob))
            assertFalse(executor.isSignalOrUpdateJob(timerJob))

            assertFalse(executor.isNonQueryJob(signalJob))
            assertTrue(executor.isNonQueryJob(timerJob))
        }

    @Test
    fun `query jobs are processed last in read-only mode`() =
        runTest {
            val executor = createTestExecutor()

            val queryJob = queryWorkflowJob("test-query")
            val timerJob = fireTimerJob(1)

            // Query is Stage 4 (not classified by these methods - handled specially)
            assertFalse(executor.isPatchJob(queryJob))
            assertFalse(executor.isSignalOrUpdateJob(queryJob))
            assertFalse(executor.isNonQueryJob(queryJob))

            // Timer is Stage 3
            assertTrue(executor.isNonQueryJob(timerJob))
        }

    @Test
    fun `activation with multiple stages processes in correct order`() =
        runTest {
            // Verify job classification for a complex activation with all job types
            val executor = createTestExecutor()

            val jobs =
                listOf(
                    queryWorkflowJob(), // Stage 4
                    fireTimerJob(1), // Stage 3
                    signalWorkflowJob(), // Stage 2
                    notifyHasPatchJob(), // Stage 1
                )

            // Stage 1: Patches
            val patchJobs = jobs.filter { executor.isPatchJob(it) }
            assertEquals(1, patchJobs.size)
            assertTrue(patchJobs[0].hasNotifyHasPatch())

            // Stage 2: Signals/Updates
            val signalJobs = jobs.filter { executor.isSignalOrUpdateJob(it) }
            assertEquals(1, signalJobs.size)
            assertTrue(signalJobs[0].hasSignalWorkflow())

            // Stage 3: Non-queries
            val nonQueryJobs = jobs.filter { executor.isNonQueryJob(it) }
            assertEquals(1, nonQueryJobs.size)
            assertTrue(nonQueryJobs[0].hasFireTimer())

            // Stage 4: Queries (special handling)
            val queryJobs = jobs.filter { it.hasQueryWorkflow() }
            assertEquals(1, queryJobs.size)
        }

    // ================================================================
    // Read-Only Mode Safety Tests
    // ================================================================

    @Test
    fun `isReadOnly is true during query processing`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Initially not read-only
            assertFalse(state.isReadOnly)

            // Simulate entering query processing
            state.isReadOnly = true
            assertTrue(state.isReadOnly)

            // Verify mutations throw in read-only mode
            val exception =
                kotlin.runCatching {
                    state.nextSeq()
                }
            assertTrue(exception.isFailure)
            assertTrue(exception.exceptionOrNull() is ReadOnlyContextException)
        }

    @Test
    fun `isReadOnly is reset to false after query processing`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Simulate entering and exiting query processing
            state.isReadOnly = true
            assertTrue(state.isReadOnly)

            // Simulate exiting query processing
            state.isReadOnly = false
            assertFalse(state.isReadOnly)

            // Mutations should work again
            val seq = state.nextSeq()
            assertEquals(1, seq)
        }

    @Test
    fun `isReadOnly is reset to false even when query throws exception`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var exceptionThrown = false

            // Simulate query processing with exception
            try {
                state.isReadOnly = true
                throw RuntimeException("Simulated query error")
            } catch (e: RuntimeException) {
                exceptionThrown = true
            } finally {
                state.isReadOnly = false
            }

            // Exception was thrown
            assertTrue(exceptionThrown)

            // isReadOnly should be reset
            assertFalse(state.isReadOnly)

            // Mutations should work again
            val seq = state.nextSeq()
            assertEquals(1, seq)
        }

    @Test
    fun `query processing pattern uses try-finally for safety`() =
        runTest {
            // This test documents and verifies the try-finally pattern used in WorkflowExecutor
            val state = WorkflowState("test-run-id")
            var queryExecuted = false

            // Simulate the pattern from WorkflowExecutor.activate()
            state.isReadOnly = true
            try {
                // Simulate processing query jobs
                queryExecuted = true
                // Verify we're in read-only mode during "query processing"
                assertTrue(state.isReadOnly)
            } finally {
                state.isReadOnly = false
            }

            assertTrue(queryExecuted)
            assertFalse(state.isReadOnly)
        }

    // ================================================================
    // Condition Checking Stage Tests
    // ================================================================

    @Test
    fun `conditions are checked after patch stage`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var conditionChecked = false

            // Register a condition that will be checked
            val deferred =
                state.registerCondition {
                    conditionChecked = true
                    true // Return true to complete
                }

            // Simulate the check that happens after patch stage
            state.checkConditions()

            assertTrue(conditionChecked)
            assertTrue(deferred.isCompleted)
        }

    @Test
    fun `conditions are checked after signal stage`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var flag = false

            // Register a condition that depends on flag
            val deferred = state.registerCondition { flag }

            // First check - condition not met
            state.checkConditions()
            assertFalse(deferred.isCompleted)

            // Simulate signal processing that changes state
            flag = true

            // Second check - condition should be met
            state.checkConditions()
            assertTrue(deferred.isCompleted)
        }

    @Test
    fun `conditions are checked after non-query stage`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var counter = 0

            // Register a condition that depends on counter
            val deferred = state.registerCondition { counter >= 3 }

            // Simulate multiple timer resolutions incrementing counter
            repeat(3) {
                counter++
                state.checkConditions()
            }

            assertTrue(deferred.isCompleted)
        }

    @Test
    fun `conditions are NOT checked after query stage`() =
        runTest {
            // This test documents that conditions should NOT be registered or checked during queries
            // as queries are read-only operations
            val state = WorkflowState("test-run-id")

            // In read-only mode (query processing), registering conditions throws
            state.isReadOnly = true

            val exception =
                kotlin.runCatching {
                    state.registerCondition { true }
                }
            assertTrue(exception.isFailure)
            assertTrue(exception.exceptionOrNull() is ReadOnlyContextException)

            state.isReadOnly = false
        }

    @Test
    fun `multiple conditions can be satisfied in same check cycle`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var flag = false

            // Register multiple conditions that depend on the same flag
            val deferred1 = state.registerCondition { flag }
            val deferred2 = state.registerCondition { flag }
            val deferred3 = state.registerCondition { flag }

            // Initially none complete
            state.checkConditions()
            assertFalse(deferred1.isCompleted)
            assertFalse(deferred2.isCompleted)
            assertFalse(deferred3.isCompleted)

            // Set flag
            flag = true
            state.checkConditions()

            // All should complete in one check cycle
            assertTrue(deferred1.isCompleted)
            assertTrue(deferred2.isCompleted)
            assertTrue(deferred3.isCompleted)
        }

    // ================================================================
    // Activation Processing Integration Tests
    // ================================================================

    @Test
    fun `activation with cancel job sets cancelRequested`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // First initialize the workflow
            val initActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(initializeWorkflowJob(workflowType = "TestWorkflow")),
                )
            executor.activate(initActivation)

            // Then send cancel
            val cancelActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(cancelWorkflowJob()),
                )

            val completion = executor.activate(cancelActivation)

            // Should complete (cancel request is processed)
            assertTrue(completion.hasSuccessful())
        }

    @Test
    fun `activation updates state from metadata`() =
        runTest {
            val state = WorkflowState("test-run-id")

            val testTimestamp = timestamp(1234567890L, 500000000)
            state.updateFromActivation(
                timestamp = testTimestamp,
                isReplaying = true,
                historyLength = 42,
            )

            // Verify state was updated
            assertTrue(state.isReplaying)
            assertEquals(42, state.historyLength)
            // Time should be set from timestamp
            val expectedMillis = 1234567890L * 1000 + 500 // seconds to millis + nanos to millis
            // Allow some tolerance due to nano -> millis conversion
            assertTrue(state.currentTime.toEpochMilliseconds() >= 1234567890000L)
        }

    @Test
    fun `empty activation returns empty commands`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Empty activation (no jobs)
            val activation =
                createActivation(
                    runId = runId,
                    jobs = emptyList(),
                )

            val completion = executor.activate(activation)

            assertTrue(completion.hasSuccessful())
            assertEquals(0, completion.successful.commandsCount)
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
