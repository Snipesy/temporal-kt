package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.UpdateValidator
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
 * Information about a registered signal handler method.
 *
 * @property signalName The signal name (null for dynamic handler that catches all unhandled signals)
 * @property handlerMethod The method to invoke for this signal
 * @property hasContextReceiver Whether the method uses WorkflowContext as an extension receiver
 * @property isSuspend Whether the method is suspending
 * @property parameterTypes The parameter types (excluding context receiver and signal name for dynamic handlers)
 */
internal data class SignalHandlerInfo(
    val signalName: String?,
    val handlerMethod: KFunction<*>,
    val hasContextReceiver: Boolean,
    val isSuspend: Boolean,
    val parameterTypes: List<KType>,
)

/**
 * Information about a registered update handler method.
 *
 * @property updateName The update name (null for dynamic handler that catches all unhandled updates)
 * @property handlerMethod The method to invoke for this update
 * @property validatorMethod The optional validator method to invoke before the update handler
 * @property hasContextReceiver Whether the handler method uses WorkflowContext as an extension receiver
 * @property isSuspend Whether the handler method is suspending
 * @property parameterTypes The parameter types (excluding context receiver and update name for dynamic handlers)
 * @property returnType The return type
 */
internal data class UpdateHandlerInfo(
    val updateName: String?,
    val handlerMethod: KFunction<*>,
    val validatorMethod: KFunction<*>?,
    val hasContextReceiver: Boolean,
    val isSuspend: Boolean,
    val parameterTypes: List<KType>,
    val returnType: KType,
)

/**
 * Information about a registered workflow method.
 */
