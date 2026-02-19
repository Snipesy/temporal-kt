package com.surrealdev.temporal.internal

/**
 * Returns `true` if this [Throwable] is a JVM [Error] that indicates the runtime is in a
 * compromised state and the application should shut down.
 *
 * Fatal errors:
 * - [OutOfMemoryError] — heap exhaustion, the JVM cannot allocate objects reliably
 * - [InternalError] — JVM implementation bug (e.g. bytecode verifier failure)
 * - [UnknownError] — serious JVM error with no specific subclass
 *
 * Non-fatal [Error] subclasses like [StackOverflowError], [AssertionError], and
 * [LinkageError] are recoverable at the task level and do **not** warrant application shutdown.
 */
internal fun Throwable.isFatalError(): Boolean =
    this is OutOfMemoryError ||
        this is InternalError ||
        this is UnknownError
