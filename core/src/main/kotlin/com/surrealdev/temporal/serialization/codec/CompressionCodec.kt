package com.surrealdev.temporal.serialization.codec

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.PayloadCodec
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Encoding value for GZIP-compressed payloads.
 */
private const val ENCODING_GZIP = "binary/gzip"
private val ENCODING_GZIP_BYTES = TemporalByteString.fromUtf8(ENCODING_GZIP)

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

    private fun encodePayload(payload: TemporalPayload): TemporalPayload {
        // Check if already encoded by this codec (pass through)
        if (payload.encoding == ENCODING_GZIP) {
            return payload
        }

        // Check threshold - don't compress small payloads
        if (payload.dataSize < threshold) {
            return payload
        }

        // Compress the data
        val compressed = compress(payload.data)

        // Only use compression if it actually reduces size
        if (compressed.size >= payload.dataSize) {
            return payload
        }

        // Build new payload preserving existing metadata and adding encoding marker
        val newMetadata = payload.metadataByteStrings.toMutableMap()
        newMetadata[TemporalPayload.METADATA_ENCODING] = ENCODING_GZIP_BYTES
        return TemporalPayload.create(compressed, newMetadata)
    }

    private fun decodePayload(payload: TemporalPayload): TemporalPayload {
        // Check if this payload was compressed by this codec
        if (payload.encoding != ENCODING_GZIP) {
            return payload // Pass through non-compressed payloads
        }

        // Decompress the data
        val decompressed = decompress(payload.data)

        // Build new payload, removing the gzip encoding marker
        val newMetadata = payload.metadataByteStrings.toMutableMap()
        newMetadata.remove(TemporalPayload.METADATA_ENCODING)
        return TemporalPayload.create(decompressed, newMetadata)
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
