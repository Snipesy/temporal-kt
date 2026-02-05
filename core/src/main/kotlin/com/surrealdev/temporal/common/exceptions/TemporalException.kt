package com.surrealdev.temporal.common.exceptions

import kotlinx.coroutines.CancellationException

/**
 * Base exception for all Temporal runtime exceptions
 */
sealed class TemporalRuntimeException(
    message: String?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception thrown when a Temporal operation is canceled and must be caught alongside other
 * CancellationExceptions.
 */
sealed class TemporalCancellationException(
    message: String,
) : CancellationException(message)
