package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.timestamp
import com.surrealdev.temporal.workflow.ActivityCancellationType
import coresdk.activity_result.ActivityResult
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.test.runTest
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.toKotlinInstant

/**
 * Unit tests for [WorkflowState].
 *
 * Tests cover:
 * - Read-only mode protections
 * - Condition registry behavior
 * - Timer and activity registration/resolution
 * - Command management
 * - State updates from activations
 */
class WorkflowStateTest {
    // ================================================================
    // Read-Only Mode Tests
    // ================================================================

    @Test
    fun `nextSeq throws ReadOnlyContextException when isReadOnly is true`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = true

        val exception =
            assertFailsWith<ReadOnlyContextException> {
                state.nextSeq()
            }
        assertTrue(exception.message!!.contains("read-only"))
    }

    @Test
    fun `nextSeq increments sequence when isReadOnly is false`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = false

        val seq1 = state.nextSeq()
        val seq2 = state.nextSeq()
        val seq3 = state.nextSeq()

        assertEquals(1, seq1)
        assertEquals(2, seq2)
        assertEquals(3, seq3)
    }

    @Test
    fun `registerTimer throws ReadOnlyContextException when isReadOnly is true`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = true

        val exception =
            assertFailsWith<ReadOnlyContextException> {
                state.registerTimer(1)
            }
        assertTrue(exception.message!!.contains("read-only"))
    }

    @Test
    fun `registerTimer returns deferred when isReadOnly is false`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = false

        val deferred = state.registerTimer(1)

        assertNotNull(deferred)
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `registerTimerContinuation throws ReadOnlyContextException when isReadOnly is true`() =
        runTest {
            val state = WorkflowState("test-run-id")
            state.isReadOnly = true

            val exception =
                assertFailsWith<ReadOnlyContextException> {
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                        state.registerTimerContinuation(1, continuation)
                    }
                }
            assertTrue(exception.message!!.contains("read-only"))
        }

    @Test
    fun `registerActivity throws ReadOnlyContextException when isReadOnly is true`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = true
        val serializer = KotlinxJsonSerializer()

        val handle =
            ActivityHandleImpl<String>(
                activityId = "test",
                seq = 1,
                activityType = "Test::run",
                state = state,
                serializer = serializer,
                returnType = typeOf<String>(),
                cancellationType = ActivityCancellationType.TRY_CANCEL,
            )

        val exception =
            assertFailsWith<ReadOnlyContextException> {
                state.registerActivity(1, handle)
            }
        assertTrue(exception.message!!.contains("read-only"))
    }

    @Test
    fun `registerActivity stores handle when isReadOnly is false`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = false
        val serializer = KotlinxJsonSerializer()

        val handle =
            ActivityHandleImpl<String>(
                activityId = "test",
                seq = 1,
                activityType = "Test::run",
                state = state,
                serializer = serializer,
                returnType = typeOf<String>(),
                cancellationType = ActivityCancellationType.TRY_CANCEL,
            )

        state.registerActivity(1, handle)

        val retrieved = state.getActivity(1)
        assertNotNull(retrieved)
    }

    @Test
    fun `registerCondition throws ReadOnlyContextException when isReadOnly is true`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = true

        val exception =
            assertFailsWith<ReadOnlyContextException> {
                state.registerCondition { true }
            }
        assertTrue(exception.message!!.contains("read-only"))
    }

    @Test
    fun `registerCondition returns deferred when isReadOnly is false`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = false

        val deferred = state.registerCondition { false }

        assertNotNull(deferred)
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `addCommand throws ReadOnlyContextException when isReadOnly is true`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = true

        val command =
            coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
                .newBuilder()
                .build()

        val exception =
            assertFailsWith<ReadOnlyContextException> {
                state.addCommand(command)
            }
        assertTrue(exception.message!!.contains("read-only"))
    }

    @Test
    fun `addCommand succeeds when isReadOnly is false`() {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = false

        val command =
            coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
                .newBuilder()
                .build()

        state.addCommand(command)

        assertTrue(state.hasCommands())
    }

    // ================================================================
    // Condition Registry Tests
    // ================================================================

    @Test
    fun `checkConditions completes deferred when condition is true`() =
        runTest {
            val state = WorkflowState("test-run-id")

            val deferred = state.registerCondition { true }
            assertFalse(deferred.isCompleted)

            state.checkConditions()

            assertTrue(deferred.isCompleted)
            // Should complete normally (not exceptionally)
            deferred.await()
        }

    @Test
    fun `checkConditions does not complete deferred when condition is false`() {
        val state = WorkflowState("test-run-id")

        val deferred = state.registerCondition { false }
        assertFalse(deferred.isCompleted)

        state.checkConditions()

        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `checkConditions completes exceptionally when condition throws`() =
        runTest {
            val state = WorkflowState("test-run-id")
            val testException = IllegalStateException("Test error")

            val deferred = state.registerCondition { throw testException }
            assertFalse(deferred.isCompleted)

            state.checkConditions()

            assertTrue(deferred.isCompleted)

            val exception =
                assertFailsWith<IllegalStateException> {
                    deferred.await()
                }
            assertEquals("Test error", exception.message)
        }

    @Test
    fun `checkConditions removes completed conditions`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var evaluationCount = 0

            // Condition returns true on first check
            val deferred =
                state.registerCondition {
                    evaluationCount++
                    true
                }

            // First check should complete and remove
            state.checkConditions()
            assertTrue(deferred.isCompleted)
            assertEquals(1, evaluationCount)

            // Second check should not re-evaluate (condition was removed)
            state.checkConditions()
            assertEquals(1, evaluationCount)
        }

    @Test
    fun `checkConditions handles multiple conditions becoming true simultaneously`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var flag = false

            // Both conditions depend on the same flag
            val deferred1 = state.registerCondition { flag }
            val deferred2 = state.registerCondition { flag }

            // Initially, both should be pending
            state.checkConditions()
            assertFalse(deferred1.isCompleted)
            assertFalse(deferred2.isCompleted)

            // Set flag to true - both should complete
            flag = true
            state.checkConditions()
            assertTrue(deferred1.isCompleted)
            assertTrue(deferred2.isCompleted)

            // Both should complete normally
            deferred1.await()
            deferred2.await()
        }

    @Test
    fun `checkConditions evaluates conditions in registration order`() =
        runTest {
            val state = WorkflowState("test-run-id")
            val evaluationOrder = mutableListOf<Int>()

            val deferred1 =
                state.registerCondition {
                    evaluationOrder.add(1)
                    true
                }
            val deferred2 =
                state.registerCondition {
                    evaluationOrder.add(2)
                    true
                }
            val deferred3 =
                state.registerCondition {
                    evaluationOrder.add(3)
                    true
                }

            state.checkConditions()

            assertEquals(listOf(1, 2, 3), evaluationOrder)
            assertTrue(deferred1.isCompleted)
            assertTrue(deferred2.isCompleted)
            assertTrue(deferred3.isCompleted)
        }

    @Test
    fun `conditions cleared on eviction via clear()`() =
        runTest {
            val state = WorkflowState("test-run-id")
            var evaluationCount = 0

            val deferred =
                state.registerCondition {
                    evaluationCount++
                    false
                }

            // First check
            state.checkConditions()
            assertEquals(1, evaluationCount)
            assertFalse(deferred.isCompleted)

            // Clear state
            state.clear()

            // Deferred should be cancelled
            assertTrue(deferred.isCancelled)

            // Check should not evaluate the cleared condition
            state.checkConditions()
            assertEquals(1, evaluationCount) // No additional evaluation
        }

    // ================================================================
    // Timer Tests
    // ================================================================

    @Test
    fun `resolveTimer completes registered timer deferred`() =
        runTest {
            val state = WorkflowState("test-run-id")

            val deferred = state.registerTimer(42)
            assertFalse(deferred.isCompleted)

            state.resolveTimer(42)

            assertTrue(deferred.isCompleted)
            deferred.await() // Should complete normally
        }

    @Test
    fun `resolveTimer handles non-existent timer gracefully`() {
        val state = WorkflowState("test-run-id")

        // Should not throw for non-existent timer
        state.resolveTimer(999)
    }

    @Test
    fun `multiple timers can be registered and resolved independently`() =
        runTest {
            val state = WorkflowState("test-run-id")

            val deferred1 = state.registerTimer(1)
            val deferred2 = state.registerTimer(2)
            val deferred3 = state.registerTimer(3)

            assertFalse(deferred1.isCompleted)
            assertFalse(deferred2.isCompleted)
            assertFalse(deferred3.isCompleted)

            // Resolve in non-sequential order
            state.resolveTimer(2)
            assertFalse(deferred1.isCompleted)
            assertTrue(deferred2.isCompleted)
            assertFalse(deferred3.isCompleted)

            state.resolveTimer(1)
            assertTrue(deferred1.isCompleted)
            assertTrue(deferred2.isCompleted)
            assertFalse(deferred3.isCompleted)

            state.resolveTimer(3)
            assertTrue(deferred1.isCompleted)
            assertTrue(deferred2.isCompleted)
            assertTrue(deferred3.isCompleted)
        }

    @Test
    fun `timers cleared on eviction via clear()`() =
        runTest {
            val state = WorkflowState("test-run-id")

            val deferred = state.registerTimer(1)
            assertFalse(deferred.isCompleted)

            state.clear()

            assertTrue(deferred.isCancelled)
        }

    // ================================================================
    // Activity Tests
    // ================================================================

    @Test
    fun `resolveActivity with completed result completes handle`() =
        runTest {
            val state = WorkflowState("test-run-id")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            val handle =
                ActivityHandleImpl<String>(
                    activityId = "test",
                    seq = 1,
                    activityType = "Test::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            state.registerActivity(1, handle)
            assertFalse(handle.isDone)

            val resultPayload = Payload.newBuilder().build()
            val completed =
                ActivityResult.Success
                    .newBuilder()
                    .setResult(resultPayload)
                    .build()
            val resolution =
                ActivityResult.ActivityResolution
                    .newBuilder()
                    .setCompleted(completed)
                    .build()

            state.resolveActivity(1, resolution)

            assertTrue(handle.isDone)
        }

    @Test
    fun `resolveActivity with failed result completes exceptionally`() =
        runTest {
            val state = WorkflowState("test-run-id")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            val handle =
                ActivityHandleImpl<String>(
                    activityId = "test",
                    seq = 1,
                    activityType = "Test::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            state.registerActivity(1, handle)
            assertFalse(handle.isDone)

            val failure =
                Failure
                    .newBuilder()
                    .setMessage("Activity failed!")
                    .build()
            val failed =
                ActivityResult.Failure
                    .newBuilder()
                    .setFailure(failure)
                    .build()
            val resolution =
                ActivityResult.ActivityResolution
                    .newBuilder()
                    .setFailed(failed)
                    .build()

            state.resolveActivity(1, resolution)

            assertTrue(handle.isDone)

            val exception =
                assertFailsWith<com.surrealdev.temporal.workflow.ActivityFailureException> {
                    handle.result()
                }
            assertEquals("Activity failed!", exception.message)
        }

    @Test
    fun `resolveActivity with cancelled result completes exceptionally`() =
        runTest {
            val state = WorkflowState("test-run-id")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            val handle =
                ActivityHandleImpl<String>(
                    activityId = "test",
                    seq = 1,
                    activityType = "Test::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            state.registerActivity(1, handle)
            assertFalse(handle.isDone)

            val cancelled = ActivityResult.Cancellation.newBuilder().build()
            val resolution =
                ActivityResult.ActivityResolution
                    .newBuilder()
                    .setCancelled(cancelled)
                    .build()

            state.resolveActivity(1, resolution)

            assertTrue(handle.isDone)

            assertFailsWith<com.surrealdev.temporal.workflow.ActivityCancelledException> {
                handle.result()
            }
        }

    @Test
    fun `resolveActivity handles non-existent activity gracefully`() {
        val state = WorkflowState("test-run-id")

        val completed = ActivityResult.Success.newBuilder().build()
        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setCompleted(completed)
                .build()

        // Should not throw for non-existent activity
        state.resolveActivity(999, resolution)
    }

    @Test
    fun `activities cleared on eviction via clear()`() =
        runTest {
            val state = WorkflowState("test-run-id")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            val handle =
                ActivityHandleImpl<String>(
                    activityId = "test",
                    seq = 1,
                    activityType = "Test::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            state.registerActivity(1, handle)
            assertFalse(handle.resultDeferred.isCompleted)

            state.clear()

            assertTrue(handle.resultDeferred.isCancelled)
        }

    // ================================================================
    // Command and State Tests
    // ================================================================

    @Test
    fun `drainCommands returns all commands and clears list`() {
        val state = WorkflowState("test-run-id")

        val command1 =
            coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
                .newBuilder()
                .build()
        val command2 =
            coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
                .newBuilder()
                .build()

        state.addCommand(command1)
        state.addCommand(command2)
        assertTrue(state.hasCommands())

        val commands = state.drainCommands()

        assertEquals(2, commands.size)
        assertFalse(state.hasCommands())

        // Drain again should return empty
        val emptyCommands = state.drainCommands()
        assertTrue(emptyCommands.isEmpty())
    }

    @Test
    fun `updateFromActivation updates time and replay flag`() {
        val state = WorkflowState("test-run-id")

        // Initial values
        assertEquals(0L, state.currentTime.toEpochMilliseconds())
        assertFalse(state.isReplaying)
        assertEquals(0, state.historyLength)

        // Update with new values
        val timestamp = timestamp(1234567890L, 500000000)
        state.updateFromActivation(
            timestamp = timestamp,
            isReplaying = true,
            historyLength = 42,
        )

        // Verify updated values
        val javaInstant = java.time.Instant.ofEpochSecond(1234567890L, 500000000)
        assertEquals(javaInstant.toKotlinInstant(), state.currentTime)
        assertTrue(state.isReplaying)
        assertEquals(42, state.historyLength)
    }

    @Test
    fun `updateFromActivation handles null timestamp`() {
        val state = WorkflowState("test-run-id")

        // Set an initial time
        val initialTimestamp = timestamp(1000L)
        state.updateFromActivation(
            timestamp = initialTimestamp,
            isReplaying = false,
            historyLength = 1,
        )
        val initialTime = state.currentTime

        // Update with null timestamp should preserve existing time
        state.updateFromActivation(
            timestamp = null,
            isReplaying = true,
            historyLength = 2,
        )

        assertEquals(initialTime, state.currentTime)
        assertTrue(state.isReplaying)
        assertEquals(2, state.historyLength)
    }

    @Test
    fun `randomSeed can be updated`() {
        val state = WorkflowState("test-run-id")

        assertEquals(0L, state.randomSeed)

        state.randomSeed = 12345L
        assertEquals(12345L, state.randomSeed)

        state.randomSeed = 99999L
        assertEquals(99999L, state.randomSeed)
    }

    @Test
    fun `cancelRequested flag can be set`() {
        val state = WorkflowState("test-run-id")

        assertFalse(state.cancelRequested)

        state.cancelRequested = true
        assertTrue(state.cancelRequested)
    }

    @Test
    fun `clear resets all pending operations`() =
        runTest {
            val state = WorkflowState("test-run-id")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            // Register various operations
            val timerDeferred = state.registerTimer(1)
            val handle =
                ActivityHandleImpl<String>(
                    activityId = "test",
                    seq = 2,
                    activityType = "Test::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )
            state.registerActivity(2, handle)
            val conditionDeferred = state.registerCondition { false }
            state.addCommand(
                coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
                    .newBuilder()
                    .build(),
            )

            // Verify all are active
            assertFalse(timerDeferred.isCompleted)
            assertFalse(handle.resultDeferred.isCompleted)
            assertFalse(conditionDeferred.isCompleted)
            assertTrue(state.hasCommands())

            // Clear everything
            state.clear()

            // Verify all are cancelled/cleared
            assertTrue(timerDeferred.isCancelled)
            assertTrue(handle.resultDeferred.isCancelled)
            assertTrue(conditionDeferred.isCancelled)
            assertFalse(state.hasCommands())
        }

    @Test
    fun `runId is set correctly`() {
        val state = WorkflowState("test-run-id-123")

        assertEquals("test-run-id-123", state.runId)
    }

    // ================================================================
    // Condition Removal Tests
    // ================================================================

    @Test
    fun `removeCondition removes and returns true when deferred exists`() {
        val state = WorkflowState("test-run-id")
        val deferred = state.registerCondition { false }

        val removed = state.removeCondition(deferred)

        assertTrue(removed)
        // Verify the condition is no longer tracked by checking that checkConditions
        // doesn't complete the deferred (since it's been removed)
        state.checkConditions()
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `removeCondition returns false when deferred not found`() {
        val state = WorkflowState("test-run-id")
        val unknownDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

        val removed = state.removeCondition(unknownDeferred)

        assertFalse(removed)
    }

    @Test
    fun `removeCondition does not affect other conditions`() {
        val state = WorkflowState("test-run-id")
        var condition1Met = false
        var condition2Met = false
        val deferred1 = state.registerCondition { condition1Met }
        val deferred2 = state.registerCondition { condition2Met }

        // Remove first condition
        state.removeCondition(deferred1)

        // Second condition should still work
        condition2Met = true
        state.checkConditions()

        assertFalse(deferred1.isCompleted)
        assertTrue(deferred2.isCompleted)
    }

    @Test
    fun `checkConditions skips already completed deferreds`() {
        val state = WorkflowState("test-run-id")
        val deferred = state.registerCondition { false }

        // Simulate external cancellation (like from withTimeout)
        deferred.cancel()

        // Should not throw, should remove the cancelled condition
        state.checkConditions()

        // Verify it was cleaned up (would throw if not removed on second call
        // since the predicate would be evaluated again)
        state.checkConditions()
    }

    @Test
    fun `checkConditions removes cancelled conditions from registry`() {
        val state = WorkflowState("test-run-id")
        var otherConditionMet = false
        val cancelledDeferred =
            state.registerCondition {
                throw IllegalStateException("Should not be called after cancellation")
            }
        val otherDeferred = state.registerCondition { otherConditionMet }

        // Cancel the first condition
        cancelledDeferred.cancel()

        // This should skip the cancelled condition and not throw
        state.checkConditions()

        // Verify the other condition still works
        otherConditionMet = true
        state.checkConditions()
        assertTrue(otherDeferred.isCompleted)
    }

    // ================================================================
    // NextSeq Overflow Tests (Sprint 4)
    // ================================================================

    @Test
    fun `nextSeq logs warning at 1 billion operations`() {
        val state = WorkflowState("test-run")
        state.isReadOnly = false

        // Use reflection to set nextSeq to 999,999,999
        val field = WorkflowState::class.java.getDeclaredField("nextSeq")
        field.isAccessible = true
        field.setInt(state, 999_999_999)

        // Capture logs
        val handler = TestLogHandler()
        val logger = Logger.getLogger(WorkflowState::class.java.name)
        val originalLevel = logger.level
        val originalUseParentHandlers = logger.useParentHandlers
        logger.level = Level.ALL
        logger.useParentHandlers = false
        logger.addHandler(handler)
        handler.level = Level.ALL

        val seq = state.nextSeq()
        assertEquals(999_999_999, seq)
        assertTrue(
            handler.warnings.any { it.contains("1 billion") },
            "Expected warning message about 1 billion operations but got: ${handler.warnings}",
        )

        logger.removeHandler(handler)
        logger.level = originalLevel
        logger.useParentHandlers = originalUseParentHandlers
    }

    @Test
    fun `nextSeq logs severe warning at 2 billion operations`() {
        val state = WorkflowState("test-run")
        state.isReadOnly = false

        // Use reflection to set nextSeq to 1,999,999,999
        val field = WorkflowState::class.java.getDeclaredField("nextSeq")
        field.isAccessible = true
        field.setInt(state, 1_999_999_999)

        // Capture logs
        val handler = TestLogHandler()
        val logger = Logger.getLogger(WorkflowState::class.java.name)
        val originalLevel = logger.level
        val originalUseParentHandlers = logger.useParentHandlers
        logger.level = Level.ALL
        logger.useParentHandlers = false
        logger.addHandler(handler)
        handler.level = Level.ALL

        val seq = state.nextSeq()
        assertEquals(1_999_999_999, seq)
        assertTrue(
            handler.severeMessages.any { it.contains("CRITICAL") && it.contains("2 billion") },
            "Expected severe message about 2 billion operations but got: ${handler.severeMessages}",
        )

        logger.removeHandler(handler)
        logger.level = originalLevel
        logger.useParentHandlers = originalUseParentHandlers
    }

    @Test
    fun `nextSeq throws IllegalStateException at MAX_VALUE`() {
        val state = WorkflowState("test-run")
        state.isReadOnly = false

        // Set to MAX_VALUE
        val field = WorkflowState::class.java.getDeclaredField("nextSeq")
        field.isAccessible = true
        field.setInt(state, Int.MAX_VALUE)

        val exception =
            assertFailsWith<IllegalStateException> {
                state.nextSeq()
            }
        assertTrue(exception.message!!.contains("Sequence overflow"))
    }

    @Test
    fun `nextSeq increments normally below thresholds`() {
        val state = WorkflowState("test-run")
        state.isReadOnly = false

        // Verify normal operation
        val seq1 = state.nextSeq()
        val seq2 = state.nextSeq()
        assertEquals(1, seq1)
        assertEquals(2, seq2)
    }

    // ================================================================
    // Handle Integration Tests (Sprint 4)
    // ================================================================

    @Test
    fun `registerActivity stores handle in pending activities map`() =
        runTest {
            val state = WorkflowState("test-run")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            val handle =
                ActivityHandleImpl<String>(
                    activityId = "test-activity",
                    seq = 42,
                    activityType = "TestActivity::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            state.registerActivity(42, handle)

            val retrieved = state.getActivity(42)
            assertNotNull(retrieved)
            assertEquals(42, retrieved.seq)
        }

    @Test
    fun `getActivity returns null for non-existent seq`() {
        val state = WorkflowState("test-run")
        state.isReadOnly = false

        val retrieved = state.getActivity(999)
        assertEquals(null, retrieved)
    }

    @Test
    fun `resolveActivity delegates to handle and removes from pending`() =
        runTest {
            val state = WorkflowState("test-run")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            val handle =
                ActivityHandleImpl<String>(
                    activityId = "test-activity",
                    seq = 1,
                    activityType = "TestActivity::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            state.registerActivity(1, handle)
            assertNotNull(state.getActivity(1))

            // Resolve with completed result
            val resultPayload = Payload.newBuilder().build()
            val completed =
                ActivityResult.Success
                    .newBuilder()
                    .setResult(resultPayload)
                    .build()
            val resolution =
                ActivityResult.ActivityResolution
                    .newBuilder()
                    .setCompleted(completed)
                    .build()

            state.resolveActivity(1, resolution)

            // Handle should be removed from pending
            assertEquals(null, state.getActivity(1))
            // Handle should be completed
            assertTrue(handle.isDone)
        }

    @Test
    fun `resolveActivity handles non-existent activity gracefully - no exception`() {
        val state = WorkflowState("test-run")
        state.isReadOnly = false

        val completed = ActivityResult.Success.newBuilder().build()
        val resolution =
            ActivityResult.ActivityResolution
                .newBuilder()
                .setCompleted(completed)
                .build()

        // Should not throw for non-existent activity
        state.resolveActivity(999, resolution)
    }

    // ================================================================
    // Cleanup Tests (Sprint 4)
    // ================================================================

    @Test
    fun `clear cancels activity handles via resultDeferred`() =
        runTest {
            val state = WorkflowState("test-run")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            val handle =
                ActivityHandleImpl<String>(
                    activityId = "test",
                    seq = 1,
                    activityType = "Test::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            state.registerActivity(1, handle)
            state.clear()

            assertTrue(handle.resultDeferred.isCancelled)
        }

    @Test
    fun `clear handles multiple activities correctly`() =
        runTest {
            val state = WorkflowState("test-run")
            state.isReadOnly = false
            val serializer = KotlinxJsonSerializer()

            val handle1 =
                ActivityHandleImpl<String>(
                    activityId = "test1",
                    seq = 1,
                    activityType = "Test::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<String>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            val handle2 =
                ActivityHandleImpl<Int>(
                    activityId = "test2",
                    seq = 2,
                    activityType = "Test::run",
                    state = state,
                    serializer = serializer,
                    returnType = typeOf<Int>(),
                    cancellationType = ActivityCancellationType.TRY_CANCEL,
                )

            state.registerActivity(1, handle1)
            state.registerActivity(2, handle2)
            state.clear()

            assertTrue(handle1.resultDeferred.isCancelled)
            assertTrue(handle2.resultDeferred.isCancelled)
        }
}

/**
 * Test log handler for capturing log messages.
 */
class TestLogHandler : Handler() {
    val warnings = mutableListOf<String>()
    val severeMessages = mutableListOf<String>()

    override fun publish(record: LogRecord) {
        when (record.level) {
            Level.WARNING -> warnings.add(record.message)
            Level.SEVERE -> severeMessages.add(record.message)
        }
    }

    override fun flush() {}

    override fun close() {}
}
