package com.surrealdev.temporal.activity

import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KType
import kotlin.time.Instant

/**
 * Context available within an activity execution.
 *
 * This context provides access to activity information and operations like
 * heartbeating and cancellation checking.
 *
 * As a [CoroutineScope], activity code can use structured concurrency:
 * ```kotlin
 * @Activity
 * suspend fun ActivityContext.processInParallel(items: List<String>): List<Result> {
 *     return items.map { item ->
 *         async { processItem(item) }
 *     }.awaitAll()
 * }
 * ```
 *
 * As a [CoroutineContext.Element], it can be accessed from any coroutine
 * running within the activity's scope using `coroutineContext[ActivityContext]`.
 *
 * Usage:
 * ```kotlin
 * class MyActivity {
 *     @Activity
 *     suspend fun greet(name: String): String {
 *         // Access context from coroutine context
 *         val ctx = coroutineContext[ActivityContext]!!
 *         ctx.heartbeat("Processing $name")
 *         return "Hello, $name"
 *     }
 * }
 * ```
 *
 * Or as an extension receiver:
 * ```kotlin
 * @Activity
 * suspend fun ActivityContext.greet(name: String): String {
 *     heartbeat("Processing $name")
 *     return "Hello, $name"
 * }
 * ```
 */
interface ActivityContext :
    CoroutineScope,
    CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ActivityContext>

    override val key: CoroutineContext.Key<*> get() = Key

    /**
     * The payload serializer used for serializing/deserializing heartbeat details.
     */
    val serializer: PayloadSerializer

    /**
     * Information about the currently executing activity.
     */
    val info: ActivityInfo

    /**
     * Reports progress of a long-running activity with optional details.
     *
     * Call this periodically for activities that take longer than
     * the heartbeat timeout. The details can be retrieved if the
     * activity is retried.
     *
     * This is a low-level method that takes a serialized Payload directly.
     * Consider using the reified extension function `heartbeat<T>(value: T)` instead.
     *
     * @param details Pre-serialized progress details to record, or null for a simple heartbeat
     */
    suspend fun heartbeatWithPayload(details: TemporalPayload? = null)

    /**
     * Checks if cancellation has been requested for this activity.
     *
     * Activities should check this periodically and exit gracefully
     * when true.
     */
    val isCancellationRequested: Boolean

    /**
     * Throws [ActivityCancelledException] if cancellation has been requested.
     *
     * Convenience method for activities that want to exit immediately on cancellation.
     */
    fun ensureNotCancelled()
}

/**
 * Information about the currently executing activity.
 */
data class ActivityInfo(
    /** Unique identifier for this activity execution. */
    val activityId: String,
    /** The activity type name. */
    val activityType: String,
    /** The task queue this activity is running on. */
    val taskQueue: String,
    /** Attempt number (1-based). */
    val attempt: Int,
    /** When this activity execution started. */
    val startTime: Instant,
    /** When this activity is scheduled to timeout. */
    val deadline: Instant?,
    /** Heartbeat details from the previous attempt, if any. */
    val heartbeatDetails: HeartbeatDetails?,
    /** The workflow that scheduled this activity. */
    val workflowInfo: ActivityWorkflowInfo,
)

/**
 * Holds raw heartbeat data from a previous activity attempt.
 *
 * Deserialization is deferred until the caller provides the expected type,
 * since the activity knows what type it heartbeated with.
 *
 * Usage:
 * ```kotlin
 * val lastProgress = context.info.heartbeatDetails?.get<MyProgressType>()
 * ```
 */
