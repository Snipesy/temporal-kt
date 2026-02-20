package com.surrealdev.temporal.dependencies

/**
 * Per-execution context for resolving dependencies.
 *
 * Created once per workflow run or activity task. Dependencies are singletons
 * within the owning [DependencyRegistry] — the same instance is returned
 * across all contexts created from that registry.
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
}

/**
 * Internal implementation of DependencyContext.
 *
 * Supports hierarchical lookup: primary registry is checked first, then fallback.
 * This allows task-queue-level dependencies to override application-level ones.
 *
 * All instances returned are registry-level singletons.
 */
internal class DependencyContextImpl(
    private val registry: DependencyRegistry,
    internal val isWorkflowContext: Boolean,
    private val fallbackRegistry: DependencyRegistry? = null,
) : DependencyContext {
    override fun <T : Any> get(key: DependencyKey<T>): T {
        // Resolve from primary registry, then fallback — check provider existence before scope
        val resolving =
            when {
                registry.hasProvider(key) -> registry
                fallbackRegistry != null && fallbackRegistry.hasProvider(key) -> fallbackRegistry
                else -> throw MissingDependencyException("No provider registered for $key")
            }

        // Validate scope only after confirming the provider actually exists
        if (isWorkflowContext && key.scope == DependencyScope.ACTIVITY_ONLY) {
            throw IllegalDependencyScopeException(
                "Cannot use ACTIVITY_ONLY dependency ${key.type.simpleName} in workflow context",
            )
        }

        return resolving.getOrCreate(key, this)
    }

    override fun <T : Any> getOrNull(key: DependencyKey<T>): T? =
        try {
            get(key)
        } catch (_: MissingDependencyException) {
            null
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
