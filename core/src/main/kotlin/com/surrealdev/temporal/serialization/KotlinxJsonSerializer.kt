package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.serializer
import java.io.InputStream
import java.io.OutputStream
import kotlin.reflect.KType

/**
 * Pre-built metadata maps for common encoding types.
 */
private val JSON_METADATA =
    mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_JSON))
private val NULL_METADATA =
    mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_NULL))

/**
 * A [PayloadSerializer] implementation using kotlinx.serialization with JSON encoding.
 *
 * This is the default serializer provided by the SDK. It encodes values as JSON
 * and stores them in the Payload's data field, with appropriate metadata.
 *
 * Features:
 * - Handles null values with binary/null encoding
 * - Handles primitive types efficiently
 * - Uses kotlinx.serialization for complex types
 *
 * Usage:
 * ```kotlin
 * val serializer = KotlinxJsonSerializer(Json {
 *     encodeDefaults = true
 *     ignoreUnknownKeys = true
 * })
 *
 * val payload = serializer.serialize(MyData("hello", 42))
 * val data: MyData = serializer.deserialize(typeInfoOf<MyData>(), payload) as MyData
 * ```
 */
class KotlinxJsonSerializer(
    private val json: Json = Json.Default,
) : PayloadSerializer {
    override fun serialize(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload {
        // Handle null
        if (value == null) {
            return TemporalPayload.create(NULL_METADATA)
        }

        // Serialize to JSON directly into the payload data stream
        return try {
            TemporalPayload.create(JSON_METADATA) { stream ->
                serializeToJson(typeInfo, stream, value)
            }
        } catch (e: Exception) {
            throw SerializationException(
                "Failed to serialize value of type ${typeInfo.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
    }

    override fun deserialize(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any? {
        val encoding = payload.encoding

        // Handle null encoding
        if (encoding == TemporalPayload.ENCODING_NULL) {
            if (!typeInfo.isMarkedNullable) {
                throw SerializationException(
                    "Cannot deserialize null payload to non-nullable type ${typeInfo.javaClass.simpleName}",
                )
            }
            return null
        }

        // Handle JSON encoding
        if (encoding == TemporalPayload.ENCODING_JSON || encoding == null) {
            return try {
                deserializeFromJson(typeInfo, payload.dataInputStream())
            } catch (e: Exception) {
                throw SerializationException(
                    "Failed to deserialize JSON to type ${typeInfo.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }

        throw SerializationException("Unsupported encoding: $encoding")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun serializeToJson(
        type: KType,
        stream: OutputStream,
        value: Any,
    ) {
        val serializer = json.serializersModule.serializer(type)
        return json.encodeToStream(serializer, value, stream)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun deserializeFromJson(
        type: KType,
        jsonStream: InputStream,
    ): Any? {
        val serializer = json.serializersModule.serializer(type)
        return json.decodeFromStream(serializer, jsonStream)
    }

    companion object {
        /**
         * Creates a default [KotlinxJsonSerializer] with sensible defaults.
         */
        fun default(): KotlinxJsonSerializer =
            KotlinxJsonSerializer(
                Json {
                    encodeDefaults = true
                    ignoreUnknownKeys = true
                },
            )
    }
}

/**
 * A serializer for [Unit] type since kotlinx.serialization doesn't
 * have a built-in serializer for it.
 */
internal object UnitSerializer : KSerializer<Unit> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.Unit", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Unit,
    ) {
        encoder.encodeString("null")
    }

    override fun deserialize(decoder: Decoder) {
        decoder.decodeString()
        return Unit
    }
}

/**
 * Creates a [Json] instance with Unit serializer support.
 */
fun jsonWithUnitSupport(configure: kotlinx.serialization.json.JsonBuilder.() -> Unit = {}): Json =
    Json {
        serializersModule =
            SerializersModule {
                contextual(UnitSerializer)
            }
        configure()
    }
