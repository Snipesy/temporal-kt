package com.surrealdev.temporal.core

/**
 * Exception thrown when an error occurs in the Temporal Core native bridge.
 *
 * This exception wraps errors that originate from the Rust side of the FFI boundary.
 */
class TemporalCoreException(
    message: String,
    val errorType: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    override fun toString(): String =
        if (errorType != null) {
            "TemporalCoreException[$errorType]: $message"
        } else {
            "TemporalCoreException: $message"
        }
}
