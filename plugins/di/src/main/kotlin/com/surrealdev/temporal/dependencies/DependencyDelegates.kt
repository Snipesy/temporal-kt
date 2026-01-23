package com.surrealdev.temporal.dependencies

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.ExecutionScope
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.jvmErasure

/**
 * Property delegate for accessing workflow-safe dependencies in workflows.
 *
 * Usage:
 * ```kotlin
 * @Workflow("MyWorkflow")
 * class MyWorkflow {
 *     @WorkflowRun
 *     suspend fun WorkflowContext.run(): String {
 *         val config: MyConfig by workflowDependencies()
 *         return config.value
 *     }
 * }
 * ```
 *
 * @param qualifier Optional qualifier to distinguish multiple instances of the same type
 */
fun <T : Any> workflowDependencies(qualifier: String? = null): ReadOnlyProperty<WorkflowContext, T> =
    WorkflowDependencyDelegate(DependencyScope.WORKFLOW_SAFE, qualifier)

/**
 * Property delegate for accessing activity dependencies.
 *
 * Usage:
 * ```kotlin
 * class MyActivity {
 *     @Activity
 *     suspend fun ActivityContext.doWork(): String {
 *         val httpClient: HttpClient by activityDependencies()
 *         return httpClient.get("https://api.example.com")
 *     }
 * }
 * ```
 *
 * @param qualifier Optional qualifier to distinguish multiple instances of the same type
 */
fun <T : Any> activityDependencies(qualifier: String? = null): ReadOnlyProperty<ActivityContext, T> =
    ActivityDependencyDelegate(DependencyScope.ACTIVITY_ONLY, qualifier)

/**
 * Generic property delegate for accessing any dependency from either context.
 *
 * This delegate works with both WorkflowContext and ActivityContext.
 * For workflow contexts, only WORKFLOW_SAFE dependencies can be accessed.
 *
 * Usage:
 * ```kotlin
 * val service: MyService by dependencies()
 * ```
 *
 * @param scope The dependency scope (WORKFLOW_SAFE or ACTIVITY_ONLY)
 * @param qualifier Optional qualifier to distinguish multiple instances of the same type
 */
fun <T : Any> dependencies(
    scope: DependencyScope = DependencyScope.WORKFLOW_SAFE,
    qualifier: String? = null,
): ReadOnlyProperty<Any, T> = GenericDependencyDelegate(scope, qualifier)

/**
 * Internal delegate for workflow dependencies.
 *
 * Lazily resolves dependency context from application/task queue attributes.
 */
private class WorkflowDependencyDelegate<T : Any>(
    private val scope: DependencyScope,
    private val qualifier: String?,
) : ReadOnlyProperty<WorkflowContext, T> {
    // Cache the resolved dependency context per workflow execution
    private var cachedContext: DependencyContext? = null

    @Suppress("UNCHECKED_CAST")
    override fun getValue(
        thisRef: WorkflowContext,
        property: KProperty<*>,
    ): T {
        val depContext = cachedContext ?: resolveDependencyContext(thisRef).also { cachedContext = it }

        val key =
            DependencyKey(
                type = property.returnType.jvmErasure as kotlin.reflect.KClass<T>,
                scope = scope,
                qualifier = qualifier,
            )

        return depContext.get(key)
    }

    private fun resolveDependencyContext(context: WorkflowContext): DependencyContext {
        val scope =
            context as? ExecutionScope
                ?: throw IllegalStateException("WorkflowContext does not support dependency injection")

        return createDependencyContextFromScope(scope)
    }
}

/**
 * Internal delegate for activity dependencies.
 *
 * Lazily resolves dependency context from application/task queue attributes.
 */
private class ActivityDependencyDelegate<T : Any>(
    private val scope: DependencyScope,
    private val qualifier: String?,
) : ReadOnlyProperty<ActivityContext, T> {
    // Cache the resolved dependency context per activity execution
    private var cachedContext: DependencyContext? = null

    @Suppress("UNCHECKED_CAST")
    override fun getValue(
        thisRef: ActivityContext,
        property: KProperty<*>,
    ): T {
        val depContext = cachedContext ?: resolveDependencyContext(thisRef).also { cachedContext = it }

        val key =
            DependencyKey(
                type = property.returnType.jvmErasure as kotlin.reflect.KClass<T>,
                scope = scope,
                qualifier = qualifier,
            )

        return depContext.get(key)
    }

    private fun resolveDependencyContext(context: ActivityContext): DependencyContext {
        val scope =
            context as? ExecutionScope
                ?: throw IllegalStateException("ActivityContext does not support dependency injection")

        return createDependencyContextFromScope(scope)
    }
}

/**
 * Generic delegate that works with both contexts.
 *
 * Lazily resolves dependency context from application/task queue attributes.
 */
private class GenericDependencyDelegate<T : Any>(
    private val scope: DependencyScope,
    private val qualifier: String?,
) : ReadOnlyProperty<Any, T> {
    // Cache the resolved dependency context
    private var cachedContext: DependencyContext? = null

    @Suppress("UNCHECKED_CAST")
    override fun getValue(
        thisRef: Any,
        property: KProperty<*>,
    ): T {
        val depContext = cachedContext ?: resolveDependencyContext(thisRef).also { cachedContext = it }

        val key =
            DependencyKey(
                type = property.returnType.jvmErasure as kotlin.reflect.KClass<T>,
                scope = scope,
                qualifier = qualifier,
            )

        return depContext.get(key)
    }

    private fun resolveDependencyContext(context: Any): DependencyContext {
        val scope =
            context as? ExecutionScope
                ?: throw IllegalStateException(
                    "dependencies() can only be used with WorkflowContext or ActivityContext receiver",
                )

        return createDependencyContextFromScope(scope)
    }
}

/**
 * Creates a DependencyContext by walking the scope hierarchy to find registries.
 *
 * Collects all DependencyRegistry instances from the scope chain (execution -> taskQueue -> application)
 * and creates a DependencyContext with proper fallback chain.
 */
private fun createDependencyContextFromScope(scope: ExecutionScope): DependencyContext {
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

    return if (scope.isWorkflowContext) {
        primaryRegistry.createWorkflowContext(fallbackRegistry)
    } else {
        primaryRegistry.createActivityContext(fallbackRegistry)
    }
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
