package com.surrealdev.temporal.workflow.internal

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

        val info =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                implementation = implementation,
                parameterTypes = parameterTypes,
                returnType = runMethod.returnType,
                hasContextReceiver = hasContextReceiver,
                isSuspend = runMethod.isSuspend,
            )

        workflows[workflowType] = info
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
