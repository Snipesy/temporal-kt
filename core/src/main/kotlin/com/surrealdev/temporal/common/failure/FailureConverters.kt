package com.surrealdev.temporal.common.failure

import com.surrealdev.temporal.activity.internal.ActivityDispatcher
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.ApplicationErrorCategory
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.common.exceptions.PayloadProcessingException
import com.surrealdev.temporal.common.exceptions.RemoteException
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.safeDecode
import com.surrealdev.temporal.serialization.safeEncode
import io.temporal.api.failure.v1.ApplicationFailureInfo
import io.temporal.api.failure.v1.Failure
import org.slf4j.LoggerFactory
import kotlin.time.toJavaDuration

private val logger = LoggerFactory.getLogger("com.surrealdev.temporal.common.failure.FailureConverters")

/**
 * Source identifier set on all failures produced by this SDK.
 * Used to gate stack trace overriding on the receive side — only failures
 * originating from the same SDK family get their Java stack trace replaced.
 */
const val FAILURE_SOURCE = "Kotlin"

/**
 * Class name prefixes at which stack trace serialization stops (inclusive).
 *
 * These are the SDK's own dispatch entry points — the methods that call into user code via
 * reflection. Everything below them (interceptor chain, OTel spans, coroutine machinery) is
 * pure SDK infrastructure with no user-meaningful content.
 *
 * We match by prefix rather than exact `className.methodName` because coroutine continuations
 * produce mangled class names like `ActivityDispatcher$invokeDynamicActivity$result$1$1`
 * with methodName `invokeSuspend`, which would not match an exact check.
 */
private val STACK_TRACE_CUTOFF_PREFIXES =
    listOf(
        "${ActivityDispatcher::class.qualifiedName}.invokeMethod",
        "${ActivityDispatcher::class.qualifiedName}.invokeDynamicActivity",
        // Coroutine continuations nest the method name as an inner class
        "${ActivityDispatcher::class.qualifiedName}\$invokeMethod",
        "${ActivityDispatcher::class.qualifiedName}\$invokeDynamicActivity",
    )

/**
 * Serializes [frames] to a newline-separated string, dropping synthetic coroutine boundary
 * markers and stopping at the SDK's own dispatch entry points.
 *
 * ## Why synthetic frames appear
 *
 * kotlinx.coroutines' stack trace recovery (`StackTraceRecovery.kt`) inserts artificial
 * `_COROUTINE._BOUNDARY._` frames to mark the boundary between coroutine execution and
 * coroutine launch-site frames. Two recovery paths place this marker at index 0, *before*
 * any user code:
 *  - `sanitizeStackTrace()` — always puts `ARTIFICIAL_FRAME` at position 0
 *  - `createFinalException()` fallback — when the cause trace has no `BaseContinuationImpl`
 *    frame, the boundary marker lands at index 0
 *
 * These frames carry no user-meaningful content and are silently skipped wherever they appear.
 * `BaseContinuationImpl` and `kotlinx.coroutines.*` frames are intentionally **not** used as
 * hard stops: those frames interleave with user `invokeSuspend` frames in nested coroutine
 * call chains, so stopping at the first occurrence would silently drop legitimate user frames.
 *
 * ## Serialization logic
 *
 * 1. Skip frames whose `className` starts with `_COROUTINE` — synthetic boundary markers.
 * 2. If a frame matches [STACK_TRACE_CUTOFF_PREFIXES], include it as an anchor and stop.
 *    This strips the SDK's internal dispatch machinery (interceptor chain, OTel spans, etc.)
 *    that lives below the reflection call into user code.
 * 3. All other frames — including `kotlinx.coroutines.*` and `BaseContinuationImpl` frames
 *    that appear between user frames — are emitted as-is. Coroutine machinery frames in the
 *    middle of the trace are an artifact of how `kotlinx.coroutines` interleaves
 *    trampoline frames with user continuation frames.
 */
internal fun serializeStackTrace(frames: Array<StackTraceElement>): String =
    buildString {
        for (frame in frames) {
            // Skip synthetic _COROUTINE._BOUNDARY._ / _COROUTINE._CREATION._ markers inserted
            // by kotlinx.coroutines StackTraceRecovery.
            if (frame.className.startsWith("_COROUTINE")) continue

            val fullMethod = "${frame.className}.${frame.methodName}"
            if (STACK_TRACE_CUTOFF_PREFIXES.any { fullMethod.startsWith(it) }) {
                // Include the SDK dispatch frame as a recognisable anchor, then stop.
                // Everything below (InterceptorChain, OTel, coroutine machinery) is stripped.
                appendLine(frame)
                break
            }
            appendLine(frame)
        }
    }.trimEnd()

/**
 * Regex for parsing a single JVM stack trace element in `ClassName.methodName(FileName:line)` format.
 * Matches the format produced by [StackTraceElement.toString] and the Java SDK.
 */
private val STACK_TRACE_ELEMENT_REGEX =
    Regex("""((?<className>.*)\.\s*(?<methodName>.*))\(((?<fileName>.*?)(:(?<lineNumber>\d+))?)\)""")

