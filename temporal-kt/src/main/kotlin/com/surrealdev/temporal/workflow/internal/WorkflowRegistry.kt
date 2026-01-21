package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.WorkflowRegistration
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible

/**
 * Information about a registered query handler method.
 *
 * @property queryName The query name (null for dynamic handler that catches all unhandled queries)
 * @property handlerMethod The method to invoke for this query (null for runtime-registered handlers)
 * @property handler The handler function for runtime-registered handlers
 * @property hasContextReceiver Whether the method uses WorkflowContext as an extension receiver
 * @property isSuspend Whether the method is suspending
 * @property parameterTypes The parameter types (excluding context receiver)
 * @property returnType The return type
 * @property description Human-readable description shown in Temporal UI/CLI
 */
internal data class QueryHandlerInfo(
    val queryName: String?,
    val handlerMethod: KFunction<*>?,
    val handler: (suspend (Array<Any?>) -> Any?)? = null,
    val hasContextReceiver: Boolean,
    val isSuspend: Boolean,
    val parameterTypes: List<KType>,
    val returnType: KType,
    val description: String = "",
)

/**
 * Information about a registered workflow method.
 */
internal data class WorkflowMethodInfo(
    /** The workflow type name. */
    val workflowType: String,
    /** The @WorkflowRun method to invoke. */
    val runMethod: KFunction<*>,
    /** The workflow implementation instance (used as a factory). */
    val implementation: Any,
    /** The parameter types (excluding context receiver). */
    val parameterTypes: List<KType>,
    /** The return type. */
    val returnType: KType,
    /** Whether the method uses WorkflowContext as an extension receiver. */
    val hasContextReceiver: Boolean,
    /** Whether the method is suspending. */
    val isSuspend: Boolean,
    /**
     * Query handlers for this workflow.
     * Keys are query names (null key = dynamic handler).
     */
    val queryHandlers: Map<String?, QueryHandlerInfo> = emptyMap(),
)

/**
 * Registry for workflow implementations.
 *
 * Scans workflow classes for the @WorkflowRun method and provides
 * lookup by workflow type name.
 */
internal class WorkflowRegistry {
    private val workflows = mutableMapOf<String, WorkflowMethodInfo>()

    /**
     * Registers a workflow implementation.
     *
     * @param registration The workflow registration containing the implementation
     * @throws IllegalArgumentException if no @WorkflowRun method is found or duplicate types exist
     */
    fun register(registration: WorkflowRegistration) {
        val implementation = registration.implementation
        val klass = implementation::class

        // Get the workflow type from @Workflow annotation or registration
        val workflowAnnotation = klass.findAnnotation<Workflow>()
        val workflowType =
            when {
                registration.workflowType.isNotBlank() -> registration.workflowType
                workflowAnnotation?.name?.isNotBlank() == true -> workflowAnnotation.name
                else -> klass.simpleName ?: error("Cannot determine workflow type for ${klass.qualifiedName}")
            }

        // Find the @WorkflowRun method
        val runMethods = klass.declaredFunctions.filter { it.hasAnnotation<WorkflowRun>() }

        val runMethod =
            when {
                runMethods.isEmpty() -> throw IllegalArgumentException(
                    "Workflow class ${klass.qualifiedName} has no @WorkflowRun method",
                )

                runMethods.size > 1 -> throw IllegalArgumentException(
                    "Workflow class ${klass.qualifiedName} has multiple @WorkflowRun methods. Only one is allowed.",
                )

                else -> runMethods.first()
            }

        // Check for duplicate registration
        if (workflows.containsKey(workflowType)) {
            throw IllegalArgumentException(
                "Duplicate workflow type: $workflowType. " +
                    "Workflow types must be unique across all registrations.",
            )
        }

        runMethod.isAccessible = true

        // Check if method uses WorkflowContext as extension receiver
        val extensionReceiver = runMethod.extensionReceiverParameter
        val hasContextReceiver = extensionReceiver?.type?.classifier == WorkflowContext::class

        // Get parameter types (excluding the extension receiver)
        val parameterTypes = runMethod.valueParameters.map { it.type }

        // Scan for @Query annotated methods
        val queryHandlers = scanQueryHandlers(klass, workflowType)

        val info =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                implementation = implementation,
                parameterTypes = parameterTypes,
                returnType = runMethod.returnType,
                hasContextReceiver = hasContextReceiver,
                isSuspend = runMethod.isSuspend,
                queryHandlers = queryHandlers,
            )

        workflows[workflowType] = info
    }

    /**
     * Scans for @Query annotated methods in a workflow class.
     *
     * @param klass The workflow class
     * @param workflowType The workflow type name (for error messages)
     * @return Map of query name to handler info (null key = dynamic handler)
     */
    private fun scanQueryHandlers(
        klass: kotlin.reflect.KClass<*>,
        workflowType: String,
    ): Map<String?, QueryHandlerInfo> {
        val queryMethods = klass.declaredFunctions.filter { it.hasAnnotation<Query>() }

        if (queryMethods.isEmpty()) {
            return emptyMap()
        }

        val handlers = mutableMapOf<String?, QueryHandlerInfo>()

        for (method in queryMethods) {
            method.isAccessible = true

            val queryAnnotation = method.findAnnotation<Query>()!!
            val isDynamic = queryAnnotation.dynamic

            // Determine query name: null for dynamic, annotation value or function name otherwise
            val queryName =
                when {
                    isDynamic -> null
                    queryAnnotation.name.isNotBlank() -> queryAnnotation.name
                    else -> method.name
                }

            // Check for duplicate query names
            if (handlers.containsKey(queryName)) {
                val nameDesc = queryName ?: "dynamic"
                throw IllegalArgumentException(
                    "Workflow $workflowType has duplicate query handler for '$nameDesc'. " +
                        "Each query name must have exactly one handler.",
                )
            }

            // Check if method uses WorkflowContext as extension receiver
            val extensionReceiver = method.extensionReceiverParameter
            val queryHasContextReceiver = extensionReceiver?.type?.classifier == WorkflowContext::class

            // Get parameter types (excluding the extension receiver)
            val queryParamTypes = method.valueParameters.map { it.type }

            // Validate return type - queries should return a value (not Unit)
            // Note: Unit return is allowed but often indicates a mistake
            // We don't enforce this as some queries may genuinely return Unit

            val handlerInfo =
                QueryHandlerInfo(
                    queryName = queryName,
                    handlerMethod = method,
                    hasContextReceiver = queryHasContextReceiver,
                    isSuspend = method.isSuspend,
                    parameterTypes = queryParamTypes,
                    returnType = method.returnType,
                    description = queryAnnotation.description,
                )

            handlers[queryName] = handlerInfo
        }

        return handlers
    }

    /**
     * Looks up a workflow method by its type name.
     *
     * @param workflowType The workflow type name
     * @return The workflow method info, or null if not found
     */
    fun lookup(workflowType: String): WorkflowMethodInfo? = workflows[workflowType]

    /**
     * Gets all registered workflow types.
     */
    fun registeredTypes(): Set<String> = workflows.keys.toSet()

    /**
     * Checks if a workflow type is registered.
     */
    fun contains(workflowType: String): Boolean = workflows.containsKey(workflowType)
}
