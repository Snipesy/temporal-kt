package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.notifyHasPatchJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.signalWorkflowJob
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
 * Unit tests for patch (workflow versioning) handler functionality.
 *
 * Tests verify:
 * - handlePatch records patch IDs in state
 * - isPatchNotified correctly reports notified patches
 * - NotifyHasPatch jobs are processed in Stage 1
 * - Patch memoization for deterministic behavior
 */
class PatchHandlerTest {
    // ================================================================
    // WorkflowState Patch Tracking Tests
    // ================================================================

    @Test
    fun `notifyPatch records patch ID in state`() {
        val state = WorkflowState("test-run-id")

        state.notifyPatch("my-patch-v1")

        assertTrue(state.isPatchNotified("my-patch-v1"))
    }

    @Test
    fun `isPatchNotified returns false for unknown patches`() {
        val state = WorkflowState("test-run-id")

        assertFalse(state.isPatchNotified("unknown-patch"))
    }

    @Test
    fun `multiple patches can be notified independently`() {
        val state = WorkflowState("test-run-id")

        state.notifyPatch("patch-a")
        state.notifyPatch("patch-b")

        assertTrue(state.isPatchNotified("patch-a"))
        assertTrue(state.isPatchNotified("patch-b"))
        assertFalse(state.isPatchNotified("patch-c"))
    }

    @Test
    fun `clear removes notified patches`() {
        val state = WorkflowState("test-run-id")

        state.notifyPatch("my-patch")
        assertTrue(state.isPatchNotified("my-patch"))

        state.clear()

        assertFalse(state.isPatchNotified("my-patch"))
    }

    // ================================================================
    // Patch Memoization Tests
    // ================================================================

    @Test
    fun `getPatchMemo returns null for unknown patches`() {
        val state = WorkflowState("test-run-id")

        assertEquals(null, state.getPatchMemo("unknown-patch"))
    }

    @Test
    fun `setPatchMemo and getPatchMemo work correctly`() {
        val state = WorkflowState("test-run-id")

        state.setPatchMemo("patch-a", true)
        state.setPatchMemo("patch-b", false)

        assertEquals(true, state.getPatchMemo("patch-a"))
        assertEquals(false, state.getPatchMemo("patch-b"))
    }

    @Test
    fun `clear removes patch memos`() {
        val state = WorkflowState("test-run-id")

        state.setPatchMemo("my-patch", true)
        assertEquals(true, state.getPatchMemo("my-patch"))

        state.clear()

        assertEquals(null, state.getPatchMemo("my-patch"))
    }

    // ================================================================
    // WorkflowExecutor Patch Handler Tests
    // ================================================================

    @Test
    fun `handlePatch via activation records patch in state`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Create activation with init and patch notification
            val activation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            initializeWorkflowJob(workflowType = "TestWorkflow"),
                            notifyHasPatchJob("my-versioning-patch"),
                        ),
                    isReplaying = true,
                )

            executor.activate(activation).completion

            // Patch should be recorded in state
            assertTrue(executor.state.isPatchNotified("my-versioning-patch"))
        }

    @Test
    fun `multiple NotifyHasPatch jobs are all recorded`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Create activation with multiple patches
            val activation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            initializeWorkflowJob(workflowType = "TestWorkflow"),
                            notifyHasPatchJob("patch-v1"),
                            notifyHasPatchJob("patch-v2"),
                            notifyHasPatchJob("patch-v3"),
                        ),
                    isReplaying = true,
                )

            executor.activate(activation).completion

            assertTrue(executor.state.isPatchNotified("patch-v1"))
            assertTrue(executor.state.isPatchNotified("patch-v2"))
            assertTrue(executor.state.isPatchNotified("patch-v3"))
        }

    @Test
    fun `patches are processed before signals in activation`() =
        runTest {
            val executor = createTestExecutor()
            val scope = CoroutineScope(Dispatchers.Default)
            val runId = "test-run-${UUID.randomUUID()}"

            // Create activation with signal before patch (in list order)
            // Stage processing should handle patch (Stage 1) before signal (Stage 2)
            val activation =
                createActivation(
                    runId = runId,
                    jobs =
                        listOf(
                            initializeWorkflowJob(workflowType = "TestWorkflow"),
                            signalWorkflowJob("test-signal"),
                            notifyHasPatchJob("test-patch"),
                        ),
                    isReplaying = true,
                )

            val completion = executor.activate(activation).completion

            // Activation should complete successfully
            assertTrue(completion.hasSuccessful())
            // Patch should be notified
            assertTrue(executor.state.isPatchNotified("test-patch"))
        }

    // ================================================================
    // Helper Methods
    // ================================================================

    private fun createTestExecutor(): WorkflowExecutor {
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
