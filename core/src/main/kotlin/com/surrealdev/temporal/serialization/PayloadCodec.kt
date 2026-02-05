package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.TemporalPayloads

/**
 * Interface for encoding and decoding payloads after serialization/before deserialization.
 *
 * Codecs operate on already-serialized Payloads, applying transformations like
 * compression or encryption. They are applied after PayloadSerializer.serialize()
 * and before PayloadSerializer.deserialize().
 *
 * Pipeline:
 * ```
 * OUTBOUND: Object -> [PayloadSerializer] -> Payload -> [PayloadCodec.encode] -> Encoded Payload
 * INBOUND: Encoded Payload -> [PayloadCodec.decode] -> Payload -> [PayloadSerializer] -> Object
 * ```
 *
 * Implementations should:
 * - Return the same number of payloads as input (1:1 mapping)
 * - Set appropriate metadata to identify encoded payloads (e.g., "encoding": "binary/gzip")
 * - Pass through payloads they don't handle (check metadata before decoding)
 * - Be thread-safe for concurrent use
 *
 * Example compression codec:
 * ```kotlin
 * class CompressionCodec : PayloadCodec {
 *     override suspend fun encode(payloads: TemporalPayloads): TemporalPayloads {
 *         return payloads.map { payload ->
 *             val compressed = gzip(payload.data)
 *             if (compressed.size < payload.data.size()) {
 *                 payload.toBuilder()
 *                     .putMetadata("encoding", "binary/gzip".toByteString())
 *                     .setData(compressed)
 *                     .build()
 *             } else payload
 *         }
 *     }
 *
 *     override suspend fun decode(payloads: TemporalPayloads): TemporalPayloads {
 *         return payloads.map { payload ->
 *             if (payload.metadata["encoding"] == "binary/gzip") {
 *                 payload.toBuilder()
 *                     .removeMetadata("encoding")
 *                     .setData(gunzip(payload.data))
 *                     .build()
 *             } else payload
 *         }
 *     }
 * }
 * ```
 */
interface PayloadCodec {
    /**
     * Encodes a list of payloads (e.g., compress, encrypt).
     *
     * Called after serialization, before sending to Temporal server.
     *
     * @param payloads The payloads to encode
     * @return The encoded payloads (same count and order as input)
     */
    suspend fun encode(payloads: TemporalPayloads): TemporalPayloads

    /**
     * Decodes a list of payloads (e.g., decompress, decrypt).
     *
     * Called after receiving from Temporal server, before deserialization.
     *
     * @param payloads The payloads to decode
     * @return The decoded payloads (same count and order as input)
     */
    suspend fun decode(payloads: TemporalPayloads): TemporalPayloads
}

/**
 * No-op codec that passes payloads through unchanged.
 * Used as a default when no codec is configured.
 */
object NoOpCodec : PayloadCodec {
    override suspend fun encode(payloads: TemporalPayloads): TemporalPayloads = payloads

    override suspend fun decode(payloads: TemporalPayloads): TemporalPayloads = payloads
}
