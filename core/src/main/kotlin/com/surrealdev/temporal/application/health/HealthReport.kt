package com.surrealdev.temporal.application.health

import com.surrealdev.temporal.application.worker.WorkerStatus

/**
 * Overall application status for health checks.
 */
enum class ApplicationStatus {
    /** Application has not been started yet. */
    NOT_STARTED,

    /** At least one worker is still starting up. */
    STARTING,

    /** All workers are [WorkerStatus.READY]. */
    HEALTHY,

    /** Application is shutting down. */
    SHUTTING_DOWN,

    /** At least one worker has [WorkerStatus.FAILED]. */
    DEGRADED,
}

/**
 * Health report for a single worker (task queue).
 */
data class WorkerHealthReport(
    val taskQueue: String,
    val namespace: String,
    val status: WorkerStatus,
    val workflowZombieCount: Int,
    val activityZombieCount: Int,
)

/**
 * Health report for the entire application, aggregating all worker statuses.
 */
data class ApplicationHealthReport(
    /** Overall application status derived from the worst worker status. */
    val status: ApplicationStatus,
    /** Per-worker health reports. */
    val workers: List<WorkerHealthReport>,
)
