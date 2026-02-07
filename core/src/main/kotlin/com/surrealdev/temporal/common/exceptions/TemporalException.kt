package com.surrealdev.temporal.common.exceptions

import com.surrealdev.temporal.annotation.InternalTemporalApi
import io.temporal.api.failure.v1.Failure
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

/**
 * Represents a remote exception reconstructed from a proto Failure that was not an [ApplicationFailure].
 *
 * When the SDK receives a cause chain from the server, non-ApplicationFailure nodes are
 * represented as [RemoteException] instances, preserving the original stack trace from the
 * remote side.
 */
class RemoteException(
    message: String?,
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause) {
    /**
     * The underlying proto Failure object, available when this exception was reconstructed
     * from a remote failure.
     */
    @InternalTemporalApi
    internal var protoFailure: Failure? = null

    /**
     * The original stack trace from the remote execution environment, or null if not available.
     */
    val originalStackTrace: String?
        get() = protoFailure?.stackTrace?.ifEmpty { null }
}
