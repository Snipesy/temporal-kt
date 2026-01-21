package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.cancelWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.doUpdateJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.fireTimerJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.notifyHasPatchJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.queryWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.removeFromCacheJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveActivityJobCompleted
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveCancelExternalWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveChildWorkflowExecutionJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveChildWorkflowStartJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveNexusOperationJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveNexusOperationStartJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveSignalExternalWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.signalWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.updateRandomSeedJob
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivationJob
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for job classification logic in [WorkflowExecutor].
 *
 * These tests verify that jobs are correctly classified into the 5-stage activation
 * processing order:
 * - Stage 0: Initialization (InitializeWorkflow)
 * - Stage 1: Patches (NotifyHasPatch)
 * - Stage 2: Signals + Updates (SignalWorkflow, DoUpdate)
 * - Stage 3: Non-queries (FireTimer, ResolveActivity, UpdateRandomSeed, CancelWorkflow,
 *            child workflow resolutions, external workflow resolutions, nexus operations)
 * - Stage 4: Queries (QueryWorkflow)
 *
 * Note: RemoveFromCache is handled as an early exit before stage processing.
 */
class WorkflowExecutorJobOrderingTest {
    // Create a minimal executor for testing the classification methods
    private val executor = createTestExecutor()

