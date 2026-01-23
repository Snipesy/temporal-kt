package com.surrealdev.temporal.dependencies

/**
 * Per-execution context for resolving dependencies.
 *
 * Created once per workflow run or activity task and provides lazy resolution
 * of dependencies with caching.
 */
interface DependencyContext {
    /**
     * Resolves a dependency by key.
     *
     * @throws MissingDependencyException if not registered
     * @throws IllegalDependencyScopeException if used in wrong context
     */
    fun <T : Any> get(key: DependencyKey<T>): T

    /**
     * Resolves a dependency by key, or null if not registered.
     */
    fun <T : Any> getOrNull(key: DependencyKey<T>): T?

    /**
     * Closes the context and releases resources.
     */
    fun close()
}

/**
 * Internal implementation of DependencyContext.
 *
 * Supports hierarchical lookup: primary registry is checked first, then fallback.
 * This allows task-queue-level dependencies to override application-level ones.
 */
internal class DependencyContextImpl(
    private val registry: DependencyRegistry,
    internal val isWorkflowContext: Boolean,
    private val fallbackRegistry: DependencyRegistry? = null,
) : DependencyContext {
    // Cache resolved dependencies to ensure singleton behavior per scope
    private val cache = mutableMapOf<DependencyKey<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: DependencyKey<T>): T {
        // Check cache first
        cache[key]?.let { return it as T }

        // Validate scope
        if (isWorkflowContext && key.scope == DependencyScope.ACTIVITY_ONLY) {
            throw IllegalDependencyScopeException(
                "Cannot use ACTIVITY_ONLY dependency ${key.type.simpleName} in workflow context",
            )
        }

        // Get provider - check primary registry first, then fallback
        val provider =
            registry.getProvider(key)
                ?: fallbackRegistry?.getProvider(key)
                ?: throw MissingDependencyException("No provider registered for $key")

        val instance = provider.factory(this)
        cache[key] = instance
        return instance
    }

    override fun <T : Any> getOrNull(key: DependencyKey<T>): T? =
        try {
            get(key)
        } catch (_: MissingDependencyException) {
            null
        }

    override fun close() {
        // Future: Call close() on any AutoCloseable dependencies
        cache.clear()
    }
}

/**
 * Exception thrown when a dependency is not registered.
 */
class MissingDependencyException(
    message: String,
) : IllegalStateException(message)

/**
 * Exception thrown when attempting to use an ACTIVITY_ONLY dependency in a workflow.
 */
class IllegalDependencyScopeException(
    message: String,
) : IllegalStateException(message)
