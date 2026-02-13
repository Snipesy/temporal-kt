package com.surrealdev.temporal.dependencies.context

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.ScopedPlugin
import com.surrealdev.temporal.application.plugin.createScopedPlugin
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.payloadSerializer
import com.surrealdev.temporal.util.ExecutionScope
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.reflect.KType
import kotlin.reflect.typeOf as kotlinTypeOf

// =============================================================================
// Provider Behavior
// =============================================================================

/**
 * Controls how a provider-based context entry behaves when an inbound header
 * already exists for the same key.
 *
 * This is particularly important for **replay safety**: during workflow replay,
 * the `onExecute` interceptor runs again with the same headers from the original
 * `StartWorkflow` event. If a provider is non-deterministic (e.g., reads a config
 * that changed, generates a new UUID), re-executing it during replay would produce
 * a different value, which could cause non-determinism errors if workflow code
 * branches on the context value.
 */
enum class ProviderBehavior {
    /**
     * If an inbound header already exists for this key, use the existing header
     * value and skip the provider lambda entirely.
     *
     * This is the **safe default** for application-side providers because:
     * - During replay, headers from history are preserved unchanged
     * - Non-deterministic providers cannot cause replay divergence
     * - Client-provided values are not silently overwritten
     *
     * The provider only executes when no inbound header exists for the key
     * (i.e., on the client side, or for application-side providers that add
     * new context not present in inbound headers).
     */
    SKIP_IF_PRESENT,

    /**
     * Always execute the provider lambda and overwrite any existing inbound
     * header with the same key.
     *
     * Use this when the provider must always produce a fresh value, regardless
     * of what the inbound headers contain. The provider **must be deterministic**
     * when used on the application side, or replay divergence may occur.
     */
    ALWAYS_EXECUTE,
}

// =============================================================================
// Context Entry Model
// =============================================================================

/**
 * Represents a single context entry to be propagated across service boundaries.
 */
@PublishedApi
internal sealed class ContextEntry {
    abstract val name: String

    /**
     * A provider-based entry whose value is computed at intercept time
     * and serialized using the pipeline's [PayloadSerializer].
     */
    @PublishedApi
    internal class Provider(
        override val name: String,
        val type: KType,
        val behavior: ProviderBehavior,
        val provider: () -> Any?,
    ) : ContextEntry()

    /**
     * A pass-through entry that forwards raw [TemporalPayload] bytes from
     * inbound headers to outbound headers without deserialization.
     *
     * The value is only deserialized when accessed via `context<T>()`.
     */
    @PublishedApi
    internal class PassThrough(override val name: String) : ContextEntry()
}

// =============================================================================
// Configuration DSL
// =============================================================================

/**
 * Configuration for the [ContextPropagation] plugin.
 *
 * Allows registering context entries that will be propagated across service boundaries
 * (client -> workflow -> activity -> child workflow) via Temporal headers.
 *
 * **Client side — attach context to outgoing calls:**
 * ```kotlin
 * val client = TemporalClient.connect {
 *     install(ContextPropagation) {
 *         context("tenantId") { myTenant }
 *         context("requestId") { UUID.randomUUID().toString() }
 *     }
 * }
 * ```
 *
 * **Application side — receive and forward context:**
 * ```kotlin
 * application {
 *     install(ContextPropagation) {
 *         passThrough("tenantId")                      // forward raw bytes
 *         context("somethingElse") { anotherValue }   // add new context
 *     }
 * }
 * ```
 *
 * **Replay safety:** By default, application-side `context()` providers use
 * [ProviderBehavior.SKIP_IF_PRESENT], which skips the provider lambda when an
 * inbound header already exists for the key (e.g., during replay from history).
 * Use [ProviderBehavior.ALWAYS_EXECUTE] if the provider must always produce a
 * fresh value — but ensure the provider is deterministic to avoid replay divergence.
 */
@TemporalDsl
class ContextPropagationConfig {
    @PublishedApi
    internal val entries = mutableListOf<ContextEntry>()

    /**
     * Registers a context entry with a provider lambda.
     *
     * The provider is called at intercept time and its result is serialized
     * using the pipeline's serializer into the outbound headers.
     *
     * @param T The type of the context value
     * @param name The header name used for propagation
     * @param behavior Controls whether the provider is skipped when an inbound header
     *   already exists for this key. Defaults to [ProviderBehavior.SKIP_IF_PRESENT],
     *   which is replay-safe.
     * @param provider Lambda that produces the context value
     */
    inline fun <reified T> context(
        name: String,
        behavior: ProviderBehavior = ProviderBehavior.SKIP_IF_PRESENT,
        noinline provider: () -> T,
    ) {
        entries.add(ContextEntry.Provider(name, kotlinTypeOf<T>(), behavior, provider))
    }