    private fun createTestExecutor(): WorkflowExecutor {
        // Get a simple method for the WorkflowMethodInfo
        val dummyMethod =
            this::class.members.first { it.name == "createTestExecutor" }
                as kotlin.reflect.KFunction<*>

        // Create WorkflowMethodInfo with correct parameter order
        val workflowMethodInfo =
            WorkflowMethodInfo(
                workflowType = "TestWorkflow",
                runMethod = dummyMethod,
                implementation = this,
                parameterTypes = emptyList(),
                returnType = kotlin.reflect.typeOf<Unit>(),
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

    // ================================================================
    // isPatchJob Tests
    // ================================================================

    @Test
    fun `isPatchJob returns true for NotifyHasPatch`() {
        val job = notifyHasPatchJob("test-patch")
        assertTrue(executor.isPatchJob(job))
    }

    @Test
    fun `isPatchJob returns false for other job types`() {
        val nonPatchJobs =
            listOf(
                initializeWorkflowJob(),
                fireTimerJob(1),
                resolveActivityJobCompleted(1),
                queryWorkflowJob(),
                signalWorkflowJob(),
                doUpdateJob(),
                cancelWorkflowJob(),
                removeFromCacheJob(),
                updateRandomSeedJob(),
                resolveChildWorkflowStartJob(1),
                resolveChildWorkflowExecutionJob(1),
                resolveSignalExternalWorkflowJob(1),
                resolveCancelExternalWorkflowJob(1),
                resolveNexusOperationStartJob(1),
                resolveNexusOperationJob(1),
            )

        for (job in nonPatchJobs) {
            assertFalse(executor.isPatchJob(job), "Expected isPatchJob to return false for ${jobTypeName(job)}")
        }
    }

    // ================================================================
    // isSignalOrUpdateJob Tests
    // ================================================================

    @Test
    fun `isSignalOrUpdateJob returns true for SignalWorkflow`() {
        val job = signalWorkflowJob()
        assertTrue(executor.isSignalOrUpdateJob(job))
    }

    @Test
    fun `isSignalOrUpdateJob returns true for DoUpdate`() {
        val job = doUpdateJob()
        assertTrue(executor.isSignalOrUpdateJob(job))
    }

    @Test
    fun `isSignalOrUpdateJob returns false for other job types`() {
        val nonSignalOrUpdateJobs =
            listOf(
                initializeWorkflowJob(),
                fireTimerJob(1),
                resolveActivityJobCompleted(1),
                queryWorkflowJob(),
                notifyHasPatchJob(),
                cancelWorkflowJob(),
                removeFromCacheJob(),
                updateRandomSeedJob(),
                resolveChildWorkflowStartJob(1),
                resolveChildWorkflowExecutionJob(1),
                resolveSignalExternalWorkflowJob(1),
                resolveCancelExternalWorkflowJob(1),
                resolveNexusOperationStartJob(1),
                resolveNexusOperationJob(1),
            )

        for (job in nonSignalOrUpdateJobs) {
            assertFalse(
                executor.isSignalOrUpdateJob(job),
                "Expected isSignalOrUpdateJob to return false for ${jobTypeName(job)}",
            )
        }
    }

    // ================================================================
    // isNonQueryJob Tests
    // ================================================================

    @Test
    fun `isNonQueryJob returns true for FireTimer`() {
        val job = fireTimerJob(1)
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for ResolveActivity`() {
        val job = resolveActivityJobCompleted(1)
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for UpdateRandomSeed`() {
        val job = updateRandomSeedJob()
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for CancelWorkflow`() {
        val job = cancelWorkflowJob()
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for ResolveChildWorkflowExecutionStart`() {
        val job = resolveChildWorkflowStartJob(1)
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for ResolveChildWorkflowExecution`() {
        val job = resolveChildWorkflowExecutionJob(1)
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for ResolveSignalExternalWorkflow`() {
        val job = resolveSignalExternalWorkflowJob(1)
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for ResolveRequestCancelExternalWorkflow`() {
        val job = resolveCancelExternalWorkflowJob(1)
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for ResolveNexusOperationStart`() {
        val job = resolveNexusOperationStartJob(1)
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns true for ResolveNexusOperation`() {
        val job = resolveNexusOperationJob(1)
        assertTrue(executor.isNonQueryJob(job))
    }

    @Test
    fun `isNonQueryJob returns false for InitializeWorkflow`() {
        val job = initializeWorkflowJob()
        assertFalse(executor.isNonQueryJob(job), "InitializeWorkflow should be in Stage 0, not Stage 3")
    }

    @Test
    fun `isNonQueryJob returns false for RemoveFromCache`() {
        val job = removeFromCacheJob()
        assertFalse(executor.isNonQueryJob(job), "RemoveFromCache is handled as early exit, not Stage 3")
    }

    @Test
    fun `isNonQueryJob returns false for QueryWorkflow`() {
        val job = queryWorkflowJob()
        assertFalse(executor.isNonQueryJob(job), "QueryWorkflow should be in Stage 4, not Stage 3")
    }

    @Test
    fun `isNonQueryJob returns false for SignalWorkflow`() {
        val job = signalWorkflowJob()
        assertFalse(executor.isNonQueryJob(job), "SignalWorkflow should be in Stage 2, not Stage 3")
    }

    @Test
    fun `isNonQueryJob returns false for DoUpdate`() {
        val job = doUpdateJob()
        assertFalse(executor.isNonQueryJob(job), "DoUpdate should be in Stage 2, not Stage 3")
    }

    @Test
    fun `isNonQueryJob returns false for NotifyHasPatch`() {
        val job = notifyHasPatchJob()
        assertFalse(executor.isNonQueryJob(job), "NotifyHasPatch should be in Stage 1, not Stage 3")
    }

    // ================================================================
    // Completeness Tests
    // ================================================================

    @Test
    fun `all job types are classified into exactly one stage`() {
        // All possible job types
        val allJobs =
            listOf(
                initializeWorkflowJob() to "Stage 0 (Init)",
                notifyHasPatchJob() to "Stage 1 (Patches)",
                signalWorkflowJob() to "Stage 2 (Signals/Updates)",
                doUpdateJob() to "Stage 2 (Signals/Updates)",
                fireTimerJob(1) to "Stage 3 (Non-queries)",
                resolveActivityJobCompleted(1) to "Stage 3 (Non-queries)",
                updateRandomSeedJob() to "Stage 3 (Non-queries)",
                cancelWorkflowJob() to "Stage 3 (Non-queries)",
                resolveChildWorkflowStartJob(1) to "Stage 3 (Non-queries)",
                resolveChildWorkflowExecutionJob(1) to "Stage 3 (Non-queries)",
                resolveSignalExternalWorkflowJob(1) to "Stage 3 (Non-queries)",
                resolveCancelExternalWorkflowJob(1) to "Stage 3 (Non-queries)",
                resolveNexusOperationStartJob(1) to "Stage 3 (Non-queries)",
                resolveNexusOperationJob(1) to "Stage 3 (Non-queries)",
                queryWorkflowJob() to "Stage 4 (Queries)",
                removeFromCacheJob() to "Early exit (Eviction)",
            )

        for ((job, expectedStage) in allJobs) {
            val classificationCount = countStageClassifications(job)

            // Each job should be classified into at most one stage via the classification methods
            // (InitializeWorkflow, RemoveFromCache, and QueryWorkflow have their own handling)
            val isSpecialCase =
                job.hasInitializeWorkflow() ||
                    job.hasRemoveFromCache() ||
                    job.hasQueryWorkflow()

            if (isSpecialCase) {
                // Special cases should not be classified by any of the three methods
                assertTrue(
                    classificationCount == 0,
                    "$expectedStage: ${jobTypeName(
                        job,
                    )} should not be classified by isPatchJob/isSignalOrUpdateJob/isNonQueryJob",
                )
            } else {
                // All other jobs should be classified by exactly one method
                assertTrue(
                    classificationCount == 1,
                    "$expectedStage: ${jobTypeName(
                        job,
                    )} should be classified by exactly one method, but was classified by $classificationCount",
                )
            }
        }
    }

    @Test
    fun `classification methods are mutually exclusive`() {
        val allJobs =
            listOf(
                initializeWorkflowJob(),
                notifyHasPatchJob(),
                signalWorkflowJob(),
                doUpdateJob(),
                fireTimerJob(1),
                resolveActivityJobCompleted(1),
                updateRandomSeedJob(),
                cancelWorkflowJob(),
                resolveChildWorkflowStartJob(1),
                resolveChildWorkflowExecutionJob(1),
                resolveSignalExternalWorkflowJob(1),
                resolveCancelExternalWorkflowJob(1),
                resolveNexusOperationStartJob(1),
                resolveNexusOperationJob(1),
                queryWorkflowJob(),
                removeFromCacheJob(),
            )

        for (job in allJobs) {
            val isPatch = executor.isPatchJob(job)
            val isSignalOrUpdate = executor.isSignalOrUpdateJob(job)
            val isNonQuery = executor.isNonQueryJob(job)

            // At most one should be true
            val trueCount = listOf(isPatch, isSignalOrUpdate, isNonQuery).count { it }
            assertTrue(
                trueCount <= 1,
                "${jobTypeName(job)} is classified by multiple methods: " +
                    "isPatch=$isPatch, isSignalOrUpdate=$isSignalOrUpdate, isNonQuery=$isNonQuery",
            )
        }
    }

    // ================================================================
    // Helper Methods
    // ================================================================

    private fun countStageClassifications(job: WorkflowActivationJob): Int {
        var count = 0
        if (executor.isPatchJob(job)) count++
        if (executor.isSignalOrUpdateJob(job)) count++
        if (executor.isNonQueryJob(job)) count++
        return count
    }

    private fun jobTypeName(job: WorkflowActivationJob): String =
        when {
            job.hasInitializeWorkflow() -> "InitializeWorkflow"
            job.hasFireTimer() -> "FireTimer"
            job.hasResolveActivity() -> "ResolveActivity"
            job.hasUpdateRandomSeed() -> "UpdateRandomSeed"
            job.hasNotifyHasPatch() -> "NotifyHasPatch"
            job.hasSignalWorkflow() -> "SignalWorkflow"
            job.hasDoUpdate() -> "DoUpdate"
            job.hasQueryWorkflow() -> "QueryWorkflow"
            job.hasCancelWorkflow() -> "CancelWorkflow"
            job.hasRemoveFromCache() -> "RemoveFromCache"
            job.hasResolveChildWorkflowExecutionStart() -> "ResolveChildWorkflowStart"
            job.hasResolveChildWorkflowExecution() -> "ResolveChildWorkflowExecution"
            job.hasResolveSignalExternalWorkflow() -> "ResolveSignalExternalWorkflow"
            job.hasResolveRequestCancelExternalWorkflow() -> "ResolveCancelExternalWorkflow"
            job.hasResolveNexusOperationStart() -> "ResolveNexusOperationStart"
            job.hasResolveNexusOperation() -> "ResolveNexusOperation"
            else -> "Unknown"
        }
}
