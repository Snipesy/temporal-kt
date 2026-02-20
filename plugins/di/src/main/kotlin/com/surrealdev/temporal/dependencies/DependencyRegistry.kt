package com.surrealdev.temporal.dependencies

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.PluginPipeline
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdown
import com.surrealdev.temporal.application.plugin.hooks.WorkerStopped
import com.surrealdev.temporal.util.AttributeKey
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Key for storing DependencyRegistry in application attributes.
 */
val DependencyRegistryKey = AttributeKey<DependencyRegistry>("DependencyRegistry")

/**
 * Registry for dependency providers.
 *
 * Stores providers and creates execution-scoped contexts for resolution.
 * Dependency instances are singletons within a registry — the same instance
 * is returned across all contexts created from this registry.
 *
 * Usage:
 * ```kotlin
 * app.dependencies {
 *     workflowSafe<MyConfig> { MyConfigImpl() }
 *     activityOnly<HttpClient> { HttpClientImpl() }
 *
 *     // Explicit cleanup hook
 *     activityOnly<DatabasePool> { DatabasePool(config) } cleanup { it.shutdown() }
 * }
 * ```
 */
@TemporalDsl
class DependencyRegistry internal constructor() {
    private val logger = LoggerFactory.getLogger(DependencyRegistry::class.java)

    private val providers = ConcurrentHashMap<DependencyKey<*>, DependencyProvider<*>>()
    private val singletonCache = ConcurrentHashMap<DependencyKey<*>, Any>()
    private val cleanupHooks = ConcurrentHashMap<DependencyKey<*>, (Any) -> Unit>()

    private val closed = AtomicBoolean(false)

    /**
     * Registers a workflow-safe dependency provider.
     *
     * Workflow-safe dependencies must be deterministic and have no side effects.
     */
    inline fun <reified T : Any> workflowSafe(
        qualifier: String? = null,
        noinline factory: DependencyContext.() -> T,
    ): RegistrationHandle<T> {
        val key = DependencyKey(T::class, DependencyScope.WORKFLOW_SAFE, qualifier)
        register(key, DependencyProvider(key, factory))
        return RegistrationHandle(this, key)
    }

    /**
     * Registers an activity-only dependency provider.
     *
     * Activity-only dependencies can have side effects and will throw if used in workflows.
     */
    inline fun <reified T : Any> activityOnly(
        qualifier: String? = null,
        noinline factory: DependencyContext.() -> T,
    ): RegistrationHandle<T> {
        val key = DependencyKey(T::class, DependencyScope.ACTIVITY_ONLY, qualifier)
        register(key, DependencyProvider(key, factory))
        return RegistrationHandle(this, key)
    }

    /**
     * Registers a dependency provider with explicit scope.
     */
    inline fun <reified T : Any> provide(
        scope: DependencyScope,
        qualifier: String? = null,
        noinline factory: DependencyContext.() -> T,
    ): RegistrationHandle<T> {
        val key = DependencyKey(T::class, scope, qualifier)
        register(key, DependencyProvider(key, factory))
        return RegistrationHandle(this, key)
    }

    /**
     * Registers a dependency provider.
     */
    @PublishedApi
    internal fun <T : Any> register(
        key: DependencyKey<T>,
        provider: DependencyProvider<T>,
    ) {
        providers[key] = provider
    }

    /**
     * Gets a provider for the given key, or null if not registered.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getProvider(key: DependencyKey<T>): DependencyProvider<T>? = providers[key] as? DependencyProvider<T>

    /**
     * Returns true if a provider is registered for the given key.
     */
    internal fun hasProvider(key: DependencyKey<*>): Boolean = providers.containsKey(key)

    /**
     * Gets or creates the singleton instance for the given key.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> getOrCreate(
        key: DependencyKey<T>,
        context: DependencyContext,
    ): T {
        singletonCache[key]?.let { return it as T }
        return synchronized(this) {
            singletonCache.getOrPut(key) {
                val provider =
                    providers[key] ?: throw MissingDependencyException("No provider registered for $key")
                provider.factory(context)
            } as T
        }
    }

    /**
     * Registers a cleanup callback for a dependency key.
     *
     * The callback is invoked instead of [AutoCloseable.close] when the registry closes.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> registerCleanup(
        key: DependencyKey<T>,
        block: (T) -> Unit,
    ) {
        require(!cleanupHooks.containsKey(key)) {
            "A cleanup hook is already registered for $key"
        }
        cleanupHooks[key] = { instance -> block(instance as T) }
    }

    /**
     * Closes the registry, invoking cleanup hooks or AutoCloseable.close() on all
     * singleton instances. Idempotent — safe to call multiple times.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failures = mutableListOf<Throwable>()
        for ((key, instance) in singletonCache) {
            runCatching {
                val hook = cleanupHooks[key]
                when {
                    hook != null -> hook(instance)
                    instance is AutoCloseable -> instance.close()
                }
            }.onFailure { failures += it }
        }
        singletonCache.clear()
        if (failures.isNotEmpty()) {
            val summary = failures.joinToString(separator = "\n  ") { it.toString() }
            logger.warn("${failures.size} dependency cleanup(s) failed during registry close:\n  $summary")
        }
    }

    /**
     * Creates a new workflow-scoped dependency context.
     *
     * @param fallback Optional fallback registry for hierarchical lookup.
     *                 The primary registry is checked first, then the fallback.
     */
    fun createWorkflowContext(fallback: DependencyRegistry? = null): DependencyContext =
        DependencyContextImpl(this, isWorkflowContext = true, fallbackRegistry = fallback)

