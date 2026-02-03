package com.surrealdev.temporal.common

import java.time.Instant

/**
 * Type-safe search attribute key that carries metadata type information.
 *
 * Search attributes allow querying workflows based on custom attributes.
 * Each key has a specific type that determines how the value is indexed:
 *
 * - [Text]: Full-text searchable string
 * - [Keyword]: Exact-match string (for filtering/equality)
 * - [Int]: 64-bit integer
 * - [Double]: Floating point number
 * - [Bool]: Boolean value
 * - [Datetime]: Timestamp (stored as ISO 8601 string)
 * - [KeywordList]: List of exact-match strings
 *
 * Example:
 * ```kotlin
 * // Define reusable keys as constants
 * val CUSTOMER_ID = SearchAttributeKey.forKeyword("CustomerId")
 * val ORDER_COUNT = SearchAttributeKey.forInt("OrderCount")
 *
 * // Use in workflow options
 * val handle = client.startWorkflow<String>(
 *     workflowType = "OrderWorkflow",
 *     taskQueue = "orders",
 *     options = WorkflowStartOptions(
 *         searchAttributes = searchAttributes {
 *             CUSTOMER_ID to "cust-123"
 *             ORDER_COUNT to 42L
 *         }
 *     )
 * )
 * ```
 */
sealed class SearchAttributeKey<T>(
    val name: String,
    internal val metadataType: String,
) {
    /** Full-text searchable string. Use for fields that need fuzzy search. */
    class Text(
        name: String,
    ) : SearchAttributeKey<String>(name, "Text")

    /** Exact-match string. Use for IDs, categories, and equality filtering. */
    class Keyword(
        name: String,
    ) : SearchAttributeKey<String>(name, "Keyword")

    /** 64-bit integer. Use for counts, amounts, and numeric filtering. */
    class Int(
        name: String,
    ) : SearchAttributeKey<Long>(name, "Int")

    /** Floating point number. Use for prices, scores, and decimal values. */
    class Double(
        name: String,
    ) : SearchAttributeKey<kotlin.Double>(name, "Double")

    /** Boolean value. Use for flags and binary states. */
    class Bool(
        name: String,
    ) : SearchAttributeKey<Boolean>(name, "Bool")

    /** Timestamp. Accepts [Instant] and converts to ISO 8601 string for storage. */
    class Datetime(
        name: String,
    ) : SearchAttributeKey<Instant>(name, "Datetime")

    /** List of exact-match strings. Use for tags, categories, and multi-value fields. */
    class KeywordList(
        name: String,
    ) : SearchAttributeKey<List<String>>(name, "KeywordList")

    companion object {
        /** Creates a full-text searchable string key. */
        fun forText(name: String) = Text(name)

        /** Creates an exact-match string key. */
        fun forKeyword(name: String) = Keyword(name)

        /** Creates a 64-bit integer key. */
        fun forInt(name: String) = Int(name)

        /** Creates a floating point number key. */
        fun forDouble(name: String) = Double(name)

        /** Creates a boolean key. */
        fun forBool(name: String) = Bool(name)

        /** Creates a timestamp key. */
        fun forDatetime(name: String) = Datetime(name)

        /** Creates a list of exact-match strings key. */
        fun forKeywordList(name: String) = KeywordList(name)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SearchAttributeKey<*>) return false
        return name == other.name && metadataType == other.metadataType
    }

    override fun hashCode(): kotlin.Int {
        var result = name.hashCode()
        result = 31 * result + metadataType.hashCode()
        return result
    }

    override fun toString(): String = "SearchAttributeKey.$metadataType(\"$name\")"
}

/**
 * Type-safe pair of a search attribute key and its value.
 *
 * Created using the [to] infix function within [searchAttributes] DSL:
 * ```kotlin
 * searchAttributes {
 *     CUSTOMER_ID to "cust-123"  // Creates SearchAttributePair
 * }
 * ```
 */
data class SearchAttributePair<T>(
    val key: SearchAttributeKey<T>,
    val value: T?,
)

/**
 * Collection of typed search attributes.
 *
 * Created using the [searchAttributes] DSL function:
 * ```kotlin
 * val attrs = searchAttributes {
 *     SearchAttributeKey.forKeyword("CustomerId") to "cust-123"
 *     SearchAttributeKey.forInt("OrderCount") to 42L
 *     SearchAttributeKey.forBool("IsPremium") to true
 * }
 * ```
 */
class TypedSearchAttributes(
    val pairs: List<SearchAttributePair<*>>,
) {
    companion object {
        /** Empty search attributes. */
        val EMPTY = TypedSearchAttributes(emptyList())
    }

    /** Returns true if this contains no search attributes. */
    fun isEmpty(): Boolean = pairs.isEmpty()

    /** Returns true if this contains search attributes. */
    fun isNotEmpty(): Boolean = pairs.isNotEmpty()

    /** Returns the number of search attributes. */
    val size: Int get() = pairs.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TypedSearchAttributes) return false
        return pairs == other.pairs
    }

    override fun hashCode(): Int = pairs.hashCode()

    override fun toString(): String = "TypedSearchAttributes(${pairs.joinToString()})"
}

/**
 * DSL function for creating typed search attributes.
 *
 * Example:
 * ```kotlin
 * val attrs = searchAttributes {
 *     SearchAttributeKey.forKeyword("CustomerId") to "cust-123"
 *     SearchAttributeKey.forInt("OrderCount") to 42L
 *     SearchAttributeKey.forBool("IsPremium") to true
 *     SearchAttributeKey.forDatetime("CreatedAt") to Instant.now()
 * }
 * ```
 */
fun searchAttributes(block: SearchAttributesBuilder.() -> Unit): TypedSearchAttributes =
    SearchAttributesBuilder().apply(block).build()

/**
 * Builder for [TypedSearchAttributes].
 *
 * Use the [to] infix function to add key-value pairs:
 * ```kotlin
 * SearchAttributeKey.forKeyword("CustomerId") to "cust-123"
 * ```
 */
class SearchAttributesBuilder {
    private val pairs = mutableListOf<SearchAttributePair<*>>()

    /**
     * Adds a search attribute key-value pair.
     *
     * @param value The value for this attribute (nullable to support removal)
     */
    infix fun <T> SearchAttributeKey<T>.to(value: T?) {
        pairs.add(SearchAttributePair(this, value))
    }

    /** Builds the [TypedSearchAttributes] from accumulated pairs. */
    fun build(): TypedSearchAttributes = TypedSearchAttributes(pairs.toList())
}
