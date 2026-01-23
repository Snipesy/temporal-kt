package com.surrealdev.temporal.dependencies

/**
 * Provider that creates instances of a dependency.
 *
 * @param T The type of dependency this provider creates
 */
class DependencyProvider<T : Any>(
    val key: DependencyKey<T>,
    val factory: DependencyContext.() -> T,
) {
    override fun toString(): String = "DependencyProvider($key)"
}
