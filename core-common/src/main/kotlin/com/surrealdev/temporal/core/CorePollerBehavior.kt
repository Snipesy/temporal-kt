package com.surrealdev.temporal.core

/**
 * Controls how many concurrent gRPC long-polls a worker issues to the Temporal server.
 *
 * This maps directly to the Core SDK's `PollerBehavior`. Only one variant may be active.
 */
sealed class CorePollerBehavior {
    /**
     * Uses a fixed maximum number of concurrent pollers.
     *
     * @param maximum Maximum number of concurrent gRPC long-poll requests
     */
    data class SimpleMaximum(
        val maximum: Int,
    ) : CorePollerBehavior()

    /**
     * Dynamically scales poller count based on server feedback.
     *
     * Core adjusts the number of concurrent polls up or down in response to
     * `PollerScalingDecision` hints from the server.
     *
     * @param minimum Minimum number of concurrent pollers (floor)
     * @param maximum Maximum number of concurrent pollers (ceiling)
     * @param initial Starting number of concurrent pollers
     */
    data class Autoscaling(
        val minimum: Int,
        val maximum: Int,
        val initial: Int,
    ) : CorePollerBehavior()
}
