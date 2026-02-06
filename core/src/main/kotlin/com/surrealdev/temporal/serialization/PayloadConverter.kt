package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.TemporalPayload
import kotlin.reflect.KType

/**
 * Interface for encoding-specific payload conversion.
 *
 * Each converter handles a specific encoding format (e.g., `json/plain`, `binary/null`).
 * Multiple converters are composed into a [CompositePayloadSerializer] to support
 * ordered, multi-format serialization — matching the Temporal SDK convention.
 *
 * Serialization uses first-match-wins: converters are tried in order, and the first
 * one that returns a non-null payload wins. Deserialization uses encoding-based lookup:
 * the payload's `encoding` metadata selects the matching converter.
 *
 * Example custom converter:
 * ```kotlin
 * class ProtobufPayloadConverter : PayloadConverter {
 *     override val encoding = "binary/protobuf"
 *
 *     override fun toPayload(typeInfo: KType, value: Any?): TemporalPayload? {
 *         if (value !is Message) return null  // Can't handle, skip to next
 *         return TemporalPayload.create(PROTO_META) { stream ->
 *             value.writeTo(stream)
 *         }
 *     }
 *
 *     override fun fromPayload(typeInfo: KType, payload: TemporalPayload): Any? {
 *         // Only called when encoding matches "binary/protobuf"
 *         return parseFrom(typeInfo, payload.dataInputStream())
 *     }
 * }
 * ```
 *
 * @see CompositePayloadSerializer
 */
interface PayloadConverter {
    /**
     * The encoding this converter produces (e.g., `"json/plain"`, `"binary/null"`, `"binary/plain"`).
     *
     * This value is used for deserialization lookup: when a payload arrives with this encoding
     * in its metadata, this converter will be selected to deserialize it.
     */
    val encoding: String

    /**
     * Attempts to serialize a value to a [TemporalPayload].
     *
     * Returns `null` if this converter cannot handle the given value, signaling the
     * [CompositePayloadSerializer] to try the next converter in the chain.
     *
     * Catch-all converters (like JSON) should never return `null` and should be placed
     * last in the chain.
     *
     * @param typeInfo Type information for the value
     * @param value The value to serialize (may be null)
     * @return A [TemporalPayload] if this converter can handle the value, or `null` to skip
     */
    fun toPayload(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload?

    /**
     * Deserializes a [TemporalPayload] to a value.
     *
     * This method is only called when the payload's encoding metadata matches this
     * converter's [encoding] property. Implementations can assume the encoding is correct.
     *
     * @param typeInfo Type information for the expected return type
     * @param payload The payload to deserialize
     * @return The deserialized value (may be null if the type is nullable)
     */
    fun fromPayload(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any?
}
