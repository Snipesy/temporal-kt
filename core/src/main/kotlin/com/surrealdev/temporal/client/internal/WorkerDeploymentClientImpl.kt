package com.surrealdev.temporal.client.internal

import com.google.protobuf.Timestamp
import com.surrealdev.temporal.client.VersionDrainageStatus
import com.surrealdev.temporal.client.WorkerDeploymentClient
import com.surrealdev.temporal.client.WorkerDeploymentDescription
import com.surrealdev.temporal.client.WorkerDeploymentList
import com.surrealdev.temporal.client.WorkerDeploymentRoutingConfig
import com.surrealdev.temporal.client.WorkerDeploymentRoutingUpdate
import com.surrealdev.temporal.client.WorkerDeploymentSummary
import com.surrealdev.temporal.client.WorkerDeploymentTaskQueue
import com.surrealdev.temporal.client.WorkerDeploymentTaskQueueType
import com.surrealdev.temporal.client.WorkerDeploymentVersionDescription
import com.surrealdev.temporal.client.WorkerDeploymentVersionStatus
import com.surrealdev.temporal.client.WorkerDeploymentVersionSummary
import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.exceptions.ClientPermissionDeniedException
import com.surrealdev.temporal.common.exceptions.ClientWorkerDeploymentNotFoundException
import com.surrealdev.temporal.core.TemporalCoreException
import com.surrealdev.temporal.core.WorkerDeploymentVersion
import io.temporal.api.deployment.v1.RoutingConfig
import io.temporal.api.deployment.v1.WorkerDeploymentInfo
import io.temporal.api.deployment.v1.WorkerDeploymentVersionInfo
import io.temporal.api.enums.v1.TaskQueueType
import io.temporal.api.workflowservice.v1.DeleteWorkerDeploymentRequest
import io.temporal.api.workflowservice.v1.DeleteWorkerDeploymentVersionRequest
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentVersionRequest
import io.temporal.api.workflowservice.v1.ListWorkerDeploymentsRequest
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentRampingVersionRequest
import kotlin.time.Instant
import io.temporal.api.deployment.v1.WorkerDeploymentVersion as ProtoDeploymentVersion
import io.temporal.api.enums.v1.VersionDrainageStatus as ProtoDrainageStatus
import io.temporal.api.enums.v1.WorkerDeploymentVersionStatus as ProtoVersionStatus

/**
 * [WorkerDeploymentClient] over the raw service RPCs. Pure request/response mapping; the only logic
 * is turning the server's "empty version" convention into null and NOT_FOUND into a typed exception.
 */
