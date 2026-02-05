package com.surrealdev.temporal.dependencies

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.util.AttributeKey
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.ExecutionScope
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.jvmErasure

/**
 * Key for caching the DependencyContext on an ExecutionScope.
 * This ensures that dependencies are cached within a single execution context.
 */
private val DependencyContextKey = AttributeKey<DependencyContext>("DependencyContext")

// =============================================================================
// Ktor-style Property Delegate Access (Recommended API)
// =============================================================================

/**
 * Property delegate provider for accessing activity-only dependencies.
 *
 * Usage in activities:
 * ```kotlin
 * suspend fun ActivityContext.processPayment(amount: Double): PaymentResult {
 *     val httpClient: HttpClient by activityDependencies
 *     return httpClient.post(config.paymentUrl, amount)
 * }
 * ```
 *
 * Or with `activity()`:
 * ```kotlin
 * suspend fun processPayment(amount: Double): PaymentResult {
 *     val httpClient: HttpClient by activity().activityDependencies
 *     return httpClient.post(...)
 * }
 * ```
 */
val ActivityContext.activityDependencies: ActivityDependencyProvider
    get() = ActivityDependencyProvider(this, DependencyScope.ACTIVITY_ONLY)

/**
 * Property delegate provider for accessing workflow-safe dependencies in activities.
 *
 * WORKFLOW_SAFE dependencies can be used in both workflows and activities since they
 * are deterministic and side-effect free.
 *
 * Usage in activities:
 * ```kotlin
 * suspend fun ActivityContext.processPayment(amount: Double): PaymentResult {
 *     val config: AppConfig by workflowDependencies
 *     return httpClient.post(config.paymentUrl, amount)
 * }
 * ```
 */
val ActivityContext.workflowDependencies: ActivityDependencyProvider
    get() = ActivityDependencyProvider(this, DependencyScope.WORKFLOW_SAFE)

/**
 * Property delegate provider for accessing workflow-safe dependencies in workflows.
 *
 * Usage in workflows:
 * ```kotlin
 * suspend fun WorkflowContext.run(orderId: String): OrderResult {
 *     val config: AppConfig by workflowDependencies
 *     val maxRetries = config.orderMaxRetries
 *     // ...
 * }
 * ```
 */
val WorkflowContext.workflowDependencies: WorkflowDependencyProvider
    get() = WorkflowDependencyProvider(this)

// =============================================================================
// Dependency Provider Classes
// =============================================================================

/**
 * Property delegate provider for activity dependencies.
 * Resolves dependencies based on the property's return type.
 */
