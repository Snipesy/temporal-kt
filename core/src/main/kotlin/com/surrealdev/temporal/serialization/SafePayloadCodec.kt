package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.PayloadCodecException
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.workflow.lockWorkflowDispatcherIfPresent
import kotlin.reflect.KType

/**
 * Safe wrapper for [PayloadCodec.encode] that normalizes all exceptions to [PayloadCodecException].
 *
 * Custom codec implementations may throw arbitrary exceptions (IOException, IllegalStateException, etc.)
 * instead of the typed [PayloadCodecException]. This wrapper ensures all exceptions are normalized
 * to the expected type for consistent error handling.
 *
 * @return Encoded payloads
 * @throws PayloadCodecException if encoding fails
 */
internal suspend fun PayloadCodec.safeEncode(payloads: TemporalPayloads): EncodedTemporalPayloads =
    lockWorkflowDispatcherIfPresent {
        try {
            this.encode(payloads)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: PayloadCodecException) {
            throw e // Already typed, rethrow as-is
        } catch (e: Exception) {
            throw PayloadCodecException("Codec encode failed: ${e.message}", e)
        }
    }

/**
 * Safe wrapper for [PayloadCodec.decode] that normalizes all exceptions to [PayloadCodecException].
 *
 * Custom codec implementations may throw arbitrary exceptions (IOException, IllegalStateException, etc.)
 * instead of the typed [PayloadCodecException]. This wrapper ensures all exceptions are normalized
 * to the expected type for consistent error handling.
 *
 * @return Decoded payloads
 * @throws PayloadCodecException if decoding fails
 */
internal suspend fun PayloadCodec.safeDecode(payloads: EncodedTemporalPayloads): TemporalPayloads =
    lockWorkflowDispatcherIfPresent {
        try {
            this.decode(payloads)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: PayloadCodecException) {
            throw e // Already typed, rethrow as-is
        } catch (e: Exception) {
            throw PayloadCodecException("Codec decode failed: ${e.message}", e)
        }
    }

/**
 * Safe wrapper for [PayloadSerializer.serialize] that normalizes all exceptions to [PayloadSerializationException].
 *
 * Custom serializer implementations may throw arbitrary exceptions (IOException, IllegalStateException, etc.)
 * instead of the typed [PayloadSerializationException]. This wrapper ensures all exceptions are normalized
 * to the expected type for consistent error handling.
 *
 * @return Serialized payload
 * @throws PayloadSerializationException if serialization fails
 */
internal fun PayloadSerializer.safeSerialize(
    typeInfo: KType,
    value: Any?,
): TemporalPayload {
    try {
        return this.serialize(typeInfo, value)
    } catch (e: PayloadSerializationException) {
        throw e // Already typed, rethrow as-is
    } catch (e: Exception) {
        throw PayloadSerializationException("Serialization failed: ${e.message}", e)
    }
}

/**
 * Safe wrapper for [PayloadSerializer.deserialize] that normalizes all exceptions to [PayloadSerializationException].
 *
 * Custom serializer implementations may throw arbitrary exceptions (IOException, IllegalStateException, etc.)
 * instead of the typed [PayloadSerializationException]. This wrapper ensures all exceptions are normalized
 * to the expected type for consistent error handling.
 *
 * @return Deserialized value
 * @throws PayloadSerializationException if deserialization fails
 */
internal fun PayloadSerializer.safeDeserialize(
    typeInfo: KType,
    payload: TemporalPayload,
): Any? {
    try {
        return this.deserialize(typeInfo, payload)
    } catch (e: PayloadSerializationException) {
        throw e // Already typed, rethrow as-is
    } catch (e: Exception) {
        throw PayloadSerializationException("Deserialization failed: ${e.message}", e)
    }
}

/**
 * Encodes a single [TemporalPayload] through the codec and returns the raw proto [Payload][io.temporal.api.common.v1.Payload].
 *
 * This is a convenience wrapper for the common pattern of wrapping a single payload in a
 * [TemporalPayloads], encoding, and extracting the first proto payload.
 *
 * @return The encoded proto Payload
 * @throws PayloadCodecException if encoding fails
 */
internal suspend fun PayloadCodec.safeEncodeSingle(payload: TemporalPayload): io.temporal.api.common.v1.Payload =
    safeEncode(TemporalPayloads.of(listOf(payload))).proto.getPayloads(0)

/**
 * Decodes a single raw proto [Payload][io.temporal.api.common.v1.Payload] through the codec and returns a [TemporalPayload].
 *
 * This is a convenience wrapper for the common pattern of wrapping a single proto payload in
 * [EncodedTemporalPayloads], decoding, and extracting the first result.
 *
 * @return The decoded TemporalPayload
 * @throws PayloadCodecException if decoding fails
 */
internal suspend fun PayloadCodec.safeDecodeSingle(payload: io.temporal.api.common.v1.Payload): TemporalPayload =
    safeDecode(EncodedTemporalPayloads.fromProtoPayloadList(listOf(payload)))[0]