internal class WorkerDeploymentClientImpl(
    private val serviceClient: WorkflowServiceClient,
) : WorkerDeploymentClient {
    override suspend fun describe(deploymentName: String): WorkerDeploymentDescription {
        val response =
            mapNotFound(deploymentName) {
                serviceClient.describeWorkerDeployment(
                    DescribeWorkerDeploymentRequest
                        .newBuilder()
                        .setNamespace(serviceClient.namespace)
                        .setDeploymentName(deploymentName)
                        .build(),
                )
            }
        val info = response.workerDeploymentInfo
        return WorkerDeploymentDescription(
            name = info.name,
            createTime = info.createTime.takeIf { info.hasCreateTime() }?.toInstant(),
            routingConfig = info.routingConfig.toRoutingConfig(),
            versions = info.versionSummariesList.mapNotNull { it.toSummary() },
            lastModifierIdentity = info.lastModifierIdentity,
            conflictToken = TemporalByteString(response.conflictToken),
        )
    }

    override suspend fun list(
        pageSize: Int,
        nextPageToken: TemporalByteString?,
    ): WorkerDeploymentList {
        val request =
            ListWorkerDeploymentsRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setPageSize(pageSize)
        nextPageToken?.let { request.setNextPageToken(it.inner) }
        val response = serviceClient.listWorkerDeployments(request.build())
        return WorkerDeploymentList(
            deployments =
                response.workerDeploymentsList.map { summary ->
                    WorkerDeploymentSummary(
                        name = summary.name,
                        createTime = summary.createTime.takeIf { summary.hasCreateTime() }?.toInstant(),
                        routingConfig = summary.routingConfig.toRoutingConfig(),
                        latestVersion =
                            summary.latestVersionSummary
                                .takeIf { summary.hasLatestVersionSummary() }
                                ?.toSummary(),
                        currentVersion =
                            summary.currentVersionSummary.takeIf { summary.hasCurrentVersionSummary() }?.toSummary(),
                        rampingVersion =
                            summary.rampingVersionSummary.takeIf { summary.hasRampingVersionSummary() }?.toSummary(),
                    )
                },
            nextPageToken = response.nextPageToken.takeIf { !it.isEmpty }?.let { TemporalByteString(it) },
        )
    }

    override suspend fun describeVersion(
        version: WorkerDeploymentVersion,
        reportTaskQueueStats: Boolean,
    ): WorkerDeploymentVersionDescription {
        val response =
            mapNotFound(version.deploymentName, version.buildId) {
                serviceClient.describeWorkerDeploymentVersion(
                    DescribeWorkerDeploymentVersionRequest
                        .newBuilder()
                        .setNamespace(serviceClient.namespace)
                        .setDeploymentVersion(version.toProto())
                        .setReportTaskQueueStats(reportTaskQueueStats)
                        .build(),
                )
            }
        val info = response.workerDeploymentVersionInfo
        return WorkerDeploymentVersionDescription(
            version = info.deploymentVersion.toVersion() ?: version,
            status = info.status.toStatus(),
            drainageStatus = info.drainageInfo.status.toDrainageStatus(),
            rampPercentage = info.rampPercentage,
            createTime = info.createTime.takeIf { info.hasCreateTime() }?.toInstant(),
            routingChangedTime = info.routingChangedTime.takeIf { info.hasRoutingChangedTime() }?.toInstant(),
            currentSinceTime = info.currentSinceTime.takeIf { info.hasCurrentSinceTime() }?.toInstant(),
            rampingSinceTime = info.rampingSinceTime.takeIf { info.hasRampingSinceTime() }?.toInstant(),
            firstActivationTime = info.firstActivationTime.takeIf { info.hasFirstActivationTime() }?.toInstant(),
            lastCurrentTime = info.lastCurrentTime.takeIf { info.hasLastCurrentTime() }?.toInstant(),
            lastDeactivationTime = info.lastDeactivationTime.takeIf { info.hasLastDeactivationTime() }?.toInstant(),
            taskQueues = info.taskQueueInfosList.map { it.toTaskQueue() },
            lastModifierIdentity = info.lastModifierIdentity,
        )
    }

    override suspend fun setCurrentVersion(
        deploymentName: String,
        buildId: String?,
        conflictToken: TemporalByteString?,
        ignoreMissingTaskQueues: Boolean,
        allowNoPollers: Boolean,
    ): WorkerDeploymentRoutingUpdate {
        val request =
            SetWorkerDeploymentCurrentVersionRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setDeploymentName(deploymentName)
                .setBuildId(buildId.orEmpty())
                .setIdentity(serviceClient.identity)
                .setIgnoreMissingTaskQueues(ignoreMissingTaskQueues)
                .setAllowNoPollers(allowNoPollers)
        conflictToken?.let { request.setConflictToken(it.inner) }
        val response = mapNotFound(deploymentName) { serviceClient.setWorkerDeploymentCurrentVersion(request.build()) }
        return WorkerDeploymentRoutingUpdate(
            previousVersion =
                response.previousDeploymentVersion.takeIf { response.hasPreviousDeploymentVersion() }?.toVersion(),
            previousPercentage = null,
            conflictToken = TemporalByteString(response.conflictToken),
        )
    }

    override suspend fun setRampingVersion(
        deploymentName: String,
        buildId: String?,
        percentage: Float,
        conflictToken: TemporalByteString?,
        ignoreMissingTaskQueues: Boolean,
        allowNoPollers: Boolean,
    ): WorkerDeploymentRoutingUpdate {
        require(percentage in 0f..100f) { "percentage must be between 0 and 100, was $percentage" }
        val request =
            SetWorkerDeploymentRampingVersionRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setDeploymentName(deploymentName)
                .setBuildId(buildId.orEmpty())
                .setPercentage(percentage)
                .setIdentity(serviceClient.identity)
                .setIgnoreMissingTaskQueues(ignoreMissingTaskQueues)
                .setAllowNoPollers(allowNoPollers)
        conflictToken?.let { request.setConflictToken(it.inner) }
        val response = mapNotFound(deploymentName) { serviceClient.setWorkerDeploymentRampingVersion(request.build()) }
        return WorkerDeploymentRoutingUpdate(
            previousVersion =
                response.previousDeploymentVersion.takeIf { response.hasPreviousDeploymentVersion() }?.toVersion(),
            previousPercentage = response.previousPercentage,
            conflictToken = TemporalByteString(response.conflictToken),
        )
    }

    override suspend fun delete(deploymentName: String) {
        mapNotFound(deploymentName) {
            serviceClient.deleteWorkerDeployment(
                DeleteWorkerDeploymentRequest
                    .newBuilder()
                    .setNamespace(serviceClient.namespace)
                    .setDeploymentName(deploymentName)
                    .setIdentity(serviceClient.identity)
                    .build(),
            )
        }
    }

    override suspend fun deleteVersion(
        version: WorkerDeploymentVersion,
        skipDrainage: Boolean,
    ) {
        mapNotFound(version.deploymentName, version.buildId) {
            serviceClient.deleteWorkerDeploymentVersion(
                DeleteWorkerDeploymentVersionRequest
                    .newBuilder()
                    .setNamespace(serviceClient.namespace)
                    .setDeploymentVersion(version.toProto())
                    .setSkipDrainage(skipDrainage)
                    .setIdentity(serviceClient.identity)
                    .build(),
            )
        }
    }

    private inline fun <T> mapNotFound(
        deploymentName: String,
        buildId: String? = null,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (e: TemporalCoreException) {
            when (e.statusCode) {
                GRPC_NOT_FOUND -> throw ClientWorkerDeploymentNotFoundException(deploymentName, buildId, cause = e)
                GRPC_PERMISSION_DENIED -> throw ClientPermissionDeniedException(cause = e)
                else -> throw e
            }
        }
}

// ----- proto <-> Kotlin -----

private fun WorkerDeploymentVersion.toProto(): ProtoDeploymentVersion =
    ProtoDeploymentVersion
        .newBuilder()
        .setDeploymentName(deploymentName)
        .setBuildId(buildId)
        .build()

/** The server uses an empty version for "the unversioned workers"; that becomes null. */
private fun ProtoDeploymentVersion.toVersion(): WorkerDeploymentVersion? =
    if (deploymentName.isBlank() || buildId.isBlank()) null else WorkerDeploymentVersion(deploymentName, buildId)

/**
 * Older servers fill only the deprecated `version` string, `<deployment>.<build>`. Deployment names
 * cannot contain a dot, so the first one is the separator; build IDs may contain dots.
 */
private fun parseLegacyVersion(legacy: String): WorkerDeploymentVersion? {
    val dot = legacy.indexOf('.')
    if (dot <= 0 || dot == legacy.lastIndex) return null
    return WorkerDeploymentVersion(legacy.substring(0, dot), legacy.substring(dot + 1))
}

private fun RoutingConfig.toRoutingConfig(): WorkerDeploymentRoutingConfig =
    WorkerDeploymentRoutingConfig(
        currentVersion = currentDeploymentVersion.takeIf { hasCurrentDeploymentVersion() }?.toVersion(),
        rampingVersion = rampingDeploymentVersion.takeIf { hasRampingDeploymentVersion() }?.toVersion(),
        rampingVersionPercentage = rampingVersionPercentage,
        currentVersionChangedTime = currentVersionChangedTime.takeIf { hasCurrentVersionChangedTime() }?.toInstant(),
        rampingVersionChangedTime = rampingVersionChangedTime.takeIf { hasRampingVersionChangedTime() }?.toInstant(),
        rampingVersionPercentageChangedTime =
            rampingVersionPercentageChangedTime.takeIf { hasRampingVersionPercentageChangedTime() }?.toInstant(),
    )

/**
 * Null when the server sent neither a structured version nor a parseable legacy one; such a summary
 * is dropped from the list rather than failing the whole describe.
 */
private fun WorkerDeploymentInfo.WorkerDeploymentVersionSummary.toSummary(): WorkerDeploymentVersionSummary? =
    WorkerDeploymentVersionSummary(
        version = (deploymentVersion.toVersion() ?: parseLegacyVersion(version)) ?: return null,
        status = status.toStatus(),
        drainageStatus = drainageStatus.toDrainageStatus(),
        createTime = createTime.takeIf { hasCreateTime() }?.toInstant(),
        currentSinceTime = currentSinceTime.takeIf { hasCurrentSinceTime() }?.toInstant(),
        rampingSinceTime = rampingSinceTime.takeIf { hasRampingSinceTime() }?.toInstant(),
        routingUpdateTime = routingUpdateTime.takeIf { hasRoutingUpdateTime() }?.toInstant(),
        firstActivationTime = firstActivationTime.takeIf { hasFirstActivationTime() }?.toInstant(),
        lastCurrentTime = lastCurrentTime.takeIf { hasLastCurrentTime() }?.toInstant(),
        lastDeactivationTime = lastDeactivationTime.takeIf { hasLastDeactivationTime() }?.toInstant(),
    )

private fun WorkerDeploymentVersionInfo.VersionTaskQueueInfo.toTaskQueue(): WorkerDeploymentTaskQueue =
    WorkerDeploymentTaskQueue(
        name = name,
        type =
            when (type) {
                TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW -> WorkerDeploymentTaskQueueType.WORKFLOW
                TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY -> WorkerDeploymentTaskQueueType.ACTIVITY
                TaskQueueType.TASK_QUEUE_TYPE_NEXUS -> WorkerDeploymentTaskQueueType.NEXUS
                else -> WorkerDeploymentTaskQueueType.UNSPECIFIED
            },
    )

private fun ProtoVersionStatus.toStatus(): WorkerDeploymentVersionStatus =
    when (this) {
        ProtoVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE -> WorkerDeploymentVersionStatus.INACTIVE
        ProtoVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_CURRENT -> WorkerDeploymentVersionStatus.CURRENT
        ProtoVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_RAMPING -> WorkerDeploymentVersionStatus.RAMPING
        ProtoVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_DRAINING -> WorkerDeploymentVersionStatus.DRAINING
        ProtoVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_DRAINED -> WorkerDeploymentVersionStatus.DRAINED
        ProtoVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_CREATED -> WorkerDeploymentVersionStatus.CREATED
        else -> WorkerDeploymentVersionStatus.UNSPECIFIED
    }

private fun ProtoDrainageStatus.toDrainageStatus(): VersionDrainageStatus =
    when (this) {
        ProtoDrainageStatus.VERSION_DRAINAGE_STATUS_DRAINING -> VersionDrainageStatus.DRAINING
        ProtoDrainageStatus.VERSION_DRAINAGE_STATUS_DRAINED -> VersionDrainageStatus.DRAINED
        else -> VersionDrainageStatus.UNSPECIFIED
    }

private fun Timestamp.toInstant(): Instant = Instant.fromEpochSeconds(seconds, nanos)
