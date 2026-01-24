package com.surrealdev.temporal.util

import java.util.concurrent.ConcurrentHashMap

/**
 * A type-safe map for storing arbitrary values with [AttributeKey]s.
 *
 * This provides a type-safe alternative to `Map<String, Any>` by ensuring that
 * values retrieved with a key always have the correct type.
 *
 * Usage:
 * ```kotlin
 * val attributes = Attributes()
 * val key = AttributeKey<String>("userId")
 *
 * attributes.put(key, "user-123")
 * val userId: String = attributes[key] // Type-safe retrieval
 * ```
 */
interface Attributes {
    /**
     * Gets the value associated with the key.
     *
     * @throws NoSuchElementException if the key is not present
     */
    operator fun <T : Any> get(key: AttributeKey<T>): T

    /**
     * Gets the value associated with the key, or null if not present.
     */
    fun <T : Any> getOrNull(key: AttributeKey<T>): T?

    /**
     * Checks if the key is present in the attributes.
     */
    operator fun contains(key: AttributeKey<*>): Boolean

    /**
     * Associates the value with the key.
     */
    fun <T : Any> put(
        key: AttributeKey<T>,
        value: T,
    )

    /**
     * Removes the value associated with the key.
     */
    fun <T : Any> remove(key: AttributeKey<T>)

    /**
     * Returns the value for the key if present, otherwise computes and stores it.
     *
     * This operation is atomic when using concurrent=true.
     */
    fun <T : Any> computeIfAbsent(
        key: AttributeKey<T>,
        block: () -> T,
    ): T

    /**
     * Returns all keys currently stored in the attributes.
     */
    val allKeys: List<AttributeKey<*>>
}

/**
 * Creates a new [Attributes] instance.
 *
 * @param concurrent If true, uses a [ConcurrentHashMap] for thread-safe access.
 *                   If false, uses a regular [HashMap].
 */
fun Attributes(concurrent: Boolean = false): Attributes =
    if (concurrent) {
        ConcurrentAttributesImpl()
    } else {
        AttributesImpl()
    }

/**
 * Non-concurrent implementation using a regular HashMap.
 */
internal class AttributesImpl : Attributes {
    private val map = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: AttributeKey<T>): T =
        map[key.name] as? T
            ?: throw NoSuchElementException("Key ${key.name} not found")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrNull(key: AttributeKey<T>): T? = map[key.name] as? T

    override fun contains(key: AttributeKey<*>): Boolean = key.name in map

    override fun <T : Any> put(
        key: AttributeKey<T>,
        value: T,
    ) {
        map[key.name] = value
    }

    override fun <T : Any> remove(key: AttributeKey<T>) {
        map.remove(key.name)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> computeIfAbsent(
        key: AttributeKey<T>,
        block: () -> T,
    ): T = map.getOrPut(key.name) { block() } as T

    override val allKeys: List<AttributeKey<*>>
        get() = map.keys.map { AttributeKey<Any>(name = it) }
}

/**
 * Concurrent implementation using a ConcurrentHashMap.
 */
internal class ConcurrentAttributesImpl : Attributes {
    private val map = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: AttributeKey<T>): T =
        map[key.name] as? T
            ?: throw NoSuchElementException("Key ${key.name} not found")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrNull(key: AttributeKey<T>): T? = map[key.name] as? T

    override fun contains(key: AttributeKey<*>): Boolean = map.containsKey(key.name)

    override fun <T : Any> put(
        key: AttributeKey<T>,
        value: T,
    ) {
        map[key.name] = value
    }

    override fun <T : Any> remove(key: AttributeKey<T>) {
        map.remove(key.name)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> computeIfAbsent(
        key: AttributeKey<T>,
        block: () -> T,
    ): T = map.computeIfAbsent(key.name) { block() } as T

    override val allKeys: List<AttributeKey<*>>
        get() = map.keys.map { AttributeKey<Any>(name = it) }
}
