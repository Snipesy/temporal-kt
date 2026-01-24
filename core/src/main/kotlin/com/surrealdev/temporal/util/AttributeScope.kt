package com.surrealdev.temporal.util

/**
 * Interface for objects that have attributes with optional hierarchical lookup.
 *
 * This enables a chain of attribute scopes where each level can have its own
 * attributes, with lookup falling back to parent scopes when not found locally.
 *
 * The hierarchy typically looks like:
 * ```
 * Application (root)
 *   └── TaskQueue
 *         ├── WorkflowExecution
 *         └── ActivityExecution
 * ```
 *
 * Future extensions could include nested task queues, routing layers, etc.
 *
 * Usage for plugins:
 * ```kotlin
 * // Register at application level
 * app.attributes.put(MyPluginKey, myPlugin)
 *
 * // Override at task queue level
 * taskQueue.attributes.put(MyPluginKey, taskQueueSpecificPlugin)
 *
 * // Lookup with hierarchical fallback
 * val plugin = workflowContext.getAttributeOrNull(MyPluginKey)
 * // Returns task queue version if set, otherwise app version
 * ```
 */
interface AttributeScope {
    /**
     * This scope's own attributes.
     *
     * Attributes stored here are local to this scope and don't affect parent scopes.
     */
    val attributes: Attributes

    /**
     * Parent scope for hierarchical lookup, or null if this is the root scope.
     *
     * When looking up attributes, if not found locally, the parent scope is checked.
     */
    val parentScope: AttributeScope?
}

/**
 * Looks up an attribute value with hierarchical fallback.
 *
 * First checks this scope's local attributes, then walks up the parent chain
 * until the attribute is found or the root is reached.
 *
 * @param key The attribute key to look up
 * @return The attribute value, or null if not found in any scope
 */
fun <T : Any> AttributeScope.getAttributeOrNull(key: AttributeKey<T>): T? {
    // Check local attributes first
    attributes.getOrNull(key)?.let { return it }
    // Fall back to parent
    return parentScope?.getAttributeOrNull(key)
}

/**
 * Looks up an attribute value with hierarchical fallback.
 *
 * @param key The attribute key to look up
 * @return The attribute value
 * @throws IllegalStateException if the attribute is not found in any scope
 */
fun <T : Any> AttributeScope.getAttribute(key: AttributeKey<T>): T =
    getAttributeOrNull(key)
        ?: throw IllegalStateException("Attribute '${key.name}' not found in scope hierarchy")

/**
 * Checks if an attribute exists anywhere in the scope hierarchy.
 *
 * @param key The attribute key to check
 * @return true if the attribute exists in this scope or any parent scope
 */
fun <T : Any> AttributeScope.hasAttribute(key: AttributeKey<T>): Boolean = getAttributeOrNull(key) != null

/**
 * Collects all values for an attribute from the entire scope hierarchy.
 *
 * Useful when you want to aggregate values from all levels rather than
 * just getting the first match.
 *
 * @param key The attribute key to collect
 * @return List of values from most specific (this scope) to least specific (root)
 */
fun <T : Any> AttributeScope.collectAttributes(key: AttributeKey<T>): List<T> {
    val result = mutableListOf<T>()
    var current: AttributeScope? = this
    while (current != null) {
        current.attributes.getOrNull(key)?.let { result.add(it) }
        current = current.parentScope
    }
    return result
}

/**
 * Simple implementation of [AttributeScope] for wrapping attributes with a parent.
 *
 * Useful for creating scope hierarchies from existing [Attributes] instances.
 */
class SimpleAttributeScope(
    override val attributes: Attributes,
    override val parentScope: AttributeScope? = null,
) : AttributeScope
