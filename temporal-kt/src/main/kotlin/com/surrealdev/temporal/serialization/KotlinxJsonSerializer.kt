package com.surrealdev.temporal.serialization

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.serializer
import kotlin.reflect.KType

/**
 * Standard metadata key for encoding type.
 */
private const val METADATA_ENCODING = "encoding"

/**
 * JSON encoding type identifier.
 */
private const val ENCODING_JSON = "json/plain"

/**
 * Null encoding type identifier.
 */
private const val ENCODING_NULL = "binary/null"

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
 * - Properly preserves type information through [TypeInfo]
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
        typeInfo: TypeInfo,
        value: Any?,
    ): Payload {
        // Handle null
        if (value == null) {
            return Payload
                .newBuilder()
                .putMetadata(METADATA_ENCODING, ByteString.copyFromUtf8(ENCODING_NULL))
                .build()
        }

        // Serialize to JSON
        val jsonString =
            try {
                serializeToJson(typeInfo.type, value)
            } catch (e: Exception) {
                throw SerializationException(
                    "Failed to serialize value of type ${typeInfo.reifiedClass.simpleName}: ${e.message}",
                    e,
                )
            }

        return Payload
            .newBuilder()
            .putMetadata(METADATA_ENCODING, ByteString.copyFromUtf8(ENCODING_JSON))
            .setData(ByteString.copyFromUtf8(jsonString))
            .build()
    }

    override fun deserialize(
        typeInfo: TypeInfo,
        payload: Payload,
    ): Any? {
        val encoding = payload.metadataMap[METADATA_ENCODING]?.toStringUtf8()

        // Handle null encoding
        if (encoding == ENCODING_NULL) {
            if (!typeInfo.type.isMarkedNullable) {
                throw SerializationException(
                    "Cannot deserialize null payload to non-nullable type ${typeInfo.type}",
                )
            }
            return null
        }

        // Handle JSON encoding
        if (encoding == ENCODING_JSON || encoding == null) {
            val jsonString = payload.data.toStringUtf8()
            return try {
                deserializeFromJson(typeInfo.type, jsonString)
            } catch (e: Exception) {
                throw SerializationException(
                    "Failed to deserialize JSON to type ${typeInfo.type}: ${e.message}",
                    e,
                )
            }
        }

        throw SerializationException("Unsupported encoding: $encoding")
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializeToJson(
        type: KType,
        value: Any,
    ): String {
        val serializer = json.serializersModule.serializer(type) as KSerializer<Any>
        return json.encodeToString(serializer, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeFromJson(
        type: KType,
        jsonString: String,
    ): Any? {
        val serializer = json.serializersModule.serializer(type)
        return json.decodeFromString(serializer, jsonString)
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
