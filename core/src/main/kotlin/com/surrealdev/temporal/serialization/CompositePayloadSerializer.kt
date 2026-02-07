package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.serialization.converter.JsonPayloadConverter
import com.surrealdev.temporal.serialization.converter.NullPayloadConverter
import kotlin.reflect.KType

/**
 * A [PayloadSerializer] that delegates to an ordered chain of [PayloadConverter]s.
 *
 * This follows the Temporal SDK convention for multi-format serialization:
 * - **Serialization** uses first-match-wins: converters are tried in order, and the first
 *   one that returns a non-null payload wins.
 * - **Deserialization** uses encoding-based lookup: the payload's `encoding` metadata selects
 *   the matching converter directly (O(1) lookup).
 *
 * The default chain is `[NullPayloadConverter, JsonPayloadConverter]`, which handles
 * null values and JSON serialization for all other types.
 *
 * Custom chains can be built via the `converters { }` DSL in [SerializationPlugin]:
 * ```kotlin
 * install(SerializationPlugin) {
 *     converters {
 *         null()
 *         converter(MyProtobufConverter())
 *         json()  // catch-all, should be last
 *     }
 * }
 * ```
 *
 * @param converters The ordered list of converters. The last converter should be a catch-all
 *   (like [JsonPayloadConverter]) that never returns null from [PayloadConverter.toPayload].
 */
class CompositePayloadSerializer(
    private val converters: List<PayloadConverter>,
) : PayloadSerializer {
    private val convertersByEncoding: Map<String, PayloadConverter> =
        converters.associateBy { it.encoding }

    override fun serialize(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload {
        for (converter in converters) {
            val payload = converter.toPayload(typeInfo, value)
            if (payload != null) return payload
        }
        throw PayloadSerializationException("No converter could serialize value of type $typeInfo")
    }

    override fun deserialize(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any? {
        val encoding = payload.encoding
        // Payloads without encoding metadata (e.g., from Temporal server internals)
        // fall back to the last converter in the chain (typically JSON catch-all)
        val converter =
            if (encoding != null) {
                convertersByEncoding[encoding]
                    ?: throw PayloadSerializationException("No converter registered for encoding: $encoding")
            } else {
                converters.lastOrNull()
                    ?: throw PayloadSerializationException(
                        "No converters configured and payload has no encoding metadata",
                    )
            }
        return converter.fromPayload(typeInfo, payload)
    }

    companion object {
        /**
         * Creates the default composite serializer with [NullPayloadConverter] and [JsonPayloadConverter].
         */
        fun default(): CompositePayloadSerializer =
            CompositePayloadSerializer(
                listOf(
                    NullPayloadConverter,
                    JsonPayloadConverter.default(),
                ),
            )

        /**
         * Creates a composite serializer with [NullPayloadConverter] and a custom [JsonPayloadConverter].
         */
        fun withJson(jsonConverter: JsonPayloadConverter): CompositePayloadSerializer =
            CompositePayloadSerializer(
                listOf(
                    NullPayloadConverter,
                    jsonConverter,
                ),
            )
    }
}
