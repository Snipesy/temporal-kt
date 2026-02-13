package com.surrealdev.temporal.dependencies.context

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.util.AttributeKey
import com.surrealdev.temporal.util.ExecutionScope
import com.surrealdev.temporal.workflow.WorkflowContext

/**
 * Key for storing [PropagatedContext] on an [ExecutionScope]'s attributes.
 */
val PropagatedContextKey = AttributeKey<PropagatedContext>("PropagatedContext")

/**
 * Holds propagated context values as raw [TemporalPayload] entries.
 *
 * Stored on [ExecutionScope.attributes] so it survives `launchHandler` scope creation
 * for signal/update handlers (same pattern as the DI plugin's `DependencyContext`).
 *
 * Values are lazily deserialized when accessed via the `context<T>()` extension functions.
 */
class PropagatedContext(
    private val values: Map<String, TemporalPayload>,
) {
    /**
     * Gets the raw payload for a context entry, or null if not present.
     */
    fun getRaw(name: String): TemporalPayload? = values[name]

    /**
     * Returns all entries as a map suitable for merging into outbound headers.
     */
    fun toHeaderMap(): Map<String, TemporalPayload> = values

    /**
     * Creates a new [PropagatedContext] with additional entries merged in.
     * New entries override existing ones with the same name.
     */
    fun mergedWith(other: Map<String, TemporalPayload>): PropagatedContext = PropagatedContext(values + other)
}

// =============================================================================
// Access Extensions
// =============================================================================

/**
 * Reads a propagated context value from the workflow's execution scope.
 *
 * The value is deserialized from the raw [TemporalPayload] stored in headers
 * using the workflow's [PayloadSerializer].
 *
 * Usage:
 * ```kotlin
 * @WorkflowRun
 * suspend fun WorkflowContext.run(): String {
 *     val tenant = context<Tenant>("tenantId")
 *     return tenant.name
 * }
 * ```
 *
 * @param T The expected type of the context value
 * @param name The context entry name (must match the name used in [ContextPropagationConfig])
 * @return The deserialized context value
 * @throws IllegalStateException if ContextPropagation plugin is not installed or the entry is missing
 */
inline fun <reified T> WorkflowContext.context(name: String): T {
    val scope =
        this as? ExecutionScope
            ?: error("WorkflowContext does not support context propagation (not an ExecutionScope)")
    val propagated =
        scope.attributes.getOrNull(PropagatedContextKey)
            ?: error("ContextPropagation plugin not installed")
    val raw =
        propagated.getRaw(name)
            ?: error("No propagated context entry '$name'")
    return serializer.deserialize<T>(raw)
}

/**
 * Reads a propagated context value from the activity's execution scope.
 *
 * Usage:
 * ```kotlin
 * @Activity
 * suspend fun ActivityContext.process(): String {
 *     val tenant = context<Tenant>("tenantId")
 *     return tenant.name
 * }
 * ```
 *
 * @param T The expected type of the context value
 * @param name The context entry name (must match the name used in [ContextPropagationConfig])
 * @return The deserialized context value
 * @throws IllegalStateException if ContextPropagation plugin is not installed or the entry is missing
 */
inline fun <reified T> ActivityContext.context(name: String): T {
    val scope =
        this as? ExecutionScope
            ?: error("ActivityContext does not support context propagation (not an ExecutionScope)")
    val propagated =
        scope.attributes.getOrNull(PropagatedContextKey)
            ?: error("ContextPropagation plugin not installed")
    val raw =
        propagated.getRaw(name)
            ?: error("No propagated context entry '$name'")
    return serializer.deserialize<T>(raw)
}
