package com.surrealdev.temporal.common

import com.google.protobuf.ByteString
import com.surrealdev.temporal.serialization.PayloadSerializer
import io.temporal.api.common.v1.Payload
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Encodes [TypedSearchAttributes] to Temporal Payloads with proper type metadata.
 *
 * Search attributes in Temporal require a `type` metadata field for proper indexing.
 * This encoder ensures each attribute is serialized with the correct type metadata:
 * - "Text" - Full-text searchable string
 * - "Keyword" - Exact-match string
 * - "Int" - 64-bit integer
 * - "Double" - Floating point number
 * - "Bool" - Boolean value
 * - "Datetime" - ISO 8601 timestamp string
 * - "KeywordList" - List of keywords
 *
 * Without this metadata, the Temporal server cannot properly index search attributes.
 */
object SearchAttributeEncoder {
    // Pre-computed type information for each search attribute type
    private val stringType: KType = typeOf<String>()
    private val longType: KType = typeOf<Long>()
    private val doubleType: KType = typeOf<Double>()
    private val booleanType: KType = typeOf<Boolean>()
    private val stringListType: KType = typeOf<List<String>>()

    /**
     * Encodes typed search attributes to a map of Payloads.
     *
     * @param attributes The typed search attributes to encode
     * @param serializer The payload serializer for value serialization
     * @return Map of attribute names to encoded Payloads with type metadata
     */
    fun encode(
        attributes: TypedSearchAttributes,
        serializer: PayloadSerializer,
    ): Map<String, Payload> =
        attributes.pairs.associate { pair ->
            pair.key.name to encodeValue(pair.key, pair.value, serializer)
        }

    /**
     * Encodes a single search attribute value with proper type metadata.
     */
    private fun encodeValue(
        key: SearchAttributeKey<*>,
        value: Any?,
        serializer: PayloadSerializer,
    ): Payload {
        // Null values: binary/null encoding with NO type metadata
        if (value == null) {
            return Payload
                .newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("binary/null"))
                .build()
        }

        // Datetime: convert to ISO 8601 string BEFORE serialization
        val encodableValue =
            when (value) {
                is Instant -> value.toString()
                is OffsetDateTime -> value.toInstant().toString()
                is ZonedDateTime -> value.toInstant().toString()
                else -> value
            }

        // Get the KType for serialization based on the key type
        val valueType = getTypeForKey(key)

        // Serialize the value using the configured serializer
        val payload = serializer.serialize(valueType, encodableValue)

        // Add the critical type metadata for Temporal indexing
        return payload
            .toBuilder()
            .putMetadata("type", ByteString.copyFromUtf8(key.metadataType))
            .build()
    }

    /**
     * Returns the appropriate KType for a given SearchAttributeKey type.
     */
    private fun getTypeForKey(key: SearchAttributeKey<*>): KType =
        when (key) {
            is SearchAttributeKey.Text -> stringType

            is SearchAttributeKey.Keyword -> stringType

            is SearchAttributeKey.Int -> longType

            is SearchAttributeKey.Double -> doubleType

            is SearchAttributeKey.Bool -> booleanType

            is SearchAttributeKey.Datetime -> stringType

            // Already converted to ISO 8601 string
            is SearchAttributeKey.KeywordList -> stringListType
        }
}
