package com.surrealdev.temporal.serialization

import com.google.protobuf.ByteString
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.toTemporal
import com.surrealdev.temporal.serialization.codec.ChainedCodec
import com.surrealdev.temporal.serialization.codec.CompressionCodec
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(InternalTemporalApi::class)
class PayloadCodecTest {
    private fun createPayload(
        data: String,
        metadata: Map<String, String> = emptyMap(),
    ): TemporalPayload {
        val builder = Payload.newBuilder().setData(ByteString.copyFromUtf8(data))
        metadata.forEach { (k, v) ->
            builder.putMetadata(k, ByteString.copyFromUtf8(v))
        }
        return builder.build().toTemporal()
    }

    @Test
    fun `NoOpCodec passes payloads through unchanged`() =
        runTest {
            val payload = createPayload("test data")
            val encoded = NoOpCodec.encode(TemporalPayloads.of(listOf(payload)))
            assertEquals(payload, encoded[0])

            val decoded = NoOpCodec.decode(encoded)
            assertEquals(payload, decoded[0])
        }

    @Test
    fun `CompressionCodec compresses large payloads`() =
        runTest {
            val codec = CompressionCodec(threshold = 10)
            val largeData = "x".repeat(1000)
            val payload = createPayload(largeData)

            val encoded = codec.encode(TemporalPayloads.of(listOf(payload)))
            val encodedPayload = encoded[0]

            // Should be compressed (smaller)
            assertTrue(encodedPayload.data.size < payload.data.size)
            // Should have encoding metadata
            assertEquals("binary/gzip", encodedPayload.getMetadataString("encoding"))

            // Decode should restore original
            val decoded = codec.decode(encoded)
            assertEquals(largeData, String(decoded[0].data))
            // Should remove encoding metadata
            assertTrue(decoded[0].getMetadataString("encoding") == null)
        }

    @Test
    fun `CompressionCodec passes through small payloads`() =
        runTest {
            val codec = CompressionCodec(threshold = 1000)
            val smallData = "small"
            val payload = createPayload(smallData)

            val encoded = codec.encode(TemporalPayloads.of(listOf(payload)))
            // Should be unchanged
            assertEquals(payload, encoded[0])
        }

    @Test
    fun `CompressionCodec skips compression when it increases size`() =
        runTest {
            val codec = CompressionCodec(threshold = 1)
            // Small incompressible data
            val payload = createPayload("abc")

            val encoded = codec.encode(TemporalPayloads.of(listOf(payload)))
            // Should be unchanged (compression would increase size)
            assertEquals(payload, encoded[0])
        }

    @Test
    fun `CompressionCodec preserves existing metadata`() =
        runTest {
            val codec = CompressionCodec(threshold = 10)
            val largeData = "y".repeat(500)
            val payload = createPayload(largeData, mapOf("custom-key" to "custom-value"))

            val encoded = codec.encode(TemporalPayloads.of(listOf(payload)))
            val encodedPayload = encoded[0]

            // Should preserve custom metadata
            assertEquals("custom-value", encodedPayload.getMetadataString("custom-key"))
            // Should add encoding metadata
            assertEquals("binary/gzip", encodedPayload.getMetadataString("encoding"))

            // After decode, custom metadata should remain, encoding should be removed
            val decoded = codec.decode(encoded)
            assertEquals("custom-value", decoded[0].getMetadataString("custom-key"))
            assertTrue(decoded[0].getMetadataString("encoding") == null)
        }

    @Test
    fun `CompressionCodec passes through non-gzip payloads on decode`() =
        runTest {
            val codec = CompressionCodec()
            val payload = createPayload("test", mapOf("encoding" to "binary/other"))

            val decoded =
                codec.decode(
                    com.surrealdev.temporal.common
                        .EncodedTemporalPayloads(TemporalPayloads.of(listOf(payload)).proto),
                )
            // Should pass through unchanged
            assertEquals(payload, decoded[0])
        }

    @Test
    fun `ChainedCodec applies codecs in correct order for encode`() =
        runTest {
            val order = mutableListOf<String>()

            val codec1 =
                object : PayloadCodec {
                    override suspend fun encode(
                        payloads: TemporalPayloads,
                    ): com.surrealdev.temporal.common.EncodedTemporalPayloads {
                        order.add("encode-A")
                        return com.surrealdev.temporal.common
                            .EncodedTemporalPayloads(payloads.proto)
                    }

                    override suspend fun decode(
                        payloads: com.surrealdev.temporal.common.EncodedTemporalPayloads,
                    ): TemporalPayloads {
                        order.add("decode-A")
                        return TemporalPayloads(payloads.proto)
                    }
                }

            val codec2 =
                object : PayloadCodec {
                    override suspend fun encode(
                        payloads: TemporalPayloads,
                    ): com.surrealdev.temporal.common.EncodedTemporalPayloads {
                        order.add("encode-B")
                        return com.surrealdev.temporal.common
                            .EncodedTemporalPayloads(payloads.proto)
                    }

                    override suspend fun decode(
                        payloads: com.surrealdev.temporal.common.EncodedTemporalPayloads,
                    ): TemporalPayloads {
                        order.add("decode-B")
                        return TemporalPayloads(payloads.proto)
                    }
                }

            val chained = ChainedCodec(listOf(codec1, codec2))
            val payload = createPayload("test")

            chained.encode(TemporalPayloads.of(listOf(payload)))
            assertEquals(listOf("encode-A", "encode-B"), order)

            order.clear()
            chained.decode(
                com.surrealdev.temporal.common
                    .EncodedTemporalPayloads(TemporalPayloads.of(listOf(payload)).proto),
            )
            assertEquals(listOf("decode-B", "decode-A"), order)
        }

    @Test
    fun `ChainedCodec with compression and passthrough works end to end`() =
        runTest {
            val passthrough = NoOpCodec
            val compression = CompressionCodec(threshold = 10)
            val chained = ChainedCodec(listOf(compression, passthrough))

            val largeData = "z".repeat(500)
            val payload = createPayload(largeData)

            val encoded = chained.encode(TemporalPayloads.of(listOf(payload)))
            assertNotEquals(payload, encoded[0]) // Should be modified (compressed)

            val decoded = chained.decode(encoded)
            assertEquals(largeData, String(decoded[0].data))
        }

    @Test
    fun `ChainedCodec with empty list is no-op`() =
        runTest {
            val chained = ChainedCodec(emptyList())
            val payload = createPayload("test")

            val encoded = chained.encode(TemporalPayloads.of(listOf(payload)))
            assertEquals(payload, encoded[0])

            val decoded = chained.decode(encoded)
            assertEquals(payload, decoded[0])
        }
}