/**
 * Parses a JVM-formatted stack trace string into an array of [StackTraceElement].
 *
 * Each line is expected to be in `ClassName.methodName(FileName:lineNumber)` format
 * (the output of [StackTraceElement.toString], without the `\tat ` prefix).
 * Lines that don't match are silently skipped.
 */
fun parseStackTrace(stackTrace: String): Array<StackTraceElement> =
    stackTrace
        .lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            // Strip leading "at " prefix if present (defensive)
            val cleaned = if (trimmed.startsWith("at ")) trimmed.removePrefix("at ") else trimmed
            val match = STACK_TRACE_ELEMENT_REGEX.matchEntire(cleaned) ?: return@mapNotNull null
            val className = match.groups["className"]?.value ?: return@mapNotNull null
            val methodName = match.groups["methodName"]?.value ?: return@mapNotNull null
            val fileName = match.groups["fileName"]?.value
            val lineNumber = match.groups["lineNumber"]?.value?.toIntOrNull() ?: -1
            StackTraceElement(className, methodName, fileName, lineNumber)
        }.toList()
        .toTypedArray()

/**
 * Builds an [ApplicationFailure] exception from a proto Failure that has ApplicationFailureInfo,
 * with codec decoding of details.
 *
 * This is the primary path used by activity handle implementations where the codec is available.
 */
internal suspend fun buildApplicationFailureFromProto(
    failure: Failure,
    codec: PayloadCodec,
    cause: Throwable? = null,
): ApplicationFailure {
    val appInfo = failure.applicationFailureInfo
    val category =
        when (appInfo.category) {
            io.temporal.api.enums.v1.ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN -> {
                ApplicationErrorCategory.BENIGN
            }

            else -> {
                ApplicationErrorCategory.UNSPECIFIED
            }
        }
    val details =
        if (appInfo.hasDetails()) {
            codec.safeDecode(EncodedTemporalPayloads(appInfo.details))
        } else {
            TemporalPayloads.EMPTY
        }
    return ApplicationFailure
        .fromProtoWithPayloads(
            type = appInfo.type ?: "UnknownApplicationFailure",
            message = failure.message,
            isNonRetryable = appInfo.nonRetryable,
            details = details,
            category = category,
            cause = cause,
        ).also { it.protoFailure = failure }
}

/**
 * Recursively builds cause exceptions from proto Failure, with codec decoding.
 *
 * This is the primary path used by activity handle implementations where the codec is available.
 * When a node in the chain has [ApplicationFailureInfo][io.temporal.api.failure.v1.ApplicationFailureInfo],
 * details are decoded through the codec.
 *
 * Wrapper failure info types (e.g. `childWorkflowExecutionFailureInfo`, `activityFailureInfo`)
 * are skipped — their metadata is already captured by the caller's typed exception
 * (`ChildWorkflowFailureException`, `WorkflowActivityFailureException`, etc.).
 * When such a wrapper has a cause, we recurse directly into it rather than creating a
 * `RemoteException` for the wrapper node. This matches the Java SDK's behavior where the
 * wrapper produces a typed exception whose cause is the inner failure.
 *
 * When the failure originates from this SDK ([FAILURE_SOURCE]) and carries a non-empty stack trace,
 * the Java [Throwable.stackTrace] array is overridden with the parsed remote trace so that
 * callers see the original throw site rather than the deserialization call path.
 */
internal suspend fun buildCause(
    failure: Failure,
    codec: PayloadCodec,
    depth: Int = 0,
    maxDepth: Int = 20,
): Throwable {
    if (depth >= maxDepth) {
        return RuntimeException(failure.message ?: "Cause failure (max depth reached)")
    }

    // Skip wrapper failure info types — the caller already creates the typed exception
    // (ChildWorkflowFailureException, WorkflowActivityFailureException, etc.).
    // Recurse directly into the cause so the chain doesn't contain a spurious RemoteException.
    if (!failure.hasApplicationFailureInfo() && isWrapperFailure(failure) && failure.hasCause()) {
        return buildCause(failure.cause, codec, depth + 1, maxDepth)
    }

    val nestedCause =
        if (failure.hasCause()) {
            buildCause(failure.cause, codec, depth + 1, maxDepth)
        } else {
            null
        }

    // If this failure node has ApplicationFailureInfo, create an ApplicationFailure exception
    val result: Throwable =
        if (failure.hasApplicationFailureInfo()) {
            buildApplicationFailureFromProto(failure, codec, cause = nestedCause)
        } else {
            RemoteException(
                message = failure.message ?: "Cause failure",
                cause = nestedCause,
            ).also { it.protoFailure = failure }
        }

    // Override Java stack trace when the failure originated from this SDK and has a remote trace.
    // This ensures callers see the original throw site instead of the deserialization call path.
    if (failure.source == FAILURE_SOURCE && failure.stackTrace.isNotEmpty()) {
        val parsed = parseStackTrace(failure.stackTrace)
        if (parsed.isNotEmpty()) {
            result.stackTrace = parsed
        }
    }

    return result
}

