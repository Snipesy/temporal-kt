package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.serialization.PayloadSerializer
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Converts a Kotlin [Duration] to a protobuf Duration.
 *
 * Shared by all command builders (timers, activities, child workflows,
 * continue-as-new).
 */
internal fun Duration.toProtoDuration(): com.google.protobuf.Duration {
    val javaDuration = this.toJavaDuration()
    return com.google.protobuf.Duration
        .newBuilder()
        .setSeconds(javaDuration.seconds)
        .setNanos(javaDuration.nano)
        .build()
}

/**
 * Builds the per-command user metadata carrying a UI-facing summary.
 *
 * The summary is serialized with the payload serializer but intentionally NOT
 * codec-encoded - it is UI-facing metadata, matching other SDKs.
 */
internal fun buildUserMetadata(
    serializer: PayloadSerializer,
    summary: String,
): io.temporal.api.sdk.v1.UserMetadata =
    io.temporal.api.sdk.v1.UserMetadata
        .newBuilder()
        .setSummary(serializer.serialize(typeOf<String>(), summary).toProto())
        .build()
