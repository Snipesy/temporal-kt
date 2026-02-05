package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.TemporalPayload
import kotlin.reflect.KType
import kotlin.reflect.typeOf as kotlinTypeOf

@PublishedApi
internal inline fun <reified T> typeOf(): KType = kotlinTypeOf<T>()

/**
 * Interface for serializing and deserializing values to/from Temporal Payloads.
 *
 * Note that Temporal Kotlin SDK Relies on KType's being passed around end to end in order to
 * circumvent type erasure issues in Java. We explicitly throw in scenarios where type information is loss.
 * While this 'can' be bypassed you may run into scenarios where the underlying object serialization differs
 * from the serialization that should be performed based on the original type information.
 *
 * Implementations must handle:
 * - Null values
 * - Primitive types (String, Int, Long, Double, Boolean, etc.)
 * - Data classes (with proper serialization annotations)
 * - Collections (List, Set, Map)
 *
 * Example implementation using kotlinx.serialization:
 * ```kotlin
 * class KotlinxJsonSerializer(private val json: Json) : PayloadSerializer {
 *     override fun serialize(typeInfo: TypeInfo, value: Any?): TemporalPayload { ... }
 *     override fun deserialize(typeInfo: TypeInfo, payload: TemporalPayload): Any? { ... }
 * }
 * ```
 */
interface PayloadSerializer {
    /**
     * Serializes a value to a Temporal [Payload].
     *
     * @param typeInfo Type information for the value
     * @param value The value to serialize (may be null)
     * @return A Payload containing the serialized data and metadata
     * @throws SerializationException if serialization fails
     */
    fun serialize(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload

    /**
     * Deserializes a Temporal [Payload] to a value.
     *
     * @param typeInfo Type information for the expected return type
     * @param payload The payload to deserialize
     * @return The deserialized value (may be null if the type is nullable)
     * @throws SerializationException if deserialization fails
     */
    fun deserialize(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any?
}

/**
 * Convenience extension to serialize with reified type parameter.
 */
inline fun <reified T> PayloadSerializer.serialize(value: T): TemporalPayload = serialize(typeOf<T>(), value)

/**
 * Convenience extension to deserialize with reified type parameter.
 */
inline fun <reified T> PayloadSerializer.deserialize(payload: TemporalPayload): T =
    deserialize(typeOf<T>(), payload) as T

/**
 * Exception thrown when serialization or deserialization fails.
 */
class SerializationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
