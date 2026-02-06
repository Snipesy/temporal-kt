package com.surrealdev.temporal.serialization.converter

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.serialization.PayloadConverter
import com.surrealdev.temporal.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import kotlin.reflect.KType

private val JSON_METADATA =
    mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_JSON))

/**
 * JSON converter using kotlinx.serialization.
 *
 * Produces payloads with `encoding: json/plain`. This is a catch-all converter
 * that always returns a payload (never returns null from [toPayload]), so it
 * should be placed last in the converter chain.
 *
 * @param json The [Json] instance to use for serialization/deserialization
 */
class JsonPayloadConverter(
    private val json: Json = DEFAULT_JSON,
) : PayloadConverter {
    override val encoding: String = TemporalPayload.ENCODING_JSON

    override fun toPayload(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload =
        try {
            TemporalPayload.create(JSON_METADATA) { stream ->
                @OptIn(ExperimentalSerializationApi::class)
                json.encodeToStream(json.serializersModule.serializer(typeInfo), value!!, stream)
            }
        } catch (e: Exception) {
            throw SerializationException(
                "Failed to serialize value of type $typeInfo to JSON: ${e.message}",
                e,
            )
        }

    override fun fromPayload(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any? =
        try {
            @OptIn(ExperimentalSerializationApi::class)
            json.decodeFromStream(json.serializersModule.serializer(typeInfo), payload.dataInputStream())
        } catch (e: Exception) {
            throw SerializationException(
                "Failed to deserialize JSON to type $typeInfo: ${e.message}",
                e,
            )
        }

    companion object {
        private val DEFAULT_JSON =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        fun default(): JsonPayloadConverter = JsonPayloadConverter(DEFAULT_JSON)
    }
}
