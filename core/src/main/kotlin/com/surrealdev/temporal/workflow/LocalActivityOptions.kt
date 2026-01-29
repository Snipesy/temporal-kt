package com.surrealdev.temporal.workflow

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Options for local activity execution within a workflow.
 *
 * Local activities run in the same worker process as the workflow, avoiding the
 * roundtrip to the Temporal server. They're useful for short operations that
 * don't need server-side scheduling, retry management, or persistence.
 *
 * **Key differences from regular activities:**
 * - No heartbeats (operations should be short)
 * - Retries managed by Core SDK up to [localRetryThreshold] (default 1 minute)
 * - Uses markers for replay (not re-execution)
 * - `DoBackoff` resolution when backoff exceeds threshold (lang schedules timer, then retries)
 *
 * At least one of [startToCloseTimeout] or [scheduleToCloseTimeout] must be set.
 *
 * @property startToCloseTimeout Maximum time for a single local activity execution attempt.
 * @property scheduleToCloseTimeout Maximum time from activity scheduling to completion (including retries).
 * @property scheduleToStartTimeout Maximum time the local activity can idle internally before being executed.
 *                                  This can happen if the worker is at max concurrent local activity executions.
 * @property activityId Custom activity ID. If null, generated deterministically as the seq number.
 * @property retryPolicy Retry policy for the local activity. By default local activities are retried indefinitely.
 * @property localRetryThreshold If the activity is retrying and backoff would exceed this value, lang will be
 *                               told to schedule a timer and retry after. Otherwise, backoff happens internally
 *                               in Core SDK. Defaults to 1 minute.
 * @property cancellationType How to handle cancellation of this local activity. Per proto comment, lang should
 *                           default to WAIT_CANCELLATION_COMPLETED for local activities.
 */
data class LocalActivityOptions(
    val startToCloseTimeout: Duration? = null,
    val scheduleToCloseTimeout: Duration? = null,
    val scheduleToStartTimeout: Duration? = null,
    val activityId: String? = null,
    val retryPolicy: RetryPolicy? = null,
    val localRetryThreshold: Duration = 1.minutes,
    val cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
) {
    init {
        if (startToCloseTimeout == null && scheduleToCloseTimeout == null) {
            throw IllegalArgumentException(
                "At least one of startToCloseTimeout or scheduleToCloseTimeout must be set",
            )
        }

        startToCloseTimeout?.let { timeout ->
            require(timeout.isPositive()) {
                "startToCloseTimeout must be positive, got: $timeout"
            }
        }

        scheduleToCloseTimeout?.let { timeout ->
            require(timeout.isPositive()) {
                "scheduleToCloseTimeout must be positive, got: $timeout"
            }
        }

        scheduleToStartTimeout?.let { timeout ->
            require(timeout.isPositive()) {
                "scheduleToStartTimeout must be positive, got: $timeout"
            }
        }

        require(localRetryThreshold.isPositive()) {
            "localRetryThreshold must be positive, got: $localRetryThreshold"
        }

        // Timeout relationships
        if (startToCloseTimeout != null && scheduleToCloseTimeout != null) {
            require(scheduleToCloseTimeout >= startToCloseTimeout) {
                "scheduleToCloseTimeout ($scheduleToCloseTimeout) must be >= " +
                    "startToCloseTimeout ($startToCloseTimeout)"
            }
        }

        if (scheduleToStartTimeout != null && scheduleToCloseTimeout != null) {
            require(scheduleToStartTimeout <= scheduleToCloseTimeout) {
                "scheduleToStartTimeout ($scheduleToStartTimeout) must be <= " +
                    "scheduleToCloseTimeout ($scheduleToCloseTimeout)"
            }
        }

        // RetryPolicy validation
        retryPolicy?.let { policy ->
            require(policy.backoffCoefficient > 1.0) {
                "RetryPolicy backoffCoefficient must be > 1.0, got: ${policy.backoffCoefficient}"
            }

            require(policy.maximumAttempts >= 0) {
                "RetryPolicy maximumAttempts must be >= 0, got: ${policy.maximumAttempts}"
            }

            if (policy.maximumInterval != null) {
                require(policy.maximumInterval >= policy.initialInterval) {
                    "RetryPolicy maximumInterval (${policy.maximumInterval}) must be >= " +
                        "initialInterval (${policy.initialInterval})"
                }
            }
        }
    }
}
