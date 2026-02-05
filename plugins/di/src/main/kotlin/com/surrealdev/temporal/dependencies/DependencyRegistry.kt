package com.surrealdev.temporal.dependencies

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.PluginPipeline
import com.surrealdev.temporal.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Key for storing DependencyRegistry in application attributes.
 */
val DependencyRegistryKey = AttributeKey<DependencyRegistry>("DependencyRegistry")

/**
 * Registry for dependency providers.
 *
 * Stores providers and creates execution-scoped contexts for resolution.
 *
 * Usage:
 * ```kotlin
 * app.dependencies {
 *     workflowSafe<MyConfig> { MyConfigImpl() }
 *     activityOnly<HttpClient> { HttpClientImpl() }
 * }
 * ```
 */
@TemporalDsl
class DependencyRegistry internal constructor() {
    private val providers = ConcurrentHashMap<DependencyKey<*>, DependencyProvider<*>>()

    /**
     * Registers a workflow-safe dependency provider.
     *
     * Workflow-safe dependencies must be deterministic and have no side effects.
     */
    inline fun <reified T : Any> workflowSafe(
        qualifier: String? = null,
        noinline factory: DependencyContext.() -> T,
    ) {
        provide(DependencyScope.WORKFLOW_SAFE, qualifier, factory)
    }

    /**
     * Registers an activity-only dependency provider.
     *
     * Activity-only dependencies can have side effects and will throw if used in workflows.
     */
    inline fun <reified T : Any> activityOnly(
        qualifier: String? = null,
        noinline factory: DependencyContext.() -> T,
    ) {
        provide(DependencyScope.ACTIVITY_ONLY, qualifier, factory)
    }

    /**
     * Registers a dependency provider with explicit scope.
     */
    inline fun <reified T : Any> provide(
        scope: DependencyScope,
        qualifier: String? = null,
        noinline factory: DependencyContext.() -> T,
    ) {
        val key = DependencyKey(T::class, scope, qualifier)
        register(key, DependencyProvider(key, factory))
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
}

/**
 * Access the dependency registry for this application.
 *
 * Auto-creates the registry on first access.
 */
var TemporalApplication.dependencies: DependencyRegistry
    get() = attributes.computeIfAbsent(DependencyRegistryKey) { DependencyRegistry() }
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
 * Auto-creates the registry on first access.
 * Task-queue-level dependencies take precedence over application-level dependencies.
 */
var TaskQueueBuilder.dependencies: DependencyRegistry
    get() = attributes.computeIfAbsent(DependencyRegistryKey) { DependencyRegistry() }
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