    /**
     * Registers a pass-through entry that forwards raw payload bytes
     * from inbound headers to outbound headers without any serialization.
     *
     * The value is only deserialized when accessed via `context<T>()`.
     *
     * @param name The header name to forward
     */
    fun passThrough(name: String) {
        entries.add(ContextEntry.PassThrough(name))
    }
}

// =============================================================================
// Plugin Instance
// =============================================================================

class ContextPropagationInstance internal constructor(val config: ContextPropagationConfig)

// =============================================================================
// Plugin Definition
// =============================================================================

/**
 * Plugin for propagating typed context values across Temporal service boundaries.
 *
 * Context values are transported via Temporal headers and automatically serialized/deserialized
 * using the pipeline's [PayloadSerializer].
 *
 * **How it works:**
 * - On the **client** pipeline: Provider entries are serialized and injected into outbound headers
 *   for `startWorkflow`, `signalWorkflow`, `queryWorkflow`, and `startWorkflowUpdate`.
 * - On the **application** pipeline:
 *   - **Inbound** (workflow/activity `onExecute`, signal, query, update): Registered names are
 *     extracted from headers and stored as a [PropagatedContext] on the [ExecutionScope.attributes].
 *   - **Outbound** (scheduleActivity, startChildWorkflow, continueAsNew, etc.): The current
 *     [PropagatedContext] is read from the execution scope and merged into outbound headers.
 *
 * **Access in workflows/activities:**
 * ```kotlin
 * val tenant = workflow().context<Tenant>("tenantId")
 * val tenant = activity().context<Tenant>("tenantId")
 * ```
 *
 * This is a [ScopedPlugin] and can be installed on clients, applications, or task queues.
 */
