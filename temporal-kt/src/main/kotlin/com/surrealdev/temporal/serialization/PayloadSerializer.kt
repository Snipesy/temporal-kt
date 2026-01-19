package com.surrealdev.temporal.serialization

import io.temporal.api.common.v1.Payload
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf as kotlinTypeOf

/**
 * Type information for serialization/deserialization.
 *
 * Captures both the compile-time type (via KType) and runtime class
 * for proper handling of generics and nullable types.
 */
data class TypeInfo(
    /**
     * The full Kotlin type including generics and nullability.
     */
    val type: KType,
    /**
     * The runtime class (erased generic type).
     */
    val reifiedClass: KClass<*>,
)

/**
 * Creates a [TypeInfo] for a reified type parameter.
 */
inline fun <reified T> typeInfoOf(): TypeInfo =
    TypeInfo(
        type = typeOf<T>(),
        reifiedClass = T::class,
    )

/**
 * Creates a [TypeInfo] from a KType.
 */
fun typeInfoOf(type: KType): TypeInfo =
    TypeInfo(
        type = type,
        reifiedClass = type.classifier as? KClass<*> ?: Any::class,
    )

/**
 * Creates a [TypeInfo] for a value at runtime.
 * Note: This loses generic type information.
 */
fun typeInfoOf(value: Any?): TypeInfo =
    if (value == null) {
        TypeInfo(
            type = typeOf<Any?>(),
            reifiedClass = Any::class,
        )
    } else {
        TypeInfo(
            type = value::class.createType(nullable = false),
            reifiedClass = value::class,
        )
    }

@PublishedApi
internal inline fun <reified T> typeOf(): KType = kotlinTypeOf<T>()

/**
 * Interface for serializing and deserializing values to/from Temporal Payloads.
 *
 * Implementations must handle:
 * - Null values
 * - Primitive types (String, Int, Long, Double, Boolean, etc.)
 * - Data classes (with proper serialization annotations)
 * - Collections (List, Set, Map)
 * - Generic types (using [TypeInfo] for type preservation)
 *
 * Example implementation using kotlinx.serialization:
 * ```kotlin
 * class KotlinxJsonSerializer(private val json: Json) : PayloadSerializer {
 *     override fun serialize(typeInfo: TypeInfo, value: Any?): Payload { ... }
 *     override fun deserialize(typeInfo: TypeInfo, payload: Payload): Any? { ... }
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
        typeInfo: TypeInfo,
        value: Any?,
    ): Payload

    /**
     * Deserializes a Temporal [Payload] to a value.
     *
     * @param typeInfo Type information for the expected return type
     * @param payload The payload to deserialize
     * @return The deserialized value (may be null if the type is nullable)
     * @throws SerializationException if deserialization fails
     */
    fun deserialize(
        typeInfo: TypeInfo,
        payload: Payload,
    ): Any?
}

/**
 * Convenience extension to serialize with reified type parameter.
 */
inline fun <reified T> PayloadSerializer.serialize(value: T): Payload = serialize(typeInfoOf<T>(), value)

/**
 * Convenience extension to deserialize with reified type parameter.
 */
inline fun <reified T> PayloadSerializer.deserialize(payload: Payload): T = deserialize(typeInfoOf<T>(), payload) as T

/**
 * Extension function to create a KType from KClass.
 * Used when only runtime class information is available.
 */
@PublishedApi
internal fun KClass<*>.createType(nullable: Boolean = false): KType =
    object : KType {
        override val annotations: List<Annotation> = emptyList()
        override val arguments: List<kotlin.reflect.KTypeProjection> = emptyList()
        override val classifier: kotlin.reflect.KClassifier = this@createType
        override val isMarkedNullable: Boolean = nullable
    }

/**
 * Exception thrown when serialization or deserialization fails.
 */
class SerializationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
