package com.surrealdev.temporal.core

/**
 * Identifies a worker deployment version.
 *
 * The combination of deploymentName and buildId uniquely identifies
 * a version within the namespace. This is used for Worker Versioning
 * which enables Blue/Green and Rainbow deployment strategies.
 *
 * @property deploymentName Name of the deployment (e.g., "llm_srv", "payment-service")
 * @property buildId Build ID within the deployment (e.g., "1.0", "v2.3.5")
 */
data class WorkerDeploymentVersion(
    val deploymentName: String,
    val buildId: String,
) {
    init {
        require(deploymentName.isNotBlank()) { "deploymentName cannot be blank" }
        require(buildId.isNotBlank()) { "buildId cannot be blank" }
    }
}

/**
 * Default versioning behavior for workflows that don't specify their own.
 *
 * When worker versioning is enabled and a workflow doesn't specify its own
 * versioning behavior, this determines how the workflow's deployment version
 * is managed.
 */
enum class VersioningBehavior(
    val value: Int,
) {
    /**
     * Workflow doesn't have versioning behavior specified.
     *
     * NOTE: When deployment-based Worker Versioning is enabled and this is used,
     * workflows that do not set their own Versioning Behavior will fail at registration time.
     */
    UNSPECIFIED(0),

    /**
     * Workflow stays on its initial deployment version.
     *
     * Once a workflow starts on a particular deployment version, it will continue
     * executing on that version even if newer versions become available.
     */
    PINNED(1),

    /**
     * Workflow automatically upgrades to the current deployment version.
     *
     * The workflow will be migrated to execute on the current deployment version
     * when it becomes available.
     */
    AUTO_UPGRADE(2),
}

/**
 * Options for worker deployment versioning.
 *
 * @property version The deployment version identifying this worker
 * @property useWorkerVersioning If true, worker participates in versioned task routing.
 *                               If false, worker is unversioned (tasks not distinguished by version).
 * @property defaultVersioningBehavior Default versioning behavior for workflows that don't
 *                                     specify their own. When [useWorkerVersioning] is true
 *                                     and this is [VersioningBehavior.UNSPECIFIED], workflows
 *                                     MUST specify their own versioning behavior or they will
 *                                     fail at registration.
 */
data class WorkerDeploymentOptions(
    val version: WorkerDeploymentVersion,
    val useWorkerVersioning: Boolean = true,
    val defaultVersioningBehavior: VersioningBehavior = VersioningBehavior.UNSPECIFIED,
)