/**
 * Returns true if the failure carries a wrapper info type whose metadata is already captured
 * by a typed exception at the call site. These nodes should be skipped in [buildCause] to
 * avoid creating a spurious [RemoteException] layer.
 */
private fun isWrapperFailure(failure: Failure): Boolean =
    failure.hasChildWorkflowExecutionFailureInfo() ||
        failure.hasActivityFailureInfo() ||
        failure.hasCanceledFailureInfo() ||
        failure.hasTimeoutFailureInfo() ||
        failure.hasTerminatedFailureInfo() ||
        failure.hasServerFailureInfo() ||
        failure.hasResetWorkflowFailureInfo()

/**
 * Builds a proto [Failure] from an exception, populating [ApplicationFailureInfo] appropriately.
 *
 * When the exception is an [ApplicationFailure], the failure type, non-retryable flag, details,
 * next retry delay, and category are all preserved in the proto. For other exceptions, the proto
 * is wrapped with a default [ApplicationFailureInfo] (using the exception class name as the type)
 * to ensure the server's retry logic handles it correctly.
 *
 * The stack trace is written as bare `StackTraceElement.toString()` lines (one per line),
 * without the `\tat` prefix or header line. This matches the Java SDK format and enables
 * clean round-tripping through [parseStackTrace].
 *
 * This is shared by activity and workflow failure completion builders.
 */
@InternalTemporalApi
internal suspend fun buildFailureProto(
    exception: Throwable,
    serializer: PayloadSerializer,
    codec: PayloadCodec,
    depth: Int = 0,
): Failure {
    // Format stack trace with SDK/coroutine cutoff to keep the failure payload compact
    val stackTraceString = serializeStackTrace(exception.stackTrace)

    // Use originalMessage for ApplicationFailure to avoid serializing the decorated getMessage()
    // into the proto (which would double-decorate on round-trip).
    val protoMessage =
        if (exception is ApplicationFailure) {
            exception.originalMessage.ifEmpty { exception::class.simpleName ?: "Unknown error" }
        } else {
            exception.message ?: exception::class.simpleName ?: "Unknown error"
        }

    val failureBuilder =
        Failure
            .newBuilder()
            .setMessage(protoMessage)
            .setStackTrace(stackTraceString)
            .setSource(FAILURE_SOURCE)

    if (exception is ApplicationFailure) {
        val appInfoBuilder =
            ApplicationFailureInfo
                .newBuilder()
                .setType(exception.type)
                .setNonRetryable(exception.isNonRetryable)

        // Serialize details if present (raw details from throw side or pre-decoded payloads)
        if (exception.rawDetails.isNotEmpty() || !exception.details.isEmpty) {
            try {
                val detailsPayloads = exception.serializeDetails(serializer)
                val encoded = codec.safeEncode(detailsPayloads)
                appInfoBuilder.setDetails(encoded.toProto())
            } catch (e: PayloadProcessingException) {
                // Codec/serialization failed while encoding exception details - proceed without
                // details rather than masking the original exception. The type, message, and
                // nonRetryable flag are more important than the details.
                logger.warn("Failed to process ApplicationFailure details, omitting details: {}", e.message)
            }
        }

        // Set next retry delay if specified
        exception.nextRetryDelay?.let { delay ->
            val javaDuration = delay.toJavaDuration()
            val protoDuration =
                com.google.protobuf.Duration
                    .newBuilder()
                    .setSeconds(javaDuration.seconds)
                    .setNanos(javaDuration.nano)
                    .build()
            appInfoBuilder.setNextRetryDelay(protoDuration)
        }

        // Set error category if not default
        if (exception.category != ApplicationErrorCategory.UNSPECIFIED) {
            val protoCategory =
                when (exception.category) {
                    ApplicationErrorCategory.BENIGN -> {
                        io.temporal.api.enums.v1.ApplicationErrorCategory.APPLICATION_ERROR_CATEGORY_BENIGN
                    }
                }
            appInfoBuilder.setCategory(protoCategory)
        }

        failureBuilder.setApplicationFailureInfo(appInfoBuilder)
    } else {
        // Wrap non-ApplicationFailure exceptions with ApplicationFailureInfo.
        // This matches Python SDK behavior and ensures the server's retry logic
        // handles the failure correctly (bare Failures without ApplicationFailureInfo
        // may not have retry policies applied properly by the server).
        val appInfoBuilder =
            ApplicationFailureInfo
                .newBuilder()
                .setType(
                    exception::class.qualifiedName
                        ?: exception::class.simpleName
                        ?: "UnknownException",
                ).setNonRetryable(false)
        failureBuilder.setApplicationFailureInfo(appInfoBuilder)
    }

    // Recursively serialize the cause chain
    if (depth < 20) {
        exception.cause?.let { cause ->
            failureBuilder.setCause(buildFailureProto(cause, serializer, codec, depth + 1))
        }
    } else {
        if (exception.cause != null) {
            logger.warn("Cause chain depth limit (20) reached, truncating remaining causes")
        }
    }

    return failureBuilder.build()
}
