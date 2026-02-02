package com.surrealdev.temporal.core

import com.google.protobuf.Empty
import com.google.protobuf.duration
import com.google.protobuf.timestamp
import com.surrealdev.temporal.core.internal.EphemeralServerCallbackDispatcher
import com.surrealdev.temporal.core.internal.TemporalCoreEphemeralServer
import io.temporal.api.testservice.v1.GetCurrentTimeResponse
import io.temporal.api.testservice.v1.LockTimeSkippingRequest
import io.temporal.api.testservice.v1.SleepRequest
import io.temporal.api.testservice.v1.SleepUntilRequest
import io.temporal.api.testservice.v1.UnlockTimeSkippingRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * An ephemeral Temporal test server with time-skipping support.
 *
 * This server is useful for testing workflows with long timers. It runs an
 * in-memory Temporal test server that can automatically skip time when all
 * workflows are waiting on timers, allowing tests with timers of hours or
 * days to complete in milliseconds.
 *
 * The test server starts with time-skipping locked (disabled). Call
 * [unlockTimeSkipping] to enable automatic time advancement, or use
 * [sleepUntil] and [sleep] for precise time control.
 *
 * For local development without time-skipping, use [TemporalDevServer] instead.
 *
 * Example usage:
 * ```kotlin
 * TemporalRuntime.create().use { runtime ->
 *     TemporalTestServer.start(runtime).use { server ->
 *         println("Test server running at: ${server.targetUrl}")
 *
 *         // Enable automatic time skipping
 *         server.unlockTimeSkipping()
 *
 *         // Or manually advance time
 *         server.lockTimeSkipping()
 *         server.sleepUntil(Instant.now().plusSeconds(3600)) // Skip 1 hour
 *     }
 * }
 * ```
 */
