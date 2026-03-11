package com.surrealdev.temporal.dependencies

import com.surrealdev.temporal.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Attribute key for storing a [HealthCheckRegistry] in application attributes.
 *
 * The DI plugin creates and populates the registry; the health plugin reads from it.
 */
val HealthCheckRegistryKey = AttributeKey<HealthCheckRegistry>("HealthCheckRegistry")

/**
 * Registry for named health checks contributed by the DI plugin.
 *
 * Each check is a `() -> Boolean` lambda. [runAll] executes every registered check
 * and returns a list of [ResourceHealthCheck] results, catching exceptions.
 */
class HealthCheckRegistry {
    private val checks = ConcurrentHashMap<String, () -> Boolean>()

    fun register(
        name: String,
        check: () -> Boolean,
    ) {
        checks[name] = check
    }

    fun unregister(name: String) {
        checks.remove(name)
    }

    fun runAll(): List<ResourceHealthCheck> =
        checks.map { (name, check) ->
            try {
                ResourceHealthCheck(name, healthy = check())
            } catch (e: Exception) {
                ResourceHealthCheck(name, healthy = false, error = e.message)
            }
        }
}

/**
 * Health check result for a single named resource (e.g., a database pool).
 */
data class ResourceHealthCheck(
    val name: String,
    val healthy: Boolean,
    val error: String? = null,
)