internal data class WorkflowMethodInfo(
    /** The workflow type name. */
    val workflowType: String,
    /** The @WorkflowRun method to invoke. */
    val runMethod: KFunction<*>,
    /** The workflow class for creating new instances. */
    val workflowClass: kotlin.reflect.KClass<*>,
    /** Factory function to create a new workflow instance for each run. */
    val instanceFactory: () -> Any,
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
    /**
     * Signal handlers for this workflow.
     * Keys are signal names (null key = dynamic handler).
     */
    val signalHandlers: Map<String?, SignalHandlerInfo> = emptyMap(),
    /**
     * Update handlers for this workflow.
     * Keys are update names (null key = dynamic handler).
     */
    val updateHandlers: Map<String?, UpdateHandlerInfo> = emptyMap(),
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
     * Registers a workflow class.
     *
     * @param registration The workflow registration containing the workflow class
     * @throws IllegalArgumentException if no @WorkflowRun method is found or duplicate types exist
     */
    fun register(registration: WorkflowRegistration) {
        val klass = registration.workflowClass

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

        // Scan for @Signal annotated methods
        val signalHandlers = scanSignalHandlers(klass, workflowType)

        // Scan for @Update and @UpdateValidator annotated methods
        val updateHandlers = scanUpdateHandlers(klass, workflowType)

        // Create a factory function that creates new workflow instances
        // This ensures each workflow run gets a fresh instance with clean state
        val instanceFactory: () -> Any =
            registration.instanceFactory ?: run {
                val primaryConstructor =
                    klass.constructors.find { it.parameters.isEmpty() }
                        ?: error("Workflow class ${klass.qualifiedName} must have a no-arg constructor")
                primaryConstructor.isAccessible = true
                // pragma
                { primaryConstructor.call() }
            }

        val info =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                workflowClass = klass,
                instanceFactory = instanceFactory,
                parameterTypes = parameterTypes,
                returnType = runMethod.returnType,
                hasContextReceiver = hasContextReceiver,
                isSuspend = runMethod.isSuspend,
                queryHandlers = queryHandlers,
                signalHandlers = signalHandlers,
                updateHandlers = updateHandlers,
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
     * Scans for @Signal annotated methods in a workflow class.
     *
     * @param klass The workflow class
     * @param workflowType The workflow type name (for error messages)
     * @return Map of signal name to handler info (null key = dynamic handler)
     */
    private fun scanSignalHandlers(
        klass: kotlin.reflect.KClass<*>,
        workflowType: String,
    ): Map<String?, SignalHandlerInfo> {
        val signalMethods = klass.declaredFunctions.filter { it.hasAnnotation<Signal>() }

        if (signalMethods.isEmpty()) {
            return emptyMap()
        }

        val handlers = mutableMapOf<String?, SignalHandlerInfo>()

        for (method in signalMethods) {
            method.isAccessible = true

            val signalAnnotation = method.findAnnotation<Signal>()!!
            val isDynamic = signalAnnotation.dynamic

            // Determine signal name: null for dynamic, annotation value or function name otherwise
            val signalName =
                when {
                    isDynamic -> null
                    signalAnnotation.name.isNotBlank() -> signalAnnotation.name
                    else -> method.name
                }

            // Check for duplicate signal names
            if (handlers.containsKey(signalName)) {
                val nameDesc = signalName ?: "dynamic"
                throw IllegalArgumentException(
                    "Workflow $workflowType has duplicate signal handler for '$nameDesc'. " +
                        "Each signal name must have exactly one handler.",
                )
            }

            // Check if method uses WorkflowContext as extension receiver
            val extensionReceiver = method.extensionReceiverParameter
            val signalHasContextReceiver = extensionReceiver?.type?.classifier == WorkflowContext::class

            // Get parameter types (excluding the extension receiver)
            val signalParamTypes = method.valueParameters.map { it.type }

            val handlerInfo =
                SignalHandlerInfo(
                    signalName = signalName,
                    handlerMethod = method,
                    hasContextReceiver = signalHasContextReceiver,
                    isSuspend = method.isSuspend,
                    parameterTypes = signalParamTypes,
                )

            handlers[signalName] = handlerInfo
        }

        return handlers
    }

    /**
     * Scans for @Update and @UpdateValidator annotated methods in a workflow class.
     *
     * @param klass The workflow class
     * @param workflowType The workflow type name (for error messages)
     * @return Map of update name to handler info (null key = dynamic handler)
     */
    private fun scanUpdateHandlers(
        klass: kotlin.reflect.KClass<*>,
        workflowType: String,
    ): Map<String?, UpdateHandlerInfo> {
        val updateMethods = klass.declaredFunctions.filter { it.hasAnnotation<Update>() }
        val validatorMethods = klass.declaredFunctions.filter { it.hasAnnotation<UpdateValidator>() }

        if (updateMethods.isEmpty()) {
            // If there are validators but no updates, that's an error
            if (validatorMethods.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Workflow $workflowType has @UpdateValidator methods without matching @Update methods.",
                )
            }
            return emptyMap()
        }

        // Build a map of update name -> validator method
        val validatorsByName = mutableMapOf<String, KFunction<*>>()
        for (validatorMethod in validatorMethods) {
            validatorMethod.isAccessible = true
            val validatorAnnotation = validatorMethod.findAnnotation<UpdateValidator>()!!
            val updateName = validatorAnnotation.updateName

            if (validatorsByName.containsKey(updateName)) {
                throw IllegalArgumentException(
                    "Workflow $workflowType has duplicate @UpdateValidator for update '$updateName'. " +
                        "Each update can have at most one validator.",
                )
            }

            validatorsByName[updateName] = validatorMethod
        }

        val handlers = mutableMapOf<String?, UpdateHandlerInfo>()

        for (method in updateMethods) {
            method.isAccessible = true

            val updateAnnotation = method.findAnnotation<Update>()!!
            val isDynamic = updateAnnotation.dynamic

            // Determine update name: null for dynamic, annotation value or function name otherwise
            val updateName =
                when {
                    isDynamic -> null
                    updateAnnotation.name.isNotBlank() -> updateAnnotation.name
                    else -> method.name
                }

            // Check for duplicate update names
            if (handlers.containsKey(updateName)) {
                val nameDesc = updateName ?: "dynamic"
                throw IllegalArgumentException(
                    "Workflow $workflowType has duplicate update handler for '$nameDesc'. " +
                        "Each update name must have exactly one handler.",
                )
            }

            // Check if method uses WorkflowContext as extension receiver
            val extensionReceiver = method.extensionReceiverParameter
            val updateHasContextReceiver = extensionReceiver?.type?.classifier == WorkflowContext::class

            // Get parameter types (excluding the extension receiver)
            val updateParamTypes = method.valueParameters.map { it.type }

            // Look up the validator for this update (only for named updates)
            val validatorMethod = if (updateName != null) validatorsByName[updateName] else null

            val handlerInfo =
                UpdateHandlerInfo(
                    updateName = updateName,
                    handlerMethod = method,
                    validatorMethod = validatorMethod,
                    hasContextReceiver = updateHasContextReceiver,
                    isSuspend = method.isSuspend,
                    parameterTypes = updateParamTypes,
                    returnType = method.returnType,
                )

            handlers[updateName] = handlerInfo
        }

        // Check for validators without matching updates
        val updateNames =
            updateMethods
                .map { method ->
                    val ann = method.findAnnotation<Update>()!!
                    when {
                        ann.dynamic -> null
                        ann.name.isNotBlank() -> ann.name
                        else -> method.name
                    }
                }.filterNotNull()
                .toSet()

        for (validatorName in validatorsByName.keys) {
            if (validatorName !in updateNames) {
                throw IllegalArgumentException(
                    "Workflow $workflowType has @UpdateValidator for '$validatorName' but no matching @Update handler.",
                )
            }
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
