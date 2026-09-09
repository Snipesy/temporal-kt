package com.surrealdev.temporal.client

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.common.exceptions.ClientWorkerDeploymentNotFoundException
import com.surrealdev.temporal.core.VersioningBehavior
import com.surrealdev.temporal.core.WorkerDeploymentVersion
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the worker deployment RPCs through [TemporalClient.workerDeployments] against the dev
 * server, the way a deploy controller would: wait for the deployment to appear, promote the version,
 * read routing back, ramp, and hit the typed not-found path.
 */
@Tag("integration")
class WorkerDeploymentClientTest {
    @Workflow("DeploymentEchoWorkflow")
    class EchoWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String = input
    }

    @Test
    fun `describe, ramp, promote and list a deployment`() =
        runTemporalTest(timeSkipping = false) {
            val version = WorkerDeploymentVersion("deployment-${UUID.randomUUID()}", "v1")
            val taskQueue = "deployment-client-${UUID.randomUUID()}"
            deployment(version, defaultVersioningBehavior = VersioningBehavior.PINNED)
            application {
                taskQueue(taskQueue) {
                    workflow<EchoWorkflow>()
                }
            }
            val deployments = client().workerDeployments

            // The server creates the deployment when the worker's first poll lands.
            val fresh = awaitDeployment(deployments, version.deploymentName)
            assertEquals(version.deploymentName, fresh.name)
            assertNull(fresh.routingConfig.currentVersion, "nothing is current before promotion")
            assertEquals(listOf(version), fresh.versions.map { it.version })
            assertEquals(WorkerDeploymentVersionStatus.INACTIVE, fresh.versions.single().status)

            // Ramp a quarter of new workflows to the version while nothing is current yet.
            val ramped = deployments.setRampingVersion(version, percentage = 25f, conflictToken = fresh.conflictToken)
            assertNull(ramped.previousVersion, "no ramping version before")
            assertEquals(0f, ramped.previousPercentage)
            val ramping = deployments.describe(version.deploymentName)
            assertEquals(version, ramping.routingConfig.rampingVersion)
            assertEquals(25f, ramping.routingConfig.rampingVersionPercentage)
            assertEquals(WorkerDeploymentVersionStatus.RAMPING, ramping.versions.single().status)
            assertEquals(25f, deployments.describeVersion(version).rampPercentage)

            // Promote it. The server clears the ramp when the ramping version becomes current.
            val promoted = deployments.setCurrentVersion(version, conflictToken = ramping.conflictToken)
            assertNull(promoted.previousVersion, "the previous current was the unversioned workers")
            assertNull(promoted.previousPercentage)

            val current = deployments.describe(version.deploymentName)
            assertEquals(version, current.routingConfig.currentVersion)
            assertNull(current.routingConfig.rampingVersion)
            assertEquals(WorkerDeploymentVersionStatus.CURRENT, current.versions.single().status)
            assertNotNull(current.routingConfig.currentVersionChangedTime)

            val described = deployments.describeVersion(version)
            assertEquals(version, described.version)
            assertEquals(WorkerDeploymentVersionStatus.CURRENT, described.status)
            assertTrue(
                described.taskQueues.any { it.name == taskQueue && it.type == WorkerDeploymentTaskQueueType.WORKFLOW },
                "expected $taskQueue among ${described.taskQueues}",
            )

            // Listing pages through every deployment on the shared dev server, so walk until found.
            var token: com.surrealdev.temporal.common.TemporalByteString? = null
            var found: WorkerDeploymentSummary? = null
            do {
                val page = deployments.list(pageSize = 50, nextPageToken = token)
                found = page.deployments.firstOrNull { it.name == version.deploymentName }
                token = page.nextPageToken
            } while (found == null && token != null)
            assertEquals(version, assertNotNull(found, "deployment missing from list").currentVersion?.version)

            // The promoted worker still does its job.
            val handle =
                client().startWorkflow<String>(
                    workflowType = "DeploymentEchoWorkflow",
                    taskQueue = taskQueue,
                    arg = "hello",
                )
            assertEquals("hello", handle.result<String>(timeout = 30.seconds))
        }

    @Test
    fun `unknown deployment is a typed not-found`() =
        runTemporalTest(timeSkipping = false) {
            application { }
            val name = "missing-${UUID.randomUUID()}"
            val e =
                assertFailsWith<ClientWorkerDeploymentNotFoundException> { client().workerDeployments.describe(name) }
            assertEquals(name, e.deploymentName)
            assertFailsWith<ClientWorkerDeploymentNotFoundException> {
                client().workerDeployments.describeVersion(WorkerDeploymentVersion(name, "v1"))
            }
        }

    private suspend fun awaitDeployment(
        deployments: WorkerDeploymentClient,
        name: String,
    ): WorkerDeploymentDescription =
        withTimeout(30.seconds) {
            while (true) {
                try {
                    return@withTimeout deployments.describe(name)
                } catch (_: ClientWorkerDeploymentNotFoundException) {
                    delay(100)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
}
