package com.surrealdev.temporal.application.plugin

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
 * Registry for managing hook handlers.
 *
 * The [HookRegistry] stores handlers for each hook and provides methods
 * to invoke them when lifecycle events occur.
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
}

/**
 * Internal implementation of [HookRegistry].
 */
internal class HookRegistryImpl : HookRegistry {
    private val handlers = mutableMapOf<String, MutableList<Any>>()

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
}

/**
 * Internal class for storing hook handlers before installation.
 */
class HookHandler<T> internal constructor(
    private val hook: Hook<T>,
    private val handler: T,
) {
    fun install(registry: HookRegistry) {
        registry.register(hook, handler)
    }
}
