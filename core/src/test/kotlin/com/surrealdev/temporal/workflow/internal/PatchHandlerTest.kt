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
    // Patch/Init Ordering (replay determinism)
    // ================================================================

    @com.surrealdev.temporal.annotation.Workflow("PatchedAtStartWorkflow")
    class PatchedAtStartWorkflow {
        var sawPatch: Boolean? = null

        @com.surrealdev.temporal.annotation.WorkflowRun
        suspend fun com.surrealdev.temporal.workflow.WorkflowContext.run(): String {
            // Call patched() before the first suspension - the value must be identical
            // on the original run (true) and on replay (requires NotifyHasPatch to be
            // applied BEFORE the workflow body runs).
            sawPatch = patched("my-patch")
            awaitCondition { false }
            return "done"
        }
    }

    /**
     * On replay after eviction, core bundles NotifyHasPatch with InitializeWorkflow in a
     * single activation. Patch notifications must be applied before the workflow body first
     * runs, or `patched()` called before the first suspension returns false on replay
     * (true on the original run) - a nondeterminism bug. Mirrors Python's job ordering,
     * where patches are job set 0 and initialize-workflow is set 2.
     */
    @Test
    fun `patch notification bundled with init is visible to workflow code on replay`() =
        runTest {
            val workflow = PatchedAtStartWorkflow()
            val runMethod =
                PatchedAtStartWorkflow::class
                    .members
                    .first { it.name == "run" } as KFunction<*>
            val methodInfo =
                WorkflowMethodInfo(
                    workflowType = "PatchedAtStartWorkflow",
                    runMethod = runMethod,
                    workflowClass = PatchedAtStartWorkflow::class,
                    instanceFactory = { workflow },
                    parameterTypes = emptyList(),
                    returnType = typeOf<String>(),
                    hasContextReceiver = true,
                    isSuspend = true,
                )
            val runId = "test-run-${UUID.randomUUID()}"
            val executor =
                createTestWorkflowExecutor(
                    runId = runId,
                    methodInfo = methodInfo,
                )

            val completion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs =
                                listOf(
                                    initializeWorkflowJob(workflowType = "PatchedAtStartWorkflow"),
                                    notifyHasPatchJob("my-patch"),
                                ),
                            isReplaying = true,
                        ),
                    ).completion

            assertTrue(completion.hasSuccessful())
            assertEquals(
                true,
                workflow.sawPatch,
                "patched() before first suspension must see the bundled NotifyHasPatch on replay",
            )
        }

    @com.surrealdev.temporal.annotation.Workflow("DeprecatePatchWorkflow")
    class DeprecatePatchWorkflow {
        @com.surrealdev.temporal.annotation.WorkflowRun
        suspend fun com.surrealdev.temporal.workflow.WorkflowContext.run(): String {
            deprecatePatch("my-old-patch")
            awaitCondition { false }
            return "done"
        }
    }

    @Test
    fun `deprecatePatch emits deprecated SetPatchMarker command`() =
        runTest {
            val workflow = DeprecatePatchWorkflow()
            val runMethod =
                DeprecatePatchWorkflow::class
                    .members
                    .first { it.name == "run" } as KFunction<*>
            val methodInfo =
                WorkflowMethodInfo(
                    workflowType = "DeprecatePatchWorkflow",
                    runMethod = runMethod,
                    workflowClass = DeprecatePatchWorkflow::class,
                    instanceFactory = { workflow },
                    parameterTypes = emptyList(),
                    returnType = typeOf<String>(),
                    hasContextReceiver = true,
                    isSuspend = true,
                )
            val runId = "test-run-${UUID.randomUUID()}"
            val executor = createTestWorkflowExecutor(runId = runId, methodInfo = methodInfo)

            val completion =
                executor
                    .activate(
                        createActivation(
                            runId = runId,
                            jobs = listOf(initializeWorkflowJob(workflowType = "DeprecatePatchWorkflow")),
                            isReplaying = false,
                        ),
                    ).completion

            assertTrue(completion.hasSuccessful())
            val marker =
                completion.successful.commandsList
                    .single { it.hasSetPatchMarker() }
                    .setPatchMarker
            assertEquals("my-old-patch", marker.patchId)
            assertTrue(marker.deprecated, "SetPatchMarker should be marked deprecated")
        }

    // ================================================================
    // Helper Methods
    // ================================================================

    private fun createTestExecutor(): WorkflowExecutor =
        com.surrealdev.temporal.testing
            .createIdleTestWorkflowExecutor()
}
