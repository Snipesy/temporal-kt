package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.fireTimerJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.timestamp
import com.surrealdev.temporal.testing.ProtoTestHelpers.updateRandomSeedJob
import coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
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
 * Unit tests verifying replay determinism at the activation level.
 *
 * These tests verify:
 * - Command generation produces identical results on replay
 * - Timer commands have deterministic sequence numbers
 * - State mutations are identical between original and replay
 * - Random seed produces deterministic values
 * - Workflow time progression is deterministic
 */
class ActivationReplayDeterminismTest {
    // ================================================================
    // Command Generation Determinism Tests
    // ================================================================

    @Test
    fun `timer commands have deterministic sequence numbers`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Generate sequence numbers
            val seq1 = state.nextSeq()
            val seq2 = state.nextSeq()
            val seq3 = state.nextSeq()

            // Sequence numbers should always be deterministic (1, 2, 3, ...)
            assertEquals(1, seq1)
            assertEquals(2, seq2)
            assertEquals(3, seq3)
        }

    @Test
    fun `multiple activations in sequence produce consistent command ordering`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Add commands in sequence
            val command1 =
                WorkflowCommand
                    .newBuilder()
                    .setStartTimer(
                        coresdk.workflow_commands.WorkflowCommands.StartTimer
                            .newBuilder()
                            .setSeq(state.nextSeq()),
                    ).build()
            state.addCommand(command1)

            val command2 =
                WorkflowCommand
                    .newBuilder()
                    .setStartTimer(
                        coresdk.workflow_commands.WorkflowCommands.StartTimer
                            .newBuilder()
                            .setSeq(state.nextSeq()),
                    ).build()
            state.addCommand(command2)

            // Drain commands
            val commands = state.drainCommands()

            // Commands should be in order of addition
            assertEquals(2, commands.size)
            assertEquals(1, commands[0].startTimer.seq)
            assertEquals(2, commands[1].startTimer.seq)
        }

    @Test
    fun `sequence numbers are deterministic across separate state instances with same history`() =
        runTest {
            // Simulate two separate state instances processing the same activation
            val state1 = WorkflowState("test-run-id-1")
            val state2 = WorkflowState("test-run-id-2")

            // Both should produce the same sequence numbers
            assertEquals(state1.nextSeq(), state2.nextSeq()) // Both should be 1
            assertEquals(state1.nextSeq(), state2.nextSeq()) // Both should be 2
            assertEquals(state1.nextSeq(), state2.nextSeq()) // Both should be 3
        }

    @Test
    fun `commands from same workflow are ordered deterministically`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Add various command types in a specific order
            state.addCommand(
                WorkflowCommand
                    .newBuilder()
                    .setStartTimer(
                        coresdk.workflow_commands.WorkflowCommands.StartTimer
                            .newBuilder()
                            .setSeq(state.nextSeq()),
                    ).build(),
            )

            state.addCommand(
                WorkflowCommand
                    .newBuilder()
                    .setScheduleActivity(
                        coresdk.workflow_commands.WorkflowCommands.ScheduleActivity
                            .newBuilder()
                            .setSeq(state.nextSeq()),
                    ).build(),
            )

            state.addCommand(
                WorkflowCommand
                    .newBuilder()
                    .setStartTimer(
                        coresdk.workflow_commands.WorkflowCommands.StartTimer
                            .newBuilder()
                            .setSeq(state.nextSeq()),
                    ).build(),
            )

            val commands = state.drainCommands()

            // Verify order is preserved
            assertEquals(3, commands.size)
            assertTrue(commands[0].hasStartTimer())
            assertEquals(1, commands[0].startTimer.seq)
            assertTrue(commands[1].hasScheduleActivity())
            assertEquals(2, commands[1].scheduleActivity.seq)
            assertTrue(commands[2].hasStartTimer())
            assertEquals(3, commands[2].startTimer.seq)
        }

    // ================================================================
    // State Mutation Determinism Tests
    // ================================================================

    @Test
    fun `state updates are identical between original and replay`() =
        runTest {
            val timestamp1 = timestamp(1000L, 500)
            val timestamp2 = timestamp(2000L, 600)

            // Original run
            val originalState = WorkflowState("original-run")
            originalState.updateFromActivation(
                timestamp = timestamp1,
                isReplaying = false,
                historyLength = 1,
            )

            // Replay run
            val replayState = WorkflowState("replay-run")
            replayState.updateFromActivation(
                timestamp = timestamp1,
                isReplaying = true,
                historyLength = 1,
            )

            // Times should be identical (isReplaying flag doesn't affect time)
            assertEquals(originalState.currentTime, replayState.currentTime)

            // Both should track the same history length
            assertEquals(originalState.historyLength, replayState.historyLength)

            // Update with second activation
            originalState.updateFromActivation(
                timestamp = timestamp2,
                isReplaying = false,
                historyLength = 2,
            )
            replayState.updateFromActivation(
                timestamp = timestamp2,
                isReplaying = true,
                historyLength = 2,
            )

            // Times should still be identical
            assertEquals(originalState.currentTime, replayState.currentTime)
        }

    @Test
    fun `random seed produces deterministic values on replay`() =
        runTest {
            val testSeed = 12345L

            // Original run
            val originalState = WorkflowState("original-run")
            originalState.randomSeed = testSeed

            // Replay run
            val replayState = WorkflowState("replay-run")
            replayState.randomSeed = testSeed

            // Both should have the same seed
            assertEquals(originalState.randomSeed, replayState.randomSeed)

            // Using the same seed, random number generators initialized with it
            // would produce identical sequences
            val rng1 = java.util.Random(originalState.randomSeed)
            val rng2 = java.util.Random(replayState.randomSeed)

            // Generate several random values
            repeat(10) {
                assertEquals(rng1.nextLong(), rng2.nextLong())
            }
        }

    @Test
    fun `workflow time progression is deterministic on replay`() =
        runTest {
            // Time is driven by activation timestamps, not wall clock
            // This ensures deterministic replay
            val state = WorkflowState("test-run-id")

            val time1 = timestamp(1000L)
            val time2 = timestamp(2000L)
            val time3 = timestamp(3000L)

            // Progress through activations
            state.updateFromActivation(timestamp = time1, isReplaying = false, historyLength = 1)
            val currentTime1 = state.currentTime.toEpochMilliseconds()

            state.updateFromActivation(timestamp = time2, isReplaying = false, historyLength = 2)
            val currentTime2 = state.currentTime.toEpochMilliseconds()

            state.updateFromActivation(timestamp = time3, isReplaying = false, historyLength = 3)
            val currentTime3 = state.currentTime.toEpochMilliseconds()

            // Time should progress monotonically with activation timestamps
            assertTrue(currentTime1 < currentTime2)
            assertTrue(currentTime2 < currentTime3)

            // Exact values should match activation timestamps (converted to millis)
            assertEquals(1000000L, currentTime1) // 1000 seconds = 1000000 millis
            assertEquals(2000000L, currentTime2)
            assertEquals(3000000L, currentTime3)
        }

    @Test
    fun `updateRandomSeed job updates seed deterministically`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Initialize workflow
            val initActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(initializeWorkflowJob(workflowType = "TestWorkflow", randomnessSeed = 111L)),
                )
            executor.activate(initActivation, scope)

            // Update random seed
            val seedActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(updateRandomSeedJob(seed = 999L)),
                )
            val completion = executor.activate(seedActivation, scope)

            // Should complete successfully
            assertTrue(completion.hasSuccessful())
        }

    // ================================================================
    // Replay Flag Tests
    // ================================================================

    @Test
    fun `isReplaying flag is set correctly from activation`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Initially not replaying
            assertFalse(state.isReplaying)

            // Update with replaying = true
            state.updateFromActivation(
                timestamp = timestamp(1000L),
                isReplaying = true,
                historyLength = 5,
            )
            assertTrue(state.isReplaying)

            // Update with replaying = false
            state.updateFromActivation(
                timestamp = timestamp(2000L),
                isReplaying = false,
                historyLength = 6,
            )
            assertFalse(state.isReplaying)
        }

    @Test
    fun `historyLength is updated from activation`() =
        runTest {
            val state = WorkflowState("test-run-id")

            assertEquals(0, state.historyLength)

            state.updateFromActivation(
                timestamp = timestamp(1000L),
                isReplaying = false,
                historyLength = 10,
            )
            assertEquals(10, state.historyLength)

            state.updateFromActivation(
                timestamp = timestamp(2000L),
                isReplaying = false,
                historyLength = 20,
            )
            assertEquals(20, state.historyLength)
        }

    // ================================================================
    // Pending Operation Determinism Tests
    // ================================================================

    @Test
    fun `pending timers are tracked by sequence number deterministically`() =
        runTest {
            val state = WorkflowState("test-run-id")

            // Register timers in sequence
            val deferred1 = state.registerTimer(1)
            val deferred2 = state.registerTimer(2)
            val deferred3 = state.registerTimer(3)

            // All should be pending
            assertFalse(deferred1.isCompleted)
            assertFalse(deferred2.isCompleted)
            assertFalse(deferred3.isCompleted)

            // Resolve in any order - resolution is by sequence number
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
    fun `activation replay produces same timer resolution order`() =
        runTest {
            // This test simulates the pattern where timer resolutions come in
            // the same order during replay as they did in the original run
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Initialize workflow
            val initActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(initializeWorkflowJob(workflowType = "TestWorkflow")),
                )
            val initCompletion = executor.activate(initActivation, scope)
            assertTrue(initCompletion.hasSuccessful())

            // Fire timers in sequence (simulating replay)
            val fireTimerActivation =
                createActivation(
                    runId = runId,
                    jobs = listOf(fireTimerJob(1)),
                    isReplaying = true,
                )
            val fireCompletion = executor.activate(fireTimerActivation, scope)
            assertTrue(fireCompletion.hasSuccessful())
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

        return WorkflowExecutor(
            runId = "test-run-id",
            methodInfo = workflowMethodInfo,
            serializer = KotlinxJsonSerializer(),
            taskQueue = "test-task-queue",
            namespace = "default",
        )
    }
}
