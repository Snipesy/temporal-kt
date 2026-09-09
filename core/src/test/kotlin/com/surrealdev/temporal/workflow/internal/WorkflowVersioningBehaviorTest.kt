package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.WorkflowRegistration
import com.surrealdev.temporal.core.VersioningBehavior
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import io.temporal.api.enums.v1.VersioningBehavior as ProtoVersioningBehavior

/**
 * The per-workflow versioning behavior travels on `WorkflowActivationCompletion.Success`; Core copies
 * it onto the workflow task completion and only falls back to the worker default when it is unset.
 */
class WorkflowVersioningBehaviorTest {
    @Workflow("PinnedWorkflow", versioningBehavior = VersioningBehavior.PINNED)
    class PinnedWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"
    }

    @Workflow("UnannotatedWorkflow")
    class UnannotatedWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "done"
    }

    @Test
    fun `annotation behavior is stamped on the completion`() =
        runTest {
            val completion = completeOnce(WorkflowRegistration("PinnedWorkflow", PinnedWorkflow::class))
            assertEquals(ProtoVersioningBehavior.VERSIONING_BEHAVIOR_PINNED, completion.successful.versioningBehavior)
        }

    @Test
    fun `unannotated workflow leaves the field unset so the worker default applies`() =
        runTest {
            val completion = completeOnce(WorkflowRegistration("UnannotatedWorkflow", UnannotatedWorkflow::class))
            assertEquals(
                ProtoVersioningBehavior.VERSIONING_BEHAVIOR_UNSPECIFIED,
                completion.successful.versioningBehavior,
            )
        }

    @Test
    fun `registration override wins over the annotation`() =
        runTest {
            val completion =
                completeOnce(
                    WorkflowRegistration(
                        "PinnedWorkflow",
                        PinnedWorkflow::class,
                        versioningBehavior = VersioningBehavior.AUTO_UPGRADE,
                    ),
                )
            assertEquals(
                ProtoVersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE,
                completion.successful.versioningBehavior,
            )
        }

    @Test
    fun `registry resolves the effective behavior`() {
        val registry = WorkflowRegistry()
        registry.register(WorkflowRegistration("PinnedWorkflow", PinnedWorkflow::class))
        registry.register(
            WorkflowRegistration(
                "UnannotatedWorkflow",
                UnannotatedWorkflow::class,
                versioningBehavior = VersioningBehavior.PINNED,
            ),
        )
        assertEquals(VersioningBehavior.PINNED, registry.lookup("PinnedWorkflow")!!.versioningBehavior)
        assertEquals(VersioningBehavior.PINNED, registry.lookup("UnannotatedWorkflow")!!.versioningBehavior)
    }

    /** Registers [registration], runs the workflow to completion in one activation, returns the completion. */
    private suspend fun completeOnce(
        registration: WorkflowRegistration,
    ): coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion {
        val registry = WorkflowRegistry().apply { register(registration) }
        val methodInfo = registry.lookup(registration.workflowType)!!
        val runId = "run-${UUID.randomUUID()}"
        val executor = createTestWorkflowExecutor(runId = runId, methodInfo = methodInfo)
        val activation =
            createActivation(
                runId = runId,
                jobs = listOf(initializeWorkflowJob(workflowType = registration.workflowType, arguments = emptyList())),
                isReplaying = false,
            )
        val completion = executor.activate(activation).completion
        check(completion.hasSuccessful()) { "expected a successful completion, got $completion" }
        return completion
    }
}
