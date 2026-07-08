package com.surrealdev.temporal.core

/**
 * Exception thrown when an error occurs in the Temporal Core native bridge.
 *
 * This exception wraps errors that originate from the Rust side of the FFI boundary.
 *
 * @param writableStackTrace Whether to capture a stack trace at construction.
 *   Constructions on native (FFM upcall) callback threads MUST pass false: the trace
 *   is meaningless there (it shows the Rust callback thread, not user code), and the
 *   JVM's stack walk over upcall stub frames has proven crash-prone (SIGSEGV in
 *   `Throwable.fillInStackTrace` on macOS aarch64). Callers on normal JVM threads can
 *   keep the default.
 */
class TemporalCoreException(
    message: String,
    val errorType: String? = null,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    writableStackTrace: Boolean = true,
) : RuntimeException(message, cause, true, writableStackTrace) {
    override fun toString(): String =
        buildString {
            append("TemporalCoreException")
            if (errorType != null) append("[$errorType]")
            append(": $message")
            if (statusCode != null) append(" (code=$statusCode)")
        }
}
