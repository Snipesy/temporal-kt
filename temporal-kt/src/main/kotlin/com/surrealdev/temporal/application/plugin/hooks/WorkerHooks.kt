package com.surrealdev.temporal.application.plugin.hooks

import com.surrealdev.temporal.application.plugin.Hook

/**
 * Hook called after a worker successfully starts.
 *
 * This hook is fired in [com.surrealdev.temporal.application.TemporalApplication.start]
 * after each worker starts.
 *
 * Use this hook to:
 * - Track worker lifecycle
 * - Initialize worker-specific resources
 * - Register metrics or monitoring
 */
object WorkerStarted : Hook<suspend (WorkerStartedContext) -> Unit> {
    override val name = "WorkerStarted"
}

/**
 * Context provided to [WorkerStarted] hook handlers.
 *
 * @property taskQueue The task queue name
 * @property namespace The namespace the worker is connected to
 */
data class WorkerStartedContext(
    val taskQueue: String,
    val namespace: String,
)

/**
 * Hook called after a worker stops.
 *
 * This hook is fired in [com.surrealdev.temporal.application.TemporalApplication.close]
 * after each worker's [ManagedWorker.stop] completes.
 *
 * Use this hook to:
 * - Track worker lifecycle
 * - Clean up worker-specific resources
 * - Record metrics or logs
 */
object WorkerStopped : Hook<suspend (WorkerStoppedContext) -> Unit> {
    override val name = "WorkerStopped"
}

/**
 * Context provided to [WorkerStopped] hook handlers.
 *
 * @property taskQueue The task queue name
 * @property namespace The namespace the worker was connected to
 */
data class WorkerStoppedContext(
    val taskQueue: String,
    val namespace: String,
)
