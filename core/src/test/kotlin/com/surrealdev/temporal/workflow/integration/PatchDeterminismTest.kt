package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.core.TemporalCoreException
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.core.VersioningBehavior
import com.surrealdev.temporal.core.WorkerDeploymentVersion
import com.surrealdev.temporal.testing.TemporalTestApplicationBuilder
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentResponse
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
 * - Workers register deployment versions and report the configured behavior to the server
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

            application {
                taskQueue(taskQueue) {
                    workflow<SimplePatchWorkflow>()
                    workflow<MemoizedPatchWorkflow>()
                }
            }

            val client = client()

            // Test 1: patched() returns true on first execution
            val handle1 =
                client.startWorkflow(
                    workflowType = "SimplePatchWorkflow",
                    taskQueue = taskQueue,
                )
            val result1: String = handle1.result(timeout = 30.seconds)
            assertEquals("new-format-result", result1)
            handle1.assertHistory { completed() }

            // Test 2: patched() is memoized (same ID returns same value)
            val handle2 =
                client.startWorkflow(
                    workflowType = "MemoizedPatchWorkflow",
                    taskQueue = taskQueue,
                )
            val memoResult: List<Boolean> = handle2.result(timeout = 30.seconds)
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
    fun `multiple patches work independently`() =
        runTemporalTest {
            val taskQueue = "test-multi-patch-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<MultiplePatchWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "MultiplePatchWorkflow",
                    taskQueue = taskQueue,
                )

            val result: List<Boolean> = handle.result(timeout = 30.seconds)

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
    fun `workflow uses correct patched code path`() =
        runTemporalTest {
            val taskQueue = "test-conditional-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ConditionalPatchWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ConditionalPatchWorkflow",
                    taskQueue = taskQueue,
                )

            val result: ConditionalPatchResult = handle.result(timeout = 30.seconds)

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
    fun `pinned worker receives workflows through its current deployment version`() =
        runTemporalTest(timeSkipping = false) {
            verifyVersioning(VersioningBehavior.PINNED)
        }

    @Test
    fun `auto upgrade worker reports its versioning behavior to the server`() =
        runTemporalTest(timeSkipping = false) {
            verifyVersioning(VersioningBehavior.AUTO_UPGRADE)
        }

    private suspend fun TemporalTestApplicationBuilder.verifyVersioning(behavior: VersioningBehavior) {
        val taskQueue = "versioning-${UUID.randomUUID()}"
        val version = WorkerDeploymentVersion("deployment-${UUID.randomUUID()}", "v1.0")
        deployment(version, defaultVersioningBehavior = behavior)
        application {
            taskQueue(taskQueue) {
                workflow<VersionReportingWorkflow>()
            }
        }

        TemporalRuntime.create().use { runtime ->
            TemporalCoreClient.connect(runtime, targetUrl).use { coreClient ->
                // Registration is asynchronous: wait for the worker's first poll to reach the server.
                withTimeout(30.seconds) {
                    while (true) {
                        try {
                            coreClient.workflowServiceCall(
                                "DescribeWorkerDeployment",
                                DescribeWorkerDeploymentRequest
                                    .newBuilder()
                                    .setNamespace("default")
                                    .setDeploymentName(version.deploymentName)
                                    .build(),
                            ) { DescribeWorkerDeploymentResponse.parseFrom(it) }
                            break
                        } catch (e: TemporalCoreException) {
                            if (e.statusCode != 5) throw e // NOT_FOUND while the first poll is registering.
                            delay(100)
                        }
                    }
                }
                coreClient.workflowServiceCall(
                    "SetWorkerDeploymentCurrentVersion",
                    SetWorkerDeploymentCurrentVersionRequest
                        .newBuilder()
                        .setNamespace("default")
                        .setDeploymentName(version.deploymentName)
                        .setBuildId(version.buildId)
                        .build(),
                ) { SetWorkerDeploymentCurrentVersionResponse.parseFrom(it) }

                val handle =
                    client().startWorkflow<String>(
                        workflowType = "VersionReportingWorkflow",
                        taskQueue = taskQueue,
                        arg = "versioned-input",
                    )
                assertEquals("processed-by-worker: versioned-input", handle.result<String>(timeout = 30.seconds))
                val description =
                    coreClient.workflowServiceCall(
                        "DescribeWorkflowExecution",
                        DescribeWorkflowExecutionRequest
                            .newBuilder()
                            .setNamespace("default")
                            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(handle.workflowId))
                            .build(),
                    ) { DescribeWorkflowExecutionResponse.parseFrom(it) }
                val versioning = description.workflowExecutionInfo.versioningInfo
                assertEquals(behavior.value, versioning.behaviorValue)
                assertEquals(version.deploymentName, versioning.deploymentVersion.deploymentName)
                assertEquals(version.buildId, versioning.deploymentVersion.buildId)
            }
        }
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
                    workflow<VersionReportingWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "VersionReportingWorkflow",
                    taskQueue = taskQueue,
                    arg = "test-input",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("processed-by-worker: test-input", result)

            handle.assertHistory { completed() }
        }
}
