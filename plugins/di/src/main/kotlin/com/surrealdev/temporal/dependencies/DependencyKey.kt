package com.surrealdev.temporal.dependencies

import kotlin.reflect.KClass

/**
 * Type-safe key for identifying dependencies.
 *
 * @param type The Kotlin class of the dependency
 * @param scope The scope defining where this dependency can be used
 * @param qualifier Optional qualifier to distinguish multiple instances of the same type
 */
data class DependencyKey<T : Any>(
    val type: KClass<T>,
    val scope: DependencyScope,
    val qualifier: String? = null,
) {
    override fun toString(): String =
        "DependencyKey(${type.simpleName}, $scope${qualifier?.let { ", qualifier=$it" } ?: ""})"
}

/**
 * Creates a DependencyKey for the reified type.
 */
inline fun <reified T : Any> dependencyKey(
    scope: DependencyScope,
    qualifier: String? = null,
): DependencyKey<T> = DependencyKey(T::class, scope, qualifier)
