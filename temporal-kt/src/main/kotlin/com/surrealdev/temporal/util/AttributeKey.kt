package com.surrealdev.temporal.util

/**
 * A type-safe key for storing values in an [Attributes] map.
 *
 * Usage:
 * ```kotlin
 * val UserIdKey = AttributeKey<String>("userId")
 * val CounterKey = AttributeKey<Int>("counter")
 * ```
 *
 * @param T The type of value stored with this key
 * @param name The unique identifier for this key
 */
data class AttributeKey<T : Any>(
    val name: String,
) {
    override fun toString(): String = "AttributeKey($name)"
}