class ActivityDependencyProvider(
    private val context: ActivityContext,
    private val scope: DependencyScope,
) {
    /**
     * Provides a delegate that resolves the dependency based on property type.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T {
        val executionScope =
            context as? ExecutionScope
                ?: throw IllegalStateException("ActivityContext does not support dependency injection")

        val type = property.returnType.jvmErasure as KClass<T>
        val depContext = createDependencyContextFromScope(executionScope)
        val key = DependencyKey(type = type, scope = scope, qualifier = null)
        return depContext.get(key)
    }
}

/**
 * Property delegate provider for workflow dependencies.
 * Resolves WORKFLOW_SAFE dependencies based on the property's return type.
 */
class WorkflowDependencyProvider(
    private val context: WorkflowContext,
) {
    /**
     * Provides a delegate that resolves the dependency based on property type.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T {
        val executionScope =
            context as? ExecutionScope
                ?: throw IllegalStateException("WorkflowContext does not support dependency injection")

        val type = property.returnType.jvmErasure as KClass<T>
        val depContext = createDependencyContextFromScope(executionScope)
        val key = DependencyKey(type = type, scope = DependencyScope.WORKFLOW_SAFE, qualifier = null)
        return depContext.get(key)
    }
}

// =============================================================================
// Qualified Dependency Access (for multiple instances of same type)
// =============================================================================

/**
 * Gets an activity-only dependency with a qualifier.
 *
 * Usage:
 * ```kotlin
 * val primaryDb: Database by activity().activityDependency("primary")
 * val secondaryDb: Database by activity().activityDependency("secondary")
 * ```
 */
fun ActivityContext.activityDependency(qualifier: String): QualifiedActivityDependencyProvider =
    QualifiedActivityDependencyProvider(this, DependencyScope.ACTIVITY_ONLY, qualifier)

/**
 * Gets a workflow-safe dependency with a qualifier from an activity.
 */
fun ActivityContext.workflowDependency(qualifier: String): QualifiedActivityDependencyProvider =
    QualifiedActivityDependencyProvider(this, DependencyScope.WORKFLOW_SAFE, qualifier)

/**
 * Gets a workflow-safe dependency with a qualifier.
 *
 * Usage:
 * ```kotlin
 * val usersDb: Database by workflow().workflowDependency("users")
 * val ordersDb: Database by workflow().workflowDependency("orders")
 * ```
 */
fun WorkflowContext.workflowDependency(qualifier: String): QualifiedWorkflowDependencyProvider =
    QualifiedWorkflowDependencyProvider(this, qualifier)

/**
 * Property delegate provider for qualified activity dependencies.
 */
class QualifiedActivityDependencyProvider(
    private val context: ActivityContext,
    private val scope: DependencyScope,
    private val qualifier: String,
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T {
        val executionScope =
            context as? ExecutionScope
                ?: throw IllegalStateException("ActivityContext does not support dependency injection")

        val type = property.returnType.jvmErasure as KClass<T>
        val depContext = createDependencyContextFromScope(executionScope)
        val key = DependencyKey(type = type, scope = scope, qualifier = qualifier)
        return depContext.get(key)
    }
}

/**
 * Property delegate provider for qualified workflow dependencies.
 */
class QualifiedWorkflowDependencyProvider(
    private val context: WorkflowContext,
    private val qualifier: String,
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T {
        val executionScope =
            context as? ExecutionScope
                ?: throw IllegalStateException("WorkflowContext does not support dependency injection")

        val type = property.returnType.jvmErasure as KClass<T>
        val depContext = createDependencyContextFromScope(executionScope)
        val key = DependencyKey(type = type, scope = DependencyScope.WORKFLOW_SAFE, qualifier = qualifier)
        return depContext.get(key)
    }
}

/**
 * Creates or retrieves a cached DependencyContext for the given execution scope.
 *
 * The DependencyContext is cached on the execution scope's attributes to ensure
 * dependencies are resolved consistently and cached within a single execution.
 *
 * Collects all DependencyRegistry instances from the scope chain (execution -> taskQueue -> application)
 * and creates a DependencyContext with proper fallback chain.
 */
private fun createDependencyContextFromScope(scope: ExecutionScope): DependencyContext {
    // Return cached context if available
    scope.attributes.getOrNull(DependencyContextKey)?.let { return it }

    // Collect all registries from the scope hierarchy
    val registries = collectRegistriesFromScope(scope)

    if (registries.isEmpty()) {
        throw IllegalStateException(
            "Dependencies not configured. Use app.dependencies { } or taskQueue.dependencies { } to register dependencies.",
        )
    }

    // Build the context with fallback chain
    // First registry is the most specific (closest to execution), last is most general (application)
    val primaryRegistry = registries.first()
    val fallbackRegistry = if (registries.size > 1) registries[1] else null

    val context =
        if (scope.isWorkflowContext) {
            primaryRegistry.createWorkflowContext(fallbackRegistry)
        } else {
            primaryRegistry.createActivityContext(fallbackRegistry)
        }

    // Cache the context for subsequent lookups
    scope.attributes.put(DependencyContextKey, context)

    return context
}

/**
 * Walks up the scope hierarchy collecting DependencyRegistry instances.
 *
 * Returns registries in order from most specific (execution/taskQueue) to least specific (application).
 */
private fun collectRegistriesFromScope(scope: AttributeScope): List<DependencyRegistry> {
    val registries = mutableListOf<DependencyRegistry>()
    var current: AttributeScope? = scope

    while (current != null) {
        current.attributes.getOrNull(DependencyRegistryKey)?.let {
            registries.add(it)
        }
        current = current.parentScope
    }

    return registries
}
