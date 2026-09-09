package com.surrealdev.temporal.client

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.core.WorkerDeploymentVersion
import kotlin.time.Instant

/**
 * Typed access to the worker deployment management RPCs (`DescribeWorkerDeployment`,
 * `SetWorkerDeploymentCurrentVersion`, ...), reached through [TemporalClient.workerDeployments].
 *
 * A worker deployment groups the versions of one service; the server routes new workflows to the
 * deployment's *current* version and a percentage of them to its *ramping* version. Workers join a
 * deployment by declaring it in the application's `deployment { }` block, and the server creates the
 * deployment the first time one of them polls.
 *
 * ```kotlin
 * val deployments = client.workerDeployments
 * val before = deployments.describe("order-service")
 * deployments.setCurrentVersion("order-service", buildId = "v1.2.3", conflictToken = before.conflictToken)
 * ```
 *
 * Passing a `null` build ID to [setCurrentVersion] or [setRampingVersion] sends an empty build ID, which
 * the API defines as "the deployment's unversioned workers". Servers differ in whether they accept it
 * (the dev server bundled with 0.2.0 rejects it for ramping), so treat it as server-dependent.
 */
interface WorkerDeploymentClient {
    /**
     * Describes a worker deployment: its routing configuration and the versions the server knows.
     *
     * @throws com.surrealdev.temporal.common.exceptions.ClientWorkerDeploymentNotFoundException if no
     *   worker of that deployment has polled yet.
     */
    suspend fun describe(deploymentName: String): WorkerDeploymentDescription

    /**
     * Lists worker deployments in the client's namespace, one page at a time.
     *
     * @param nextPageToken Token from a previous [WorkerDeploymentList.nextPageToken].
     */
    suspend fun list(
        pageSize: Int = 100,
        nextPageToken: TemporalByteString? = null,
    ): WorkerDeploymentList

    /**
     * Describes one version of a deployment.
     *
     * @param reportTaskQueueStats Also fetch per-task-queue statistics; slower.
     */
    suspend fun describeVersion(
        version: WorkerDeploymentVersion,
        reportTaskQueueStats: Boolean = false,
    ): WorkerDeploymentVersionDescription

    /**
     * Makes [buildId] the deployment's current version, so new workflows start on it.
     *
     * @param buildId The build to route to, or null for the deployment's unversioned workers.
     * @param conflictToken Token from a previous describe or update; the call fails if the deployment
     *   changed since that token was issued.
     * @param ignoreMissingTaskQueues Proceed even if the new version is not polling every task queue
     *   the current version polled. Only set this when those pollers are known never to arrive.
     * @param allowNoPollers Proceed even if the server has not yet seen a poller for the new version.
     */
    suspend fun setCurrentVersion(
        deploymentName: String,
        buildId: String?,
        conflictToken: TemporalByteString? = null,
        ignoreMissingTaskQueues: Boolean = false,
        allowNoPollers: Boolean = false,
    ): WorkerDeploymentRoutingUpdate

    /**
     * Ramps [percentage] of new workflows to [buildId].
     *
     * @param buildId The build to ramp to, or null for the deployment's unversioned workers.
     * @param percentage Share of new workflows to route there, 0 to 100.
     * @see setCurrentVersion for the remaining parameters.
     */
    suspend fun setRampingVersion(
        deploymentName: String,
        buildId: String?,
        percentage: Float,
        conflictToken: TemporalByteString? = null,
        ignoreMissingTaskQueues: Boolean = false,
        allowNoPollers: Boolean = false,
    ): WorkerDeploymentRoutingUpdate

    /** Deletes a deployment. The server refuses while it still has versions or pollers. */
    suspend fun delete(deploymentName: String)

    /**
     * Deletes one version of a deployment.
     *
     * @param skipDrainage Delete even if the version may still have open pinned workflows.
     */
    suspend fun deleteVersion(
        version: WorkerDeploymentVersion,
        skipDrainage: Boolean = false,
    )
}

/** [WorkerDeploymentClient.setCurrentVersion] for a [WorkerDeploymentVersion]. */
suspend fun WorkerDeploymentClient.setCurrentVersion(
    version: WorkerDeploymentVersion,
    conflictToken: TemporalByteString? = null,
    ignoreMissingTaskQueues: Boolean = false,
    allowNoPollers: Boolean = false,
): WorkerDeploymentRoutingUpdate =
    setCurrentVersion(version.deploymentName, version.buildId, conflictToken, ignoreMissingTaskQueues, allowNoPollers)

