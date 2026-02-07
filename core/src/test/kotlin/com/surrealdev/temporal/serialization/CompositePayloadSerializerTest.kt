package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.serialization.converter.ByteArrayPayloadConverter
import com.surrealdev.temporal.serialization.converter.JsonPayloadConverter
import com.surrealdev.temporal.serialization.converter.NullPayloadConverter
import kotlinx.serialization.Serializable
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompositePayloadSerializerTest {
    @Serializable
    data class TestData(
        val name: String,
        val count: Int,
    )

    // ================================================================
    // Default chain tests
    // ================================================================

    @Test
    fun `default chain serializes null values with binary null encoding`() {
        val serializer = CompositePayloadSerializer.default()
        val payload = serializer.serialize(typeOf<String?>(), null)

        assertEquals(TemporalPayload.ENCODING_NULL, payload.encoding)
    }

    @Test
    fun `default chain serializes data classes as JSON`() {
        val serializer = CompositePayloadSerializer.default()
        val payload = serializer.serialize(typeOf<TestData>(), TestData("hello", 42))

        assertEquals(TemporalPayload.ENCODING_JSON, payload.encoding)
        val json = String(payload.data)
        assertTrue(json.contains("\"hello\""))
        assertTrue(json.contains("42"))
    }

    @Test
    fun `default chain deserializes null payload to nullable type`() {
        val serializer = CompositePayloadSerializer.default()
        val payload = serializer.serialize(typeOf<String?>(), null)

        val result = serializer.deserialize(typeOf<String?>(), payload)
        assertNull(result)
    }

    @Test
    fun `default chain round-trips data class through JSON`() {
        val serializer = CompositePayloadSerializer.default()
        val original = TestData("world", 99)
        val payload = serializer.serialize(typeOf<TestData>(), original)
        val result = serializer.deserialize(typeOf<TestData>(), payload)

        assertEquals(original, result)
    }

    @Test
    fun `default chain round-trips primitive types`() {
        val serializer = CompositePayloadSerializer.default()

        val intPayload = serializer.serialize(typeOf<Int>(), 42)
        assertEquals(42, serializer.deserialize(typeOf<Int>(), intPayload))

        val stringPayload = serializer.serialize(typeOf<String>(), "hello")
        assertEquals("hello", serializer.deserialize(typeOf<String>(), stringPayload))

        val boolPayload = serializer.serialize(typeOf<Boolean>(), true)
        assertEquals(true, serializer.deserialize(typeOf<Boolean>(), boolPayload))
    }

    @Test
    fun `default chain round-trips lists`() {
        val serializer = CompositePayloadSerializer.default()
        val list = listOf("a", "b", "c")
        val payload = serializer.serialize(typeOf<List<String>>(), list)
        val result = serializer.deserialize(typeOf<List<String>>(), payload)

        assertEquals(list, result)
    }

    // ================================================================
    // First-match-wins serialization order
    // ================================================================

    @Test
    fun `null value is handled by NullPayloadConverter not JSON`() {
        val serializer = CompositePayloadSerializer.default()
        val payload = serializer.serialize(typeOf<TestData?>(), null)

        // Should be binary/null, NOT json/plain
        assertEquals(TemporalPayload.ENCODING_NULL, payload.encoding)
        assertEquals(0, payload.dataSize)
    }

    @Test
    fun `byteArray converter handles ByteArray when in chain`() {
        val serializer =
            CompositePayloadSerializer(
                listOf(
                    NullPayloadConverter,
                    ByteArrayPayloadConverter,
                    JsonPayloadConverter.default(),
                ),
            )

        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val payload = serializer.serialize(typeOf<ByteArray>(), bytes)

        assertEquals(TemporalPayload.ENCODING_BINARY, payload.encoding)
        assertEquals(bytes.toList(), payload.data.toList())
    }

    @Test
    fun `non-byteArray falls through to JSON when byteArray is in chain`() {
        val serializer =
            CompositePayloadSerializer(
                listOf(
                    NullPayloadConverter,
                    ByteArrayPayloadConverter,
                    JsonPayloadConverter.default(),
                ),
            )

        val payload = serializer.serialize(typeOf<TestData>(), TestData("test", 1))
        assertEquals(TemporalPayload.ENCODING_JSON, payload.encoding)
    }

    // ================================================================
    // Encoding-based deserialization lookup
    // ================================================================

    @Test
    fun `deserialization uses encoding-based lookup for binary null`() {
        val serializer = CompositePayloadSerializer.default()
        val nullPayload =
            TemporalPayload.create(
                mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_NULL)),
            )

        val result = serializer.deserialize(typeOf<String?>(), nullPayload)
        assertNull(result)
    }

    @Test
    fun `deserialization uses encoding-based lookup for binary plain`() {
        val serializer =
            CompositePayloadSerializer(
                listOf(
                    NullPayloadConverter,
                    ByteArrayPayloadConverter,
                    JsonPayloadConverter.default(),
                ),
            )

        val bytes = byteArrayOf(10, 20, 30)
        val binaryPayload =
            TemporalPayload.create(
                bytes,
                mapOf(
                    TemporalPayload.METADATA_ENCODING to
                        TemporalByteString.fromUtf8(TemporalPayload.ENCODING_BINARY),
                ),
            )

        val result = serializer.deserialize(typeOf<ByteArray>(), binaryPayload) as ByteArray
        assertEquals(bytes.toList(), result.toList())
    }

    // ================================================================
    // Error cases
    // ================================================================

    @Test
    fun `deserialization throws for unknown encoding`() {
        val serializer = CompositePayloadSerializer.default()
        val payload =
            TemporalPayload.create(
                byteArrayOf(1, 2, 3),
                mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8("binary/protobuf")),
            )

        val ex =
            assertFailsWith<PayloadSerializationException> {
                serializer.deserialize(typeOf<String>(), payload)
            }
        assertTrue(ex.message!!.contains("binary/protobuf"))
    }

    @Test
    fun `deserialization falls back to last converter when encoding metadata missing`() {
        val serializer = CompositePayloadSerializer.default()
        // Payload with JSON data but no encoding metadata — should fall back to JSON converter
        val jsonBytes = "\"hello\"".toByteArray()
        val payload = TemporalPayload.create(jsonBytes, emptyMap<String, TemporalByteString>())

        val result = serializer.deserialize(typeOf<String>(), payload)
        assertEquals("hello", result)
    }

    @Test
    fun `deserialization throws when no converters and encoding missing`() {
        val serializer = CompositePayloadSerializer(emptyList())
        val payload = TemporalPayload.create(byteArrayOf(1, 2, 3), emptyMap<String, TemporalByteString>())

        assertFailsWith<PayloadSerializationException> {
            serializer.deserialize(typeOf<String>(), payload)
        }
    }

    @Test
    fun `serialization throws when no converter can handle value`() {
        // Empty chain — nothing can serialize
        val serializer = CompositePayloadSerializer(emptyList())

        assertFailsWith<PayloadSerializationException> {
            serializer.serialize(typeOf<String>(), "hello")
        }
    }

    @Test
    fun `deserializing null to non-nullable type throws`() {
        val serializer = CompositePayloadSerializer.default()
        val nullPayload = serializer.serialize(typeOf<String?>(), null)

        assertFailsWith<PayloadSerializationException> {
            serializer.deserialize(typeOf<String>(), nullPayload)
        }
    }

    // ================================================================
    // Custom converter in chain
    // ================================================================

    @Test
    fun `custom converter is used when it can handle the value`() {
        val customConverter =
            object : PayloadConverter {
                override val encoding = "custom/test"

                override fun toPayload(
                    typeInfo: KType,
                    value: Any?,
                ): TemporalPayload? {
                    if (value !is TestData) return null
                    return TemporalPayload.create(
                        mapOf(
                            TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8("custom/test"),
                        ),
                    ) { stream ->
                        stream.write("${value.name}:${value.count}".toByteArray())
                    }
                }

                override fun fromPayload(
                    typeInfo: KType,
                    payload: TemporalPayload,
                ): Any {
                    val parts = String(payload.data).split(":")
                    return TestData(parts[0], parts[1].toInt())
                }
            }

        val serializer =
            CompositePayloadSerializer(
                listOf(
                    NullPayloadConverter,
                    customConverter,
                    JsonPayloadConverter.default(),
                ),
            )

        val original = TestData("custom", 7)
        val payload = serializer.serialize(typeOf<TestData>(), original)

        // Should use custom converter, not JSON
        assertEquals("custom/test", payload.encoding)

        // Round-trip
        val result = serializer.deserialize(typeOf<TestData>(), payload)
        assertEquals(original, result)
    }

    // ================================================================
    // withJson factory
    // ================================================================

    @Test
    fun `withJson creates chain with null and custom JSON`() {
        val serializer = CompositePayloadSerializer.withJson(JsonPayloadConverter.default())

        // Null works
        val nullPayload = serializer.serialize(typeOf<String?>(), null)
        assertEquals(TemporalPayload.ENCODING_NULL, nullPayload.encoding)

        // JSON works
        val jsonPayload = serializer.serialize(typeOf<TestData>(), TestData("test", 1))
        assertEquals(TemporalPayload.ENCODING_JSON, jsonPayload.encoding)
    }
}
