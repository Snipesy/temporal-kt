package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.application.plugin.interceptor.Interceptor
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorChain

/**
 * A hook represents a lifecycle event that plugins can listen to.
 *
 * Hooks are typed by their handler signature, ensuring type-safe event handling.
 *
 * Example:
 * ```kotlin
 * object WorkflowStarted : Hook<suspend (WorkflowStartedContext) -> Unit> {
 *     override val name = "WorkflowStarted"
 * }
 * ```
 *
 * @param HookHandler The type of handler function for this hook
 */
interface Hook<HookHandler> {
    /**
     * The name of this hook, used for identification and logging.
     */
    val name: String

    /**
     * Installs a handler into the hook registry.
     *
     * @param hookRegistry The registry to install into
     * @param handler The handler function
     */
    fun install(
        hookRegistry: HookRegistry,
        handler: HookHandler,
    ) {
        hookRegistry.register(this, handler)
    }
}

/**
 * Registry for managing hook handlers and interceptors.
 *
 * The [HookRegistry] is the single unified registry for both lifecycle hooks
 * (fan-out to all handlers) and interceptor hooks (chain-of-responsibility).
 */
interface HookRegistry {
    /**
     * Registers a handler for the given hook.
     *
     * Multiple handlers can be registered for the same hook.
     */
    fun <T> register(
        hook: Hook<T>,
        handler: T,
    )

    /**
     * Calls all handlers registered for a suspending hook.
     *
     * @param hook The hook to invoke
     * @param event The event data to pass to handlers
     */
    suspend fun <T> call(
        hook: Hook<suspend (T) -> Unit>,
        event: T,
    )

    /**
     * Calls all handlers registered for a blocking hook.
     *
     * @param hook The hook to invoke
     * @param event The event data to pass to handlers
     */
    fun <T> callBlocking(
        hook: Hook<(T) -> Unit>,
        event: T,
    )

    /**
     * Returns an [InterceptorChain] for the given interceptor hook.
     *
     * @param hook The interceptor hook to build a chain for
     * @return An [InterceptorChain] containing all registered interceptors for this hook
     */
    fun <TInput, TOutput> chain(hook: InterceptorHook<TInput, TOutput>): InterceptorChain<TInput, TOutput>

    /**
     * Appends all handlers from [other] into this registry.
     */
    fun addAllFrom(other: HookRegistry)

    /**
     * Creates a new registry that contains handlers from both this registry (first)
     * and the [other] registry (appended after).
     *
     * Application-level handlers run before task-queue-level handlers.
     */
    fun mergeWith(other: HookRegistry): HookRegistry
}

/**
 * Internal implementation of [HookRegistry].
 */
internal class HookRegistryImpl : HookRegistry {
    internal val handlers = mutableMapOf<String, MutableList<Any>>()

    @Synchronized
    override fun <T> register(
        hook: Hook<T>,
        handler: T,
    ) {
        handlers.getOrPut(hook.name) { mutableListOf() }.add(handler as Any)
    }

    override suspend fun <T> call(
        hook: Hook<suspend (T) -> Unit>,
        event: T,
    ) {
        val hookHandlers =
            synchronized(this) {
                handlers[hook.name]?.toList() ?: emptyList()
            }

        for (handler in hookHandlers) {
            @Suppress("UNCHECKED_CAST")
            (handler as suspend (T) -> Unit).invoke(event)
        }
    }

    override fun <T> callBlocking(
        hook: Hook<(T) -> Unit>,
        event: T,
    ) {
        val hookHandlers =
            synchronized(this) {
                handlers[hook.name]?.toList() ?: emptyList()
            }

        for (handler in hookHandlers) {
            @Suppress("UNCHECKED_CAST")
            (handler as (T) -> Unit).invoke(event)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <TInput, TOutput> chain(hook: InterceptorHook<TInput, TOutput>): InterceptorChain<TInput, TOutput> {
        val list =
            synchronized(this) {
                (handlers[hook.name]?.toList() ?: emptyList()) as List<Interceptor<TInput, TOutput>>
            }
        return InterceptorChain(list)
    }

    @Synchronized
    override fun addAllFrom(other: HookRegistry) {
        require(other is HookRegistryImpl) { "Can only merge HookRegistryImpl instances" }
        for ((name, list) in other.handlers) {
            handlers.getOrPut(name) { mutableListOf() }.addAll(list)
        }
    }

    override fun mergeWith(other: HookRegistry): HookRegistry {
        val merged = HookRegistryImpl()
        merged.addAllFrom(this)
        merged.addAllFrom(other)
        return merged
    }

    companion object {
        /** An empty registry with no handlers. */
        val EMPTY: HookRegistry = HookRegistryImpl()
    }
}

/**
 * An interceptor hook represents a named interception point in the pipeline.
 *
 * Unlike [Hook] which fans out to all handlers, interceptor hooks form a chain
 * where each interceptor can modify the input/output and call `proceed` to invoke
 * the next interceptor.
 *
 * [InterceptorHook] extends [Hook] so that interceptors can be registered using
 * the same `pluginBuilder.on(hook, handler)` path as lifecycle hooks.
 *
 * Example:
 * ```kotlin
 * object ExecuteWorkflow : InterceptorHook<ExecuteWorkflowInput, Any?> {
 *     override val name = "ExecuteWorkflow"
 * }
 * ```
 *
 * @param TInput The input type for the intercepted operation
 * @param TOutput The output type for the intercepted operation
 */
interface InterceptorHook<TInput, TOutput> : Hook<Interceptor<TInput, TOutput>>