class HeartbeatDetails internal constructor(
    @PublishedApi internal val payload: TemporalPayload,
    @PublishedApi internal val serializer: PayloadSerializer,
) {
    /**
     * Deserializes the heartbeat data to the specified type.
     */
    inline fun <reified T> get(): T = serializer.deserialize<T>(payload)

    /**
     * Deserializes the heartbeat data using an explicit KType.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(type: KType): T = serializer.deserialize(type, payload) as T
}

/**
 * Information about the workflow that scheduled an activity.
 */
data class ActivityWorkflowInfo(
    /** Workflow ID of the parent workflow. */
    val workflowId: String,
    /** Run ID of the parent workflow. */
    val runId: String,
    /** Workflow type of the parent workflow. */
    val workflowType: String,
    /** Namespace of the parent workflow. */
    val namespace: String,
)

/**
 * Exception thrown when an activity is cancelled.
 *
 * Use pattern matching to handle different cancellation reasons:
 * ```kotlin
 * catch (e: ActivityCancelledException) {
 *     when (e) {
 *         is ActivityCancelledException.TimedOut -> // handle timeout
 *         is ActivityCancelledException.WorkerShutdown -> // cleanup before shutdown
 *         else -> // handle other cancellations
 *     }
 * }
 * ```
 */
sealed class ActivityCancelledException(
    message: String,
) : RuntimeException(message) {
    /** Activity no longer exists on the server (may have already completed). */
    class NotFound(
        message: String = "Activity not found",
    ) : ActivityCancelledException(message)

    /** Activity was explicitly cancelled by the workflow or user. */
    class Cancelled(
        message: String = "Activity was cancelled",
    ) : ActivityCancelledException(message)

    /** Activity exceeded its timeout. */
    class TimedOut(
        message: String = "Activity timed out",
    ) : ActivityCancelledException(message)

    /** Worker is shutting down and the graceful timeout has elapsed. */
    class WorkerShutdown(
        message: String = "Worker is shutting down",
    ) : ActivityCancelledException(message)

    /** Activity was paused. */
    class Paused(
        message: String = "Activity was paused",
    ) : ActivityCancelledException(message)

    /** Activity was reset. */
    class Reset(
        message: String = "Activity was reset",
    ) : ActivityCancelledException(message)
}

// =============================================================================
// Reified Extension Functions for Type-Safe Heartbeat
// =============================================================================

/**
 * Reports progress of a long-running activity with typed details.
 *
 * Call this periodically for activities that take longer than the heartbeat timeout.
 * The details can be retrieved via [ActivityInfo.heartbeatDetails] if the activity is retried.
 *
 * Example:
 * ```kotlin
 * @Activity
 * suspend fun ActivityContext.processItems(items: List<String>): Int {
 *     items.forEachIndexed { index, item ->
 *         // Process item...
 *         heartbeat(index) // Heartbeat with progress
 *     }
 *     return items.size
 * }
 * ```
 *
 * @param T The type of the heartbeat details
 * @param value The progress details to record
 */
suspend inline fun <reified T> ActivityContext.heartbeat(value: T) {
    val payload = serializer.serialize<T>(value)
    heartbeatWithPayload(payload)
}

/**
 * Reports a heartbeat with explicit type information.
 *
 * Use this overload when you need to provide the type explicitly
 * rather than relying on type inference.
 *
 * @param type The type information for the value
 * @param value The progress details to record
 */
suspend fun ActivityContext.heartbeat(
    type: KType,
    value: Any?,
) {
    val payload = serializer.serialize(type, value)
    heartbeatWithPayload(payload)
}

/**
 * Reports a simple heartbeat without any details.
 *
 * Use this when you just want to signal that the activity is still making progress
 * but don't need to record any specific state.
 */
suspend fun ActivityContext.heartbeat() {
    heartbeatWithPayload(null)
}

// =============================================================================
// EncodedPayloads for Dynamic Activity Support
// =============================================================================

/**
 * Wrapper for encoded activity payloads with type-safe decoding.
 *
 * Used by dynamic activity handlers to decode arguments at runtime:
 * ```kotlin
 * dynamicActivity { activityType, payloads ->
 *     val arg1 = payloads.decode<String>(0)
 *     val arg2 = payloads.decode<Int>(1)
 *     // ...
 * }
 * ```
 */
class EncodedPayloads(
    @PublishedApi internal val payloads: TemporalPayloads,
    @PublishedApi internal val serializer: PayloadSerializer,
) {
    /** The number of payloads. */
    val size: Int get() = payloads.size

    /**
     * Decodes the payload at the given index to the specified type.
     *
     * @param index The zero-based index of the payload to decode
     * @return The decoded value
     * @throws IllegalArgumentException if the index is out of bounds
     */
    inline fun <reified T> decode(index: Int): T {
        require(index in payloads.indices) { "Index $index out of bounds (size=$size)" }
        return serializer.deserialize<T>(payloads[index])
    }

    /**
     * Returns the raw payload at the given index without decoding.
     *
     * @param index The zero-based index of the payload
     * @return The raw payload
     * @throws IllegalArgumentException if the index is out of bounds
     */
    fun raw(index: Int): TemporalPayload {
        require(index in payloads.indices) { "Index $index out of bounds (size=$size)" }
        return payloads[index]
    }
}
