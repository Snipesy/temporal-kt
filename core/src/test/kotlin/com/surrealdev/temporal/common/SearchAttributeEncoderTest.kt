package com.surrealdev.temporal.common

import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SearchAttributeEncoder].
 *
 * These tests verify that search attributes are encoded correctly with
 * proper type metadata for Temporal indexing.
 */
class SearchAttributeEncoderTest {
    private val serializer = KotlinxJsonSerializer.default()

    @Test
    fun `encodes keyword attribute with correct type metadata`() {
        val attrs =
            searchAttributes {
                SearchAttributeKey.forKeyword("CustomerId") to "cust-123"
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(1, encoded.size)
        assertTrue(encoded.containsKey("CustomerId"))

        val payload = encoded["CustomerId"]!!
        assertEquals("Keyword", payload.metadataMap["type"]?.toStringUtf8())
    }

    @Test
    fun `encodes int attribute with correct type metadata`() {
        val attrs =
            searchAttributes {
                SearchAttributeKey.forInt("OrderCount") to 42L
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(1, encoded.size)
        assertTrue(encoded.containsKey("OrderCount"))

        val payload = encoded["OrderCount"]!!
        assertEquals("Int", payload.metadataMap["type"]?.toStringUtf8())
    }

    @Test
    fun `encodes double attribute with correct type metadata`() {
        val attrs =
            searchAttributes {
                SearchAttributeKey.forDouble("Score") to 98.5
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(1, encoded.size)
        assertTrue(encoded.containsKey("Score"))

        val payload = encoded["Score"]!!
        assertEquals("Double", payload.metadataMap["type"]?.toStringUtf8())
    }

    @Test
    fun `encodes bool attribute with correct type metadata`() {
        val attrs =
            searchAttributes {
                SearchAttributeKey.forBool("IsPremium") to true
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(1, encoded.size)
        assertTrue(encoded.containsKey("IsPremium"))

        val payload = encoded["IsPremium"]!!
        assertEquals("Bool", payload.metadataMap["type"]?.toStringUtf8())
    }

    @Test
    fun `encodes datetime attribute with correct type metadata`() {
        val timestamp = Instant.parse("2024-01-15T10:30:00Z")
        val attrs =
            searchAttributes {
                SearchAttributeKey.forDatetime("CreatedAt") to timestamp
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(1, encoded.size)
        assertTrue(encoded.containsKey("CreatedAt"))

        val payload = encoded["CreatedAt"]!!
        assertEquals("Datetime", payload.metadataMap["type"]?.toStringUtf8())
        // The value should be the ISO 8601 string
        val payloadValue = payload.data.toStringUtf8()
        assertTrue(payloadValue.contains("2024-01-15T10:30:00Z"))
    }

    @Test
    fun `encodes text attribute with correct type metadata`() {
        val attrs =
            searchAttributes {
                SearchAttributeKey.forText("Description") to "Full text searchable"
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(1, encoded.size)
        assertTrue(encoded.containsKey("Description"))

        val payload = encoded["Description"]!!
        assertEquals("Text", payload.metadataMap["type"]?.toStringUtf8())
    }

    @Test
    fun `encodes keyword list attribute with correct type metadata`() {
        val attrs =
            searchAttributes {
                SearchAttributeKey.forKeywordList("Tags") to listOf("urgent", "vip")
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(1, encoded.size)
        assertTrue(encoded.containsKey("Tags"))

        val payload = encoded["Tags"]!!
        assertEquals("KeywordList", payload.metadataMap["type"]?.toStringUtf8())
    }

    @Test
    fun `encodes null value with binary null encoding without type metadata`() {
        val attrs =
            searchAttributes {
                SearchAttributeKey.forKeyword("CustomerId") to null
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(1, encoded.size)
        assertTrue(encoded.containsKey("CustomerId"))

        val payload = encoded["CustomerId"]!!
        assertEquals("binary/null", payload.metadataMap["encoding"]?.toStringUtf8())
        // Null values should NOT have type metadata
        assertTrue(!payload.metadataMap.containsKey("type"))
    }

    @Test
    fun `encodes multiple attributes correctly`() {
        val timestamp = Instant.now()
        val attrs =
            searchAttributes {
                SearchAttributeKey.forKeyword("CustomerId") to "cust-123"
                SearchAttributeKey.forInt("OrderCount") to 42L
                SearchAttributeKey.forBool("IsPremium") to true
                SearchAttributeKey.forDatetime("CreatedAt") to timestamp
            }

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertEquals(4, encoded.size)
        assertEquals("Keyword", encoded["CustomerId"]!!.metadataMap["type"]?.toStringUtf8())
        assertEquals("Int", encoded["OrderCount"]!!.metadataMap["type"]?.toStringUtf8())
        assertEquals("Bool", encoded["IsPremium"]!!.metadataMap["type"]?.toStringUtf8())
        assertEquals("Datetime", encoded["CreatedAt"]!!.metadataMap["type"]?.toStringUtf8())
    }

    @Test
    fun `encodes empty attributes returns empty map`() {
        val attrs = TypedSearchAttributes.EMPTY

        val encoded = SearchAttributeEncoder.encode(attrs, serializer)

        assertTrue(encoded.isEmpty())
    }

    @Test
    fun `DSL builds correct search attributes`() {
        val attrs =
            searchAttributes {
                SearchAttributeKey.forKeyword("CustomerId") to "cust-123"
                SearchAttributeKey.forInt("OrderCount") to 42L
            }

        assertEquals(2, attrs.size)
        assertTrue(attrs.isNotEmpty())

        val pairs = attrs.pairs
        assertEquals("CustomerId", pairs[0].key.name)
        assertEquals("cust-123", pairs[0].value)
        assertEquals("OrderCount", pairs[1].key.name)
        assertEquals(42L, pairs[1].value)
    }

    @Test
    fun `SearchAttributeKey equals and hashCode work correctly`() {
        val key1 = SearchAttributeKey.forKeyword("CustomerId")
        val key2 = SearchAttributeKey.forKeyword("CustomerId")
        val key3 = SearchAttributeKey.forInt("CustomerId")

        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())

        // Different type, same name - should not be equal
        assertTrue(key1 != key3)
    }

    @Test
    fun `SearchAttributeKey toString returns readable format`() {
        val key = SearchAttributeKey.forKeyword("CustomerId")
        assertEquals("SearchAttributeKey.Keyword(\"CustomerId\")", key.toString())
    }
}