class TemporalTestServer private constructor(
    private val serverPtr: MemorySegment,
    private val runtimePtr: MemorySegment,
    private val arena: Arena,
    private val callbackArena: Arena,
    private val dispatcher: EphemeralServerCallbackDispatcher,
    override val targetUrl: String,
    private val coreClient: TemporalCoreClient,
) : EphemeralServer {
    @Volatile
    private var closed = false

    companion object {
        /**
         * Starts a new test server with time-skipping support.
         *
         * Uses reusable callback stubs via the dispatcher for better performance.
         *
         * @param runtime The Temporal runtime to use
         * @param existingPath Path to existing test server binary (optional, will download if not set)
         * @param downloadTtlSeconds Cache duration for downloads in seconds (0 = no TTL, indefinite cache)
         * @return A running test server instance
         * @throws TemporalCoreException if the server fails to start
         */
        suspend fun start(
            runtime: TemporalRuntime,
            existingPath: String? = null,
            downloadTtlSeconds: Long = 0,
        ): TemporalTestServer {
            runtime.ensureOpen()

            val arena = Arena.ofShared()
            val callbackArena = Arena.ofShared()
            val dispatcher = EphemeralServerCallbackDispatcher(callbackArena, runtime.handle)

            return try {
                val (serverPtr, targetUrl) =
                    suspendCancellableCoroutine { continuation ->
                        var contextId: Long = 0
                        val contextPtr =
                            TemporalCoreEphemeralServer.startTestServer(
                                runtimePtr = runtime.handle,
                                arena = arena,
                                dispatcher = dispatcher,
                                existingPath = existingPath,
                                downloadVersion = "default",
                                downloadTtlSeconds = downloadTtlSeconds,
                            ) { serverPtr, targetUrl, error ->
                                try {
                                    if (error != null) {
                                        continuation.resumeWithException(TemporalCoreException(error))
                                    } else if (serverPtr == null || targetUrl == null) {
                                        continuation.resumeWithException(
                                            TemporalCoreException("Test server start returned null without error"),
                                        )
                                    } else {
                                        continuation.resume(Pair(serverPtr, targetUrl))
                                    }
                                } catch (_: IllegalStateException) {
                                    // Continuation already resumed, ignore
                                }
                            }
                        contextId = dispatcher.getContextId(contextPtr)
                        continuation.invokeOnCancellation {
                            dispatcher.cancelStart(contextId)
                        }
                    }

                // Connect a client for TestService RPC calls
                val client =
                    TemporalCoreClient.connect(
                        runtime = runtime,
                        targetUrl = "http://$targetUrl",
                        namespace = "default",
                    )

                TemporalTestServer(
                    serverPtr = serverPtr,
                    runtimePtr = runtime.handle,
                    arena = arena,
                    callbackArena = callbackArena,
                    dispatcher = dispatcher,
                    targetUrl = targetUrl,
                    coreClient = client,
                )
            } catch (e: Exception) {
                dispatcher.close()
                callbackArena.close()
                arena.close()
                when (e) {
                    is TemporalCoreException -> throw e
                    else -> throw TemporalCoreException("Test server start failed: ${e.message}", cause = e)
                }
            }
        }
    }

    /**
     * Checks if this server has been closed.
     */
    override fun isClosed(): Boolean = closed

    // =========================================================================
    // TestService APIs
    // =========================================================================

    /**
     * Locks (disables) time skipping.
     *
     * When time skipping is locked, time advances at real-time pace.
     * Multiple locks are counted - each [lockTimeSkipping] call must be
     * balanced with an [unlockTimeSkipping] call.
     *
     * The test server starts with time-skipping locked.
     */
    suspend fun lockTimeSkipping() {
        ensureOpen()
        val request = LockTimeSkippingRequest.getDefaultInstance()
        coreClient.testServiceCall("LockTimeSkipping", request.toByteArray()) { input ->
            Empty.parseFrom(input)
        }
    }

    /**
     * Unlocks (enables) time skipping.
     *
     * When time skipping is unlocked and all workflows are waiting on timers,
     * the server automatically advances time to the next timer.
     *
     * Multiple locks are counted - time skipping is only enabled when the
     * lock counter reaches zero.
     *
     * @throws TemporalCoreException if called more times than [lockTimeSkipping]
     */
    suspend fun unlockTimeSkipping() {
        ensureOpen()
        val request = UnlockTimeSkippingRequest.getDefaultInstance()
        coreClient.testServiceCall("UnlockTimeSkipping", request.toByteArray()) { input ->
            Empty.parseFrom(input)
        }
    }

    /**
     * Temporarily unlocks time skipping and advances time by the specified duration.
     *
     * This decrements the lock counter, waits for the server time to advance
     * by the specified duration, then increments the lock counter again.
     *
     * Useful for advancing time by a specific amount while keeping time-skipping
     * locked for precise test control.
     *
     * @param duration The duration to advance time by
     * @throws TemporalCoreException if the lock counter is already zero
     */
    suspend fun unlockTimeSkippingWithSleep(duration: Duration) {
        ensureOpen()
        val request =
            SleepRequest
                .newBuilder()
                .setDuration(duration.toProtoDuration())
                .build()
        coreClient.testServiceCall("UnlockTimeSkippingWithSleep", request.toByteArray()) { input ->
            Empty.parseFrom(input)
        }
    }

    /**
     * Gets the current server time.
     *
     * This may differ from system time due to time skipping.
     *
     * @return The current server time
     */
    suspend fun getCurrentTime(): Instant {
        ensureOpen()
        val request = Empty.getDefaultInstance()
        val response =
            coreClient.testServiceCall("GetCurrentTime", request.toByteArray()) { input ->
                GetCurrentTimeResponse.parseFrom(input)
            }
        return response.time.toInstant()
    }

    /**
     * Advances server time by the specified duration.
     *
     * This call blocks until the server time has advanced by the duration.
     * Time skipping must be unlocked for this to complete quickly.
     *
     * @param duration The duration to advance time by
     */
    suspend fun sleep(duration: Duration) {
        ensureOpen()
        val request =
            SleepRequest
                .newBuilder()
                .setDuration(duration.toProtoDuration())
                .build()
        coreClient.testServiceCall("Sleep", request.toByteArray()) { input ->
            Empty.parseFrom(input)
        }
    }

    /**
     * Advances server time to the specified timestamp.
     *
     * If the current server time is already past the specified timestamp,
     * this returns immediately. Time skipping must be unlocked for this
     * to complete quickly.
     *
     * @param time The target time to advance to
     */
    suspend fun sleepUntil(time: Instant) {
        ensureOpen()
        val request =
            SleepUntilRequest
                .newBuilder()
                .setTimestamp(time.toProtoTimestamp())
                .build()
        coreClient.testServiceCall("SleepUntil", request.toByteArray()) { input ->
            Empty.parseFrom(input)
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private fun ensureOpen() {
        if (closed) {
            throw IllegalStateException("Test server has been closed")
        }
    }

    /**
     * Shuts down and closes this test server.
     *
     * This method blocks until the server is fully shut down.
     * Uses reusable callback stubs via the dispatcher.
     */
    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true

            // Close the client first
            coreClient.close()

            val shutdownFuture = java.util.concurrent.CompletableFuture<Unit>()

            TemporalCoreEphemeralServer.shutdownServer(
                serverPtr = serverPtr,
                dispatcher = dispatcher,
            ) { error ->
                if (error != null) {
                    shutdownFuture.completeExceptionally(TemporalCoreException(error))
                } else {
                    shutdownFuture.complete(Unit)
                }
            }

            try {
                shutdownFuture.get(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Ignore shutdown errors
            }

            // Close dispatcher first to cancel any pending callbacks
            // Late-firing callbacks will see null and just free Rust memory
            dispatcher.close()
            TemporalCoreEphemeralServer.freeServer(serverPtr)
            arena.close()
            callbackArena.close()
        }
    }

    // =========================================================================
    // Proto Conversion Helpers
    // =========================================================================

    private fun Duration.toProtoDuration(): com.google.protobuf.Duration {
        val javaDuration = this.toJavaDuration()
        return duration {
            seconds = javaDuration.seconds
            nanos = javaDuration.nano
        }
    }

    private fun Instant.toProtoTimestamp(): com.google.protobuf.Timestamp =
        timestamp {
            seconds = this@toProtoTimestamp.epochSecond
            nanos = this@toProtoTimestamp.nano
        }

    private fun com.google.protobuf.Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
}
