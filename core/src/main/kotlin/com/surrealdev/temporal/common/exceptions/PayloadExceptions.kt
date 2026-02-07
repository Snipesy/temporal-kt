package com.surrealdev.temporal.common.exceptions

/**
 * Base exception for all payload processing failures (serialization and codec).
 *
 * This sealed class allows catching both [PayloadSerializationException] and
 * [PayloadCodecException] with a single catch block when the distinction
 * between serialization and codec errors is not important.
 *
 * ```kotlin
 * try {
 *     // encode/decode/serialize/deserialize
 * } catch (e: PayloadProcessingException) {
 *     // Handles both codec and serialization failures
 * }
 * ```
 */
sealed class PayloadProcessingException(
    message: String,
    cause: Throwable? = null,
) : TemporalRuntimeException(message, cause)

/**
 * Exception thrown when payload serialization or deserialization fails.
 *
 * This exception is thrown by [com.surrealdev.temporal.serialization.PayloadSerializer]
 * implementations when they fail to serialize a value to a payload or deserialize
 * a payload to a value.
 *
 * Common causes:
 * - Type is not registered with the serializer
 * - Malformed payload data
 * - Type mismatch between expected and actual types
 * - Missing required fields in deserialization
 */
class PayloadSerializationException(
    message: String,
    cause: Throwable? = null,
) : PayloadProcessingException(message, cause)

/**
 * Exception thrown when payload codec encoding or decoding fails.
 *
 * This exception is thrown by [com.surrealdev.temporal.serialization.PayloadCodec]
 * implementations when they fail to encode or decode payloads. Codecs are typically
 * used for compression, encryption, or other payload transformations.
 *
 * Common causes:
 * - Encryption/decryption key mismatch
 * - Corrupted compressed data
 * - Codec configuration error
 * - Unsupported encoding format
 */
class PayloadCodecException(
    message: String,
    cause: Throwable? = null,
) : PayloadProcessingException(message, cause)
