package com.surrealdev.temporal.core.internal

/**
 * Low-level JNI bridge to the Temporal Rust Core.
 *
 * This object provides the native method declarations that map to Rust functions.
 * Higher-level APIs (TemporalCoreClient, TemporalCoreWorker) should be built on top
 * of these primitives.
 *
 * All methods in this class are internal implementation details and should not be
 * used directly by application code.
 */
object TemporalCoreBridge {
    init {
        NativeLoader.load()
    }

    /**
     * Returns the version of the native Rust library.
     *
     * This is useful for debugging and ensuring the correct library is loaded.
     */
    @JvmStatic
    external fun nativeVersion(): String

    /**
     * Simple echo function to verify JNI communication.
     *
     * @param input The string to echo
     * @return The input string prefixed with "Echo from Rust: "
     */
    @JvmStatic
    external fun nativeEcho(input: String): String

    // Future methods will be added here:
    // - createClient(config: ByteArray): Long
    // - destroyClient(handle: Long)
    // - createWorker(clientHandle: Long, config: ByteArray): Long
    // - destroyWorker(handle: Long)
    // - pollWorkflowTask(workerHandle: Long): ByteArray
    // - completeWorkflowTask(workerHandle: Long, completion: ByteArray)
    // - pollActivityTask(workerHandle: Long): ByteArray
    // - completeActivityTask(workerHandle: Long, completion: ByteArray)
}
