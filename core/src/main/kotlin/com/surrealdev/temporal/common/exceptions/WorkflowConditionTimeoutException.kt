package com.surrealdev.temporal.common.exceptions

import kotlin.time.Duration

/**
 * Exception thrown from within a workflow
 * when an [com.surrealdev.temporal.workflow.WorkflowContext.awaitCondition] call times out.
 *
 * This exception wraps a [kotlinx.coroutines.TimeoutCancellationException] with
 * additional context about the timeout duration and optional summary.
 *
 * Unlike [kotlinx.coroutines.CancellationException], this represents an expected
 * timeout outcome rather than a cancellation, so it can be caught and handled
 * as a normal business logic condition.
 *
 * @property timeout The timeout duration that was specified
 * @property summary An optional summary describing the condition being waited on
 */
class WorkflowConditionTimeoutException(
    message: String = "Condition wait timed out",
    val timeout: Duration? = null,
    val summary: String? = null,
    override val cause: Throwable? = null,
) : TemporalCancellationException(message)
