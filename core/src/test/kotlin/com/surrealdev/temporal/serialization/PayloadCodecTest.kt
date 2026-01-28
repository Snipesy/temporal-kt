package com.surrealdev.temporal.serialization

import com.google.protobuf.ByteString
import com.surrealdev.temporal.serialization.codec.ChainedCodec
import com.surrealdev.temporal.serialization.codec.CompressionCodec
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PayloadCodecTest {
    private fun createPayload(
        data: String,
        metadata: Map<String, String> = emptyMap(),
    ): Payload {
        val builder = Payload.newBuilder().setData(ByteString.copyFromUtf8(data))
        metadata.forEach { (k, v) ->
            builder.putMetadata(k, ByteString.copyFromUtf8(v))
        }
        return builder.build()
    }

    @Test
    fun `NoOpCodec passes payloads through unchanged`() =
        runTest {
            val payload = createPayload("test data")
            val encoded = NoOpCodec.encode(listOf(payload))
            assertEquals(listOf(payload), encoded)

            val decoded = NoOpCodec.decode(encoded)
            assertEquals(listOf(payload), decoded)
        }

    @Test
    fun `CompressionCodec compresses large payloads`() =
        runTest {
            val codec = CompressionCodec(threshold = 10)
            val largeData = "x".repeat(1000)
            val payload = createPayload(largeData)

            val encoded = codec.encode(listOf(payload))
            val encodedPayload = encoded.single()

            // Should be compressed (smaller)
            assertTrue(encodedPayload.data.size() < payload.data.size())
            // Should have encoding metadata
            assertEquals("binary/gzip", encodedPayload.metadataMap["encoding"]?.toStringUtf8())

            // Decode should restore original
            val decoded = codec.decode(encoded)
            assertEquals(largeData, decoded.single().data.toStringUtf8())
            // Should remove encoding metadata
            assertTrue(decoded.single().metadataMap["encoding"] == null)
        }

    @Test
    fun `CompressionCodec passes through small payloads`() =
        runTest {
            val codec = CompressionCodec(threshold = 1000)
            val smallData = "small"
            val payload = createPayload(smallData)

            val encoded = codec.encode(listOf(payload))
            // Should be unchanged
            assertEquals(payload, encoded.single())
        }

    @Test
    fun `CompressionCodec skips compression when it increases size`() =
        runTest {
            val codec = CompressionCodec(threshold = 1)
            // Small incompressible data
            val payload = createPayload("abc")

            val encoded = codec.encode(listOf(payload))
            // Should be unchanged (compression would increase size)
            assertEquals(payload, encoded.single())
        }

    @Test
    fun `CompressionCodec preserves existing metadata`() =
        runTest {
            val codec = CompressionCodec(threshold = 10)
            val largeData = "y".repeat(500)
            val payload = createPayload(largeData, mapOf("custom-key" to "custom-value"))

            val encoded = codec.encode(listOf(payload))
            val encodedPayload = encoded.single()

            // Should preserve custom metadata
            assertEquals("custom-value", encodedPayload.metadataMap["custom-key"]?.toStringUtf8())
            // Should add encoding metadata
            assertEquals("binary/gzip", encodedPayload.metadataMap["encoding"]?.toStringUtf8())

            // After decode, custom metadata should remain, encoding should be removed
            val decoded = codec.decode(encoded)
            assertEquals("custom-value", decoded.single().metadataMap["custom-key"]?.toStringUtf8())
            assertTrue(decoded.single().metadataMap["encoding"] == null)
        }

    @Test
    fun `CompressionCodec passes through non-gzip payloads on decode`() =
        runTest {
            val codec = CompressionCodec()
            val payload = createPayload("test", mapOf("encoding" to "binary/other"))

            val decoded = codec.decode(listOf(payload))
            // Should pass through unchanged
            assertEquals(payload, decoded.single())
        }

    @Test
    fun `ChainedCodec applies codecs in correct order for encode`() =
        runTest {
            val order = mutableListOf<String>()

            val codec1 =
                object : PayloadCodec {
                    override suspend fun encode(payloads: List<Payload>): List<Payload> {
                        order.add("encode-A")
                        return payloads
                    }

                    override suspend fun decode(payloads: List<Payload>): List<Payload> {
                        order.add("decode-A")
                        return payloads
                    }
                }

            val codec2 =
                object : PayloadCodec {
                    override suspend fun encode(payloads: List<Payload>): List<Payload> {
                        order.add("encode-B")
                        return payloads
                    }

                    override suspend fun decode(payloads: List<Payload>): List<Payload> {
                        order.add("decode-B")
                        return payloads
                    }
                }

            val chained = ChainedCodec(listOf(codec1, codec2))
            val payload = createPayload("test")

            chained.encode(listOf(payload))
            assertEquals(listOf("encode-A", "encode-B"), order)

            order.clear()
            chained.decode(listOf(payload))
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

            val encoded = chained.encode(listOf(payload))
            assertNotEquals(payload, encoded.single()) // Should be modified (compressed)

            val decoded = chained.decode(encoded)
            assertEquals(largeData, decoded.single().data.toStringUtf8())
        }

    @Test
    fun `ChainedCodec with empty list is no-op`() =
        runTest {
            val chained = ChainedCodec(emptyList())
            val payload = createPayload("test")

            val encoded = chained.encode(listOf(payload))
            assertEquals(listOf(payload), encoded)

            val decoded = chained.decode(encoded)
            assertEquals(listOf(payload), decoded)
        }
}