val ContextPropagation: ScopedPlugin<ContextPropagationConfig, ContextPropagationInstance> =
    createScopedPlugin("ContextPropagation", ::ContextPropagationConfig) { config ->
        val serializer = pipeline.payloadSerializer()
        val providerEntries = config.entries.filterIsInstance<ContextEntry.Provider>()
        val allRegisteredNames = config.entries.map { it.name }.toSet()

        // Helper: serialize all provider entries into a header map
        fun serializeProviders(serializer: PayloadSerializer): Map<String, TemporalPayload> =
            providerEntries.associate { entry ->
                entry.name to serializer.serialize(entry.type, entry.provider())
            }

        // Helper: build PropagatedContext from inbound headers + provider entries
        fun buildPropagatedContext(
            inboundHeaders: Map<String, TemporalPayload>?,
            serializer: PayloadSerializer,
        ): PropagatedContext {
            val values = mutableMapOf<String, TemporalPayload>()

            // Extract registered names (passthrough + provider names) from inbound headers
            if (inboundHeaders != null) {
                for (name in allRegisteredNames) {
                    inboundHeaders[name]?.let { values[name] = it }
                }
            }

            // Apply provider entries, respecting their behavior setting
            for (entry in providerEntries) {
                when (entry.behavior) {
                    ProviderBehavior.SKIP_IF_PRESENT -> {
                        // Only execute provider if no inbound header exists for this key.
                        // This is replay-safe: during replay, the header from history is
                        // preserved and the provider lambda is not called.
                        if (entry.name !in values) {
                            values[entry.name] = serializer.serialize(entry.type, entry.provider())
                        }
                    }
                    ProviderBehavior.ALWAYS_EXECUTE -> {
                        // Always execute the provider, overwriting any inbound header.
                        values[entry.name] = serializer.serialize(entry.type, entry.provider())
                    }
                }
            }

            return PropagatedContext(values)
        }

        // Helper: merge propagated context into mutable outbound headers.
        // User-provided headers (already in the map) take precedence.
        fun mergeOutboundHeaders(headers: MutableMap<String, TemporalPayload>, scope: ExecutionScope) {
            val propagated = scope.attributes.getOrNull(PropagatedContextKey) ?: return
            for ((key, value) in propagated.toHeaderMap()) {
                headers.putIfAbsent(key, value)
            }
        }

        // Helper: get ExecutionScope from coroutine context
        suspend fun getExecutionScope(): ExecutionScope? {
            val wfCtx = currentCoroutineContext()[WorkflowContext]
            return wfCtx as? ExecutionScope
        }

        // =====================================================================
        // Client Outbound Interceptors
        // =====================================================================

        client {
            onStartWorkflow { input, proceed ->
                input.headers.putAll(serializeProviders(serializer))
                proceed(input)
            }

            onSignalWorkflow { input, proceed ->
                input.headers.putAll(serializeProviders(serializer))
                proceed(input)
            }

            onQueryWorkflow { input, proceed ->
                input.headers.putAll(serializeProviders(serializer))
                proceed(input)
            }

            onStartWorkflowUpdate { input, proceed ->
                input.headers.putAll(serializeProviders(serializer))
                proceed(input)
            }
        }

        // =====================================================================
        // Workflow Inbound Interceptors
        // =====================================================================

        workflow {
            onExecute { input, proceed ->
                val scope = getExecutionScope()
                if (scope != null) {
                    val ctx = buildPropagatedContext(input.headers, serializer)
                    scope.attributes.put(PropagatedContextKey, ctx)
                }
                proceed(input)
            }

            onHandleSignal { input, proceed ->
                val scope = getExecutionScope()
                if (scope != null) {
                    val existing = scope.attributes.getOrNull(PropagatedContextKey)
                    if (existing != null && input.headers != null) {
                        // Validate that signal headers don't conflict with existing context.
                        // Signals typically don't carry propagated headers (they're set on
                        // startWorkflow), but if they do, they must match.
                        for (name in allRegisteredNames) {
                            val signalValue = input.headers!![name] ?: continue
                            val existingValue = existing.getRaw(name) ?: continue
                            if (signalValue != existingValue) {
                                error(
                                    "Signal header '$name' conflicts with existing propagated context. " +
                                        "Context values are typically set on startWorkflow and must not change."
                                )
                            }
                        }
                    } else if (existing == null) {
                        val ctx = buildPropagatedContext(input.headers, serializer)
                        scope.attributes.put(PropagatedContextKey, ctx)
                    }
                }
                proceed(input)
            }

            onHandleQuery { input, proceed ->
                val scope = getExecutionScope()
                if (scope != null && scope.attributes.getOrNull(PropagatedContextKey) == null) {
                    val ctx = buildPropagatedContext(input.headers, serializer)
                    scope.attributes.put(PropagatedContextKey, ctx)
                }
                proceed(input)
            }

            onValidateUpdate { input, proceed ->
                val scope = getExecutionScope()
                if (scope != null && scope.attributes.getOrNull(PropagatedContextKey) == null) {
                    val ctx = buildPropagatedContext(input.headers, serializer)
                    scope.attributes.put(PropagatedContextKey, ctx)
                }
                proceed(input)
            }

            onExecuteUpdate { input, proceed ->
                val scope = getExecutionScope()
                if (scope != null) {
                    val existing = scope.attributes.getOrNull(PropagatedContextKey)
                    if (existing != null && input.headers != null) {
                        // Validate that update headers don't conflict with existing context.
                        for (name in allRegisteredNames) {
                            val updateValue = input.headers!![name] ?: continue
                            val existingValue = existing.getRaw(name) ?: continue
                            if (updateValue != existingValue) {
                                error(
                                    "Update header '$name' conflicts with existing propagated context. " +
                                        "Context values are typically set on startWorkflow and must not change."
                                )
                            }
                        }
                    } else if (existing == null) {
                        val ctx = buildPropagatedContext(input.headers, serializer)
                        scope.attributes.put(PropagatedContextKey, ctx)
                    }
                }
                proceed(input)
            }

            // =================================================================
            // Workflow Outbound Interceptors
            // =================================================================

            onScheduleActivity { input, proceed ->
                val scope = getExecutionScope()
                val propagated = scope?.attributes?.getOrNull(PropagatedContextKey)
                if (propagated != null) {
                    // User-provided headers take precedence over propagated context
                    val merged = propagated.toHeaderMap() + (input.options.headers ?: emptyMap())
                    proceed(input.copy(options = input.options.copy(headers = merged)))
                } else {
                    proceed(input)
                }
            }

            onScheduleLocalActivity { input, proceed ->
                val scope = getExecutionScope()
                if (scope != null) {
                    mergeOutboundHeaders(input.headers, scope)
                }
                proceed(input)
            }

            onStartChildWorkflow { input, proceed ->
                val scope = getExecutionScope()
                if (scope != null) {
                    mergeOutboundHeaders(input.headers, scope)
                }
                proceed(input)
            }

            onContinueAsNew { input, proceed ->
                val scope = getExecutionScope()
                val propagated = scope?.attributes?.getOrNull(PropagatedContextKey)
                if (propagated != null) {
                    // User-provided headers take precedence over propagated context
                    val merged = propagated.toHeaderMap() + (input.options.headers ?: emptyMap())
                    proceed(input.copy(options = input.options.copy(headers = merged)))
                } else {
                    proceed(input)
                }
            }

            onSignalExternalWorkflow { input, proceed ->
                val scope = getExecutionScope()
                if (scope != null) {
                    mergeOutboundHeaders(input.headers, scope)
                }
                proceed(input)
            }
        }

        // =====================================================================
        // Activity Inbound Interceptor
        // =====================================================================

        activity {
            onExecute { input, proceed ->
                val scope = currentCoroutineContext()[com.surrealdev.temporal.activity.ActivityContext]
                    as? ExecutionScope
                if (scope != null) {
                    val ctx = buildPropagatedContext(input.headers, serializer)
                    scope.attributes.put(PropagatedContextKey, ctx)
                }
                proceed(input)
            }
        }

        ContextPropagationInstance(config)
    }
