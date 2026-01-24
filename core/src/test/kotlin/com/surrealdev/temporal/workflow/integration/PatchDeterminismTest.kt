package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.VersioningBehavior
import com.surrealdev.temporal.application.WorkerDeploymentVersion
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for workflow patching and worker deployment versioning.
 *
 * Tests verify:
 * - patched() returns correct values during first execution
 * - patched() is memoized within single execution (deterministic)
 * - Worker deployment versioning configures correctly
 */
@Tag("integration")
class PatchDeterminismTest {
    /**
     * Workflow that uses patched() to conditionally return different values.
     */
    @Workflow("SimplePatchWorkflow")
    class SimplePatchWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            if (patched("v2-new-format")) {
                "new-format-result"
            } else {
                "old-format-result"
            }
    }

    @Test
    fun `patched returns true and is memoized during first execution`() =
        runTemporalTest {
            val taskQueue = "test-patch-${UUID.randomUUID()}"

            // Configure with deployment versioning
            deployment(
                WorkerDeploymentVersion("patch-test-deployment", "v1.0"),
                useVersioning = true,
                defaultVersioningBehavior = VersioningBehavior.PINNED,
            )

            application {
                taskQueue(taskQueue) {
                    workflow(SimplePatchWorkflow())
                    workflow(MemoizedPatchWorkflow())
                }
            }

            val client = client()

            // Test 1: patched() returns true on first execution
            val handle1 =
                client.startWorkflow<String>(
                    workflowType = "SimplePatchWorkflow",
                    taskQueue = taskQueue,
                )
            assertEquals("new-format-result", handle1.result(timeout = 30.seconds))
            handle1.assertHistory { completed() }

            // Test 2: patched() is memoized (same ID returns same value)
            val handle2 =
                client.startWorkflow<List<Boolean>>(
                    workflowType = "MemoizedPatchWorkflow",
                    taskQueue = taskQueue,
                )
            val memoResult = handle2.result(timeout = 30.seconds)
            assertEquals(3, memoResult.size)
            assertTrue(memoResult.all { it }) // All true and equal
            handle2.assertHistory { completed() }
        }

    /**
     * Workflow that calls patched() multiple times with the same ID.
     */
    @Workflow("MemoizedPatchWorkflow")
    class MemoizedPatchWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<Boolean> =
            listOf(
                patched("memoized-patch"),
                patched("memoized-patch"),
                patched("memoized-patch"),
            )
    }

    /**
     * Workflow that uses multiple independent patches.
     */
    @Workflow("MultiplePatchWorkflow")
    class MultiplePatchWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): List<Boolean> =
            listOf(
                patched("feature-a"),
                patched("feature-b"),
                patched("feature-c"),
            )
    }

    @Test
    fun `multiple patches work independently with AUTO_UPGRADE versioning`() =
        runTemporalTest {
            val taskQueue = "test-multi-patch-${UUID.randomUUID()}"

            // Configure with AUTO_UPGRADE versioning behavior
            deployment(
                WorkerDeploymentVersion("multi-patch-deployment", "v2.0"),
                useVersioning = true,
                defaultVersioningBehavior = VersioningBehavior.AUTO_UPGRADE,
            )

            application {
                taskQueue(taskQueue) {
                    workflow(MultiplePatchWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<List<Boolean>>(
                    workflowType = "MultiplePatchWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // All patches should return true on first execution
            assertEquals(3, result.size)
            assertTrue(result.all { it })

            handle.assertHistory { completed() }
        }

    /**
     * Result class for conditional patch workflow.
     */
    @kotlinx.serialization.Serializable
    data class ConditionalPatchResult(
        val codePath: String,
        val enhancement: String,
        val featureX: Boolean,
    )

    /**
     * Workflow that uses patch-based conditional logic.
     */
    @Workflow("ConditionalPatchWorkflow")
    class ConditionalPatchWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): ConditionalPatchResult {
            val (codePath, enhancement) =
                if (patched("v2-enhanced-logic")) {
                    "v2" to "enabled"
                } else {
                    "v1" to "disabled"
                }
            val featureX = patched("feature-flag-x")
            return ConditionalPatchResult(codePath, enhancement, featureX)
        }
    }

    @Test
    fun `workflow uses correct code path with UNSPECIFIED versioning`() =
        runTemporalTest {
            val taskQueue = "test-conditional-${UUID.randomUUID()}"

            // Test with default UNSPECIFIED versioning behavior
            deployment(WorkerDeploymentVersion("conditional-deployment", "v3.0"))

            application {
                taskQueue(taskQueue) {
                    workflow(ConditionalPatchWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<ConditionalPatchResult>(
                    workflowType = "ConditionalPatchWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result(timeout = 30.seconds)

            // On first execution, patches return true (new code paths)
            assertEquals("v2", result.codePath)
            assertEquals("enabled", result.enhancement)
            assertEquals(true, result.featureX)

            handle.assertHistory { completed() }
        }

    /**
     * Workflow that reports its build ID for verification.
     */
    @Workflow("VersionReportingWorkflow")
    class VersionReportingWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String = "processed-by-worker: $input"
    }

    @Test
    fun `worker with versioning disabled still executes workflows`() =
        runTemporalTest {
            val taskQueue = "test-no-versioning-${UUID.randomUUID()}"

            // Configure with versioning explicitly disabled
            deployment(
                WorkerDeploymentVersion("disabled-versioning", "v1.0"),
                useVersioning = false,
            )

            application {
                taskQueue(taskQueue) {
                    workflow(VersionReportingWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String, String>(
                    workflowType = "VersionReportingWorkflow",
                    taskQueue = taskQueue,
                    arg = "test-input",
                )

            val result = handle.result(timeout = 30.seconds)
            assertEquals("processed-by-worker: test-input", result)

            handle.assertHistory { completed() }
        }
}