/** [WorkerDeploymentClient.setRampingVersion] for a [WorkerDeploymentVersion]. */
suspend fun WorkerDeploymentClient.setRampingVersion(
    version: WorkerDeploymentVersion,
    percentage: Float,
    conflictToken: TemporalByteString? = null,
    ignoreMissingTaskQueues: Boolean = false,
    allowNoPollers: Boolean = false,
): WorkerDeploymentRoutingUpdate =
    setRampingVersion(
        version.deploymentName,
        version.buildId,
        percentage,
        conflictToken,
        ignoreMissingTaskQueues,
        allowNoPollers,
    )

/**
 * A worker deployment as the server sees it.
 *
 * @property conflictToken Pass to a later update to fail if the deployment changed in between.
 */
data class WorkerDeploymentDescription(
    val name: String,
    val createTime: Instant?,
    val routingConfig: WorkerDeploymentRoutingConfig,
    val versions: List<WorkerDeploymentVersionSummary>,
    val lastModifierIdentity: String,
    val conflictToken: TemporalByteString,
)

/**
 * Where a deployment sends new workflows. A null version means its unversioned workers.
 *
 * @property rampingVersionPercentage Share of new workflows routed to [rampingVersion], 0 to 100.
 */
data class WorkerDeploymentRoutingConfig(
    val currentVersion: WorkerDeploymentVersion?,
    val rampingVersion: WorkerDeploymentVersion?,
    val rampingVersionPercentage: Float,
    val currentVersionChangedTime: Instant?,
    val rampingVersionChangedTime: Instant?,
    val rampingVersionPercentageChangedTime: Instant?,
)

/** One version as listed in its deployment's description. */
data class WorkerDeploymentVersionSummary(
    val version: WorkerDeploymentVersion,
    val status: WorkerDeploymentVersionStatus,
    val drainageStatus: VersionDrainageStatus,
    val createTime: Instant?,
    val currentSinceTime: Instant?,
    val rampingSinceTime: Instant?,
    val routingUpdateTime: Instant?,
    val firstActivationTime: Instant?,
    val lastCurrentTime: Instant?,
    val lastDeactivationTime: Instant?,
)

/** Full description of one deployment version. */
data class WorkerDeploymentVersionDescription(
    val version: WorkerDeploymentVersion,
    val status: WorkerDeploymentVersionStatus,
    val drainageStatus: VersionDrainageStatus,
    /** Share of new workflows ramped here, 0 to 100; 0 unless [status] is RAMPING. */
    val rampPercentage: Float,
    val createTime: Instant?,
    val routingChangedTime: Instant?,
    val currentSinceTime: Instant?,
    val rampingSinceTime: Instant?,
    val firstActivationTime: Instant?,
    val lastCurrentTime: Instant?,
    val lastDeactivationTime: Instant?,
    val taskQueues: List<WorkerDeploymentTaskQueue>,
    val lastModifierIdentity: String,
)

/** A task queue polled by a deployment version. */
data class WorkerDeploymentTaskQueue(
    val name: String,
    val type: WorkerDeploymentTaskQueueType,
)

enum class WorkerDeploymentTaskQueueType {
    UNSPECIFIED,
    WORKFLOW,
    ACTIVITY,
    NEXUS,
}

/** Routing role of a version within its deployment. */
enum class WorkerDeploymentVersionStatus {
    UNSPECIFIED,

    /** Neither current nor ramping, and nothing left to drain. */
    INACTIVE,
    CURRENT,
    RAMPING,

    /** No longer current or ramping, but still has open pinned workflows. */
    DRAINING,

    /** Was draining and the last pinned workflow has closed. */
    DRAINED,
}

/** Whether a version that left the current or ramping role still has pinned workflows. */
enum class VersionDrainageStatus {
    UNSPECIFIED,
    DRAINING,
    DRAINED,
}

/** One page of [WorkerDeploymentClient.list]. */
data class WorkerDeploymentList(
    val deployments: List<WorkerDeploymentSummary>,
    /** Null when this was the last page. */
    val nextPageToken: TemporalByteString?,
)

/** A deployment as listed; [WorkerDeploymentClient.describe] gives the full version list. */
data class WorkerDeploymentSummary(
    val name: String,
    val createTime: Instant?,
    val routingConfig: WorkerDeploymentRoutingConfig,
    val latestVersion: WorkerDeploymentVersionSummary?,
    val currentVersion: WorkerDeploymentVersionSummary?,
    val rampingVersion: WorkerDeploymentVersionSummary?,
)

/**
 * Result of [WorkerDeploymentClient.setCurrentVersion] or [WorkerDeploymentClient.setRampingVersion].
 *
 * @property previousVersion The version that held the role before, null if it was the unversioned workers.
 * @property previousPercentage The ramp percentage before a ramping update; null for a current-version update.
 * @property conflictToken Token for the deployment state after this update.
 */
data class WorkerDeploymentRoutingUpdate(
    val previousVersion: WorkerDeploymentVersion?,
    val previousPercentage: Float?,
    val conflictToken: TemporalByteString,
)
