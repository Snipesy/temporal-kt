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
 * Resolves a dependency by type, searching across all scopes.
 *
 * Checks WORKFLOW_SAFE first, then ACTIVITY_ONLY. Scope validation still
 * applies — resolving an ACTIVITY_ONLY dependency in a workflow context will throw.
 *
 * Usage inside a `provide` / `workflowSafe` / `activityOnly` factory:
 * ```kotlin
 * activityOnly<HttpClient> {
 *     val config = resolve<AppConfig>()
 *     HttpClient(config.baseUrl)
 * }
 * ```
 *
 * @throws MissingDependencyException if no provider is registered for the type
 * @throws IllegalDependencyScopeException if the resolved dependency's scope is not allowed
 */
inline fun <reified T : Any> DependencyContext.resolve(qualifier: String? = null): T =
    resolveOrNull<T>(qualifier)
        ?: throw MissingDependencyException(
            "No provider registered for ${T::class.simpleName}" +
                (qualifier?.let { " (qualifier: $it)" } ?: ""),
        )

/**
 * Resolves a dependency by type, or null if not registered.
 *
 * Like [resolve], searches WORKFLOW_SAFE first, then ACTIVITY_ONLY.
 *
 * @throws IllegalDependencyScopeException if the resolved dependency's scope is not allowed
 */
inline fun <reified T : Any> DependencyContext.resolveOrNull(qualifier: String? = null): T? =
    getOrNull(DependencyKey(T::class, DependencyScope.WORKFLOW_SAFE, qualifier))
        ?: getOrNull(DependencyKey(T::class, DependencyScope.ACTIVITY_ONLY, qualifier))

/**
 * Exception thrown when attempting to use an ACTIVITY_ONLY dependency in a workflow.
 */
class IllegalDependencyScopeException(
    message: String,
) : IllegalStateException(message)

/**
 * Exception thrown when a circular dependency is detected during resolution.
 */
class CircularDependencyException(
    message: String,
) : IllegalStateException(message)
