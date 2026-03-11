package com.surrealdev.temporal.application.worker

/**
 * Lifecycle status of a managed worker.
 *
 * States follow a strict forward progression:
 * ```
 * CREATED -> STARTING -> READY -> STOPPING -> STOPPED
 *                \-> FAILED (from STARTING or READY)
 * ```
 */
enum class WorkerStatus {
    /** Worker is constructed but not yet started. */
    CREATED,

    /** Worker polling loops have been launched but first poll has not completed. */
    STARTING,

    /** Both workflow and activity polling have reached the Core SDK. Worker is serving. */
    READY,

    /** Shutdown has been signaled. Worker is draining in-flight work. */
    STOPPING,

    /** Worker has fully stopped. Terminal state. */
    STOPPED,

    /** A fatal error occurred. Terminal state. */
    FAILED,

    ;

    /** Returns true if the worker is in a terminal state ([STOPPED] or [FAILED]). */
    val isTerminal: Boolean get() = this == STOPPED || this == FAILED

    /** Returns true if the worker is actively serving ([READY]). */
    val isServing: Boolean get() = this == READY
}
