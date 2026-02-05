package com.surrealdev.temporal.serialization.codec

import com.google.protobuf.ByteString
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.PayloadCodec
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Metadata key used to identify the encoding type.
 */
private const val METADATA_ENCODING = "encoding"

/**
 * Encoding value for GZIP-compressed payloads.
 */
private const val ENCODING_GZIP = "binary/gzip"

/**
 * GZIP compression codec with configurable size threshold.
 *
 * Payloads smaller than [threshold] bytes are passed through unchanged.
 * Compression is only applied if it actually reduces the payload size.
 * Compressed payloads are marked with `encoding: binary/gzip` metadata.
 *
 * Usage:
 * ```kotlin
 * app.install(PayloadCodecPlugin) {
 *     compression(threshold = 1024)  // Only compress payloads > 1KB
 * }
 * ```
 *
 * @param threshold Minimum payload size in bytes to trigger compression (default: 256)
 */
class CompressionCodec(
    private val threshold: Int = 256,
) : PayloadCodec {
    @OptIn(InternalTemporalApi::class)
    override suspend fun encode(payloads: TemporalPayloads): TemporalPayloads =
        TemporalPayloads.of(payloads.payloads.map { encodePayload(it) })

    @OptIn(InternalTemporalApi::class)
    override suspend fun decode(payloads: TemporalPayloads): TemporalPayloads =
        TemporalPayloads.of(payloads.payloads.map { decodePayload(it) })

    @OptIn(InternalTemporalApi::class)
    private fun encodePayload(payload: TemporalPayload): TemporalPayload {
        // Check if already encoded by this codec (pass through)
        if (payload.proto.metadataMap[METADATA_ENCODING]?.toStringUtf8() == ENCODING_GZIP) {
            return payload
        }

        // Check threshold - don't compress small payloads
        if (payload.proto.data.size() < threshold) {
            return payload
        }

        // Compress the data
        val compressed = compress(payload.proto.data.toByteArray())

        // Only use compression if it actually reduces size
        if (compressed.size >= payload.proto.data.size()) {
            return payload
        }

        // Build new payload preserving existing metadata and adding encoding marker
        val proto =
            io.temporal.api.common.v1.Payload
                .newBuilder()
                .putAllMetadata(payload.proto.metadataMap)
                .putMetadata(METADATA_ENCODING, ByteString.copyFromUtf8(ENCODING_GZIP))
                .setData(ByteString.copyFrom(compressed))
                .build()
        return TemporalPayload(proto)
    }

    private fun decodePayload(payload: TemporalPayload): TemporalPayload {
        // Check if this payload was compressed by this codec
        val encoding = payload.proto.metadataMap[METADATA_ENCODING]?.toStringUtf8()
        if (encoding != ENCODING_GZIP) {
            return payload // Pass through non-compressed payloads
        }

        // Decompress the data
        val decompressed = decompress(payload.proto.data.toByteArray())

        // Build new payload, removing the gzip encoding marker
        val newMetadata = payload.proto.metadataMap.toMutableMap()
        newMetadata.remove(METADATA_ENCODING)

        val proto =
            io.temporal.api.common.v1.Payload
                .newBuilder()
                .putAllMetadata(newMetadata)
                .setData(ByteString.copyFrom(decompressed))
                .build()
        return TemporalPayload(proto)
    }

    private fun compress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(data)
        }
        return baos.toByteArray()
    }

    private fun decompress(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }
}
