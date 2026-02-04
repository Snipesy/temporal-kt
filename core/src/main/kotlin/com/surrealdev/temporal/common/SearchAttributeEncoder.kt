package com.surrealdev.temporal.common

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

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
    private const val ENCODING_JSON = "json/plain"
    private const val ENCODING_NULL = "binary/null"

    /**
     * Encodes typed search attributes to a map of Payloads.
     *
     * @param attributes The typed search attributes to encode
     * @return Map of attribute names to encoded Payloads with type metadata
     */
    fun encode(attributes: TypedSearchAttributes): Map<String, Payload> =
        attributes.pairs.associate { pair ->
            pair.key.name to encodeValue(pair.key, pair.value)
        }

    /**
     * Encodes a single search attribute value with proper type metadata.
     * Uses kotlinx.serialization JSON primitives for correct formatting.
     */
    private fun encodeValue(
        key: SearchAttributeKey<*>,
        value: Any?,
    ): Payload {
        // Null values: binary/null encoding with NO type metadata
        if (value == null) {
            return Payload
                .newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8(ENCODING_NULL))
                .build()
        }

        // Encode value as JSON using kotlinx.serialization
        val jsonValue = encodeToJson(key, value)

        return Payload
            .newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8(ENCODING_JSON))
            .putMetadata("type", ByteString.copyFromUtf8(key.metadataType))
            .setData(ByteString.copyFromUtf8(jsonValue))
            .build()
    }

    /**
     * Encodes a value to raw JSON based on the search attribute key type.
     * Uses kotlinx.serialization's JsonUnquotedLiteral for raw numeric/boolean values.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun encodeToJson(
        key: SearchAttributeKey<*>,
        value: Any,
    ): String =
        when (key) {
            is SearchAttributeKey.Text -> Json.encodeToString(JsonPrimitive(value as String))
            is SearchAttributeKey.Keyword -> Json.encodeToString(JsonPrimitive(value as String))
            is SearchAttributeKey.Int -> Json.encodeToString(JsonUnquotedLiteral((value as Long).toString()))
            is SearchAttributeKey.Double -> {
                Json.encodeToString(JsonUnquotedLiteral((value as kotlin.Double).toString()))
            }
            is SearchAttributeKey.Bool -> Json.encodeToString(JsonUnquotedLiteral((value as Boolean).toString()))
            is SearchAttributeKey.Datetime -> {
                val instant =
                    when (value) {
                        is Instant -> value
                        is OffsetDateTime -> value.toInstant()
                        is ZonedDateTime -> value.toInstant()
                        else -> throw IllegalArgumentException("Unsupported datetime type: ${value::class}")
                    }
                Json.encodeToString(JsonPrimitive(instant.toString()))
            }
            is SearchAttributeKey.KeywordList -> {
                @Suppress("UNCHECKED_CAST")
                val list = value as List<String>
                Json.encodeToString(
                    buildJsonArray {
                        list.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        }
}