    /**
     * Creates a new activity-scoped dependency context.
     *
     * @param fallback Optional fallback registry for hierarchical lookup.
     *                 The primary registry is checked first, then the fallback.
     */
    fun createActivityContext(fallback: DependencyRegistry? = null): DependencyContext =
        DependencyContextImpl(this, isWorkflowContext = false, fallbackRegistry = fallback)

    /**
     * Handle returned from dependency registration methods, enabling the `cleanup { }` DSL.
     *
     * Usage:
     * ```kotlin
     * activityOnly<HttpClient> { HttpClient() } cleanup { it.close() }
     * ```
     */
    class RegistrationHandle<T : Any>(
        private val registry: DependencyRegistry,
        val key: DependencyKey<T>,
    ) {
        infix fun cleanup(block: (T) -> Unit): RegistrationHandle<T> {
            registry.registerCleanup(key, block)
            return this
        }
    }
}

/**
 * Access the dependency registry for this application.
 *
 * Auto-creates the registry on first access and registers a hook to close it
 * on [com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdown].
 */
var TemporalApplication.dependencies: DependencyRegistry
    get() =
        attributes.computeIfAbsent(DependencyRegistryKey) {
            DependencyRegistry().also { registry ->
                hookRegistry.register(ApplicationShutdown) { _ -> registry.close() }
            }
        }
    set(value) {
        attributes.put(DependencyRegistryKey, value)
    }

/**
 * DSL for configuring dependencies.
 *
 * Usage:
 * ```kotlin
 * app.dependencies {
 *     workflowSafe<MyConfig> { MyConfigImpl() }
 *     activityOnly<HttpClient> { HttpClientImpl() }
 * }
 * ```
 */
fun <T> TemporalApplication.dependencies(action: DependencyRegistry.() -> T): T = dependencies.action()

/**
 * Access the dependency registry for this task queue.
 *
 * Auto-creates the registry on first access and registers a hook on the parent application
 * to close it when the worker for this task queue stops.
 *
 * Task-queue-level dependencies take precedence over application-level dependencies.
 */
var TaskQueueBuilder.dependencies: DependencyRegistry
    get() =
        attributes.computeIfAbsent(DependencyRegistryKey) {
            DependencyRegistry().also { registry ->
                val queueName = taskQueueName
                val app = parentScope as? TemporalApplication
                app?.hookRegistry?.register(WorkerStopped) { ctx ->
                    if (ctx.taskQueue == queueName) registry.close()
                }
            }
        }
    set(value) {
        attributes.put(DependencyRegistryKey, value)
    }

/**
 * DSL for configuring task-queue-specific dependencies.
 *
 * Dependencies registered at the task queue level take precedence over
 * application-level dependencies for workflows and activities in this task queue.
 *
 * Usage:
 * ```kotlin
 * app.taskQueue("my-queue") {
 *     dependencies {
 *         workflowSafe<MyConfig> { TaskQueueSpecificConfig() }
 *     }
 *     workflow<MyWorkflow>()
 * }
 * ```
 */
fun <T> TaskQueueBuilder.dependencies(action: DependencyRegistry.() -> T): T = dependencies.action()

// =============================================================================
// Generic PluginPipeline Integration
// =============================================================================

/**
 * Access the dependency registry for any [PluginPipeline].
 *
 * Auto-creates the registry on first access. This works for any pipeline
 * including [TemporalApplication], [TaskQueueBuilder], and test harnesses.
 *
 * Usage with test harness:
 * ```kotlin
 * runActivityTest {
 *     dependencies {
 *         activityOnly<HttpClient> { MockHttpClient() }
 *         workflowSafe<ConfigService> { TestConfigService() }
 *     }
 *
 *     withActivityContext {
 *         val httpClient: HttpClient by activityDependencies
 *         val config: ConfigService by workflowDependencies
 *     }
 * }
 * ```
 */
var PluginPipeline.dependencies: DependencyRegistry
    get() = attributes.computeIfAbsent(DependencyRegistryKey) { DependencyRegistry() }
    set(value) {
        attributes.put(DependencyRegistryKey, value)
    }

/**
 * DSL for configuring dependencies on any [PluginPipeline].
 *
 * This generic extension works for any pipeline, including test harnesses.
 * Dependencies are resolved hierarchically through [PluginPipeline.parentScope].
 *
 * Usage with test harness:
 * ```kotlin
 * runActivityTest {
 *     dependencies {
 *         activityOnly<HttpClient> { MockHttpClient() }
 *         workflowSafe<ConfigService> { TestConfigService() }
 *     }
 *
 *     val activity = MyActivity()
 *     val result = withActivityContext {
 *         activity.doWork("input")
 *     }
 *     assertEquals("expected", result)
 * }
 * ```
 *
 * For hierarchical lookup with app-level dependencies as fallback:
 * ```kotlin
 * val app = TemporalApplication { ... }
 * app.dependencies {
 *     workflowSafe<ConfigService> { ProductionConfig() }
 * }
 *
 * runActivityTest {
 *     parentScope = app  // Inherit app dependencies
 *
 *     dependencies {
 *         // Override just the HTTP client for testing
 *         activityOnly<HttpClient> { MockHttpClient() }
 *     }
 *
 *     withActivityContext {
 *         val httpClient: HttpClient by activityDependencies  // From harness
 *         val config: ConfigService by workflowDependencies   // From app (inherited)
 *     }
 * }
 * ```
 */
fun <T> PluginPipeline.dependencies(action: DependencyRegistry.() -> T): T = dependencies.action()
