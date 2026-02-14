package com.surrealdev.temporal.common

import kotlin.time.Duration

/**
 * Policy for retrying failed operations (workflows and activities).
 *
 * This policy is used by the Temporal server to automatically retry failed
 * workflows and activities. All official Temporal SDKs use a single retry
 * policy type for both workflows and activities, as they map to the same
 * underlying protobuf message.
 *
 * @property initialInterval Initial backoff duration between retry attempts.
 * @property backoffCoefficient Multiplier for exponential backoff (must be > 1.0).
 * @property maximumInterval Maximum backoff duration (caps exponential growth).
 * @property maximumAttempts Maximum number of retry attempts (0 = unlimited).
 * @property nonRetryableErrorTypes Error types that should not trigger retries.
 */
data class RetryPolicy(
    val initialInterval: Duration = Duration.parse("1s"),
    val backoffCoefficient: Double = 2.0,
    val maximumInterval: Duration? = null,
    val maximumAttempts: Int = 0,
    val nonRetryableErrorTypes: List<String> = emptyList(),
)
