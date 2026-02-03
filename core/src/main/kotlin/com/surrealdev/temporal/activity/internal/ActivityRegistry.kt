package com.surrealdev.temporal.activity.internal

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.application.ActivityRegistration
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible

/**
 * Information about a registered activity method.
 */
@InternalTemporalApi
data class ActivityMethodInfo(
    /** The activity type name (e.g., "greet"). */
    val activityType: String,
    /** The method to invoke. */
    val method: KFunction<*>,
    /**
     * The activity instance to invoke the method on.
     * - For unbound methods (from reflection scanning): the instance is required
     * - For bound method references (instance::method): null, as the instance is captured in the method
     */
    val instance: Any?,
    /** The parameter types (excluding context receiver). */
    val parameterTypes: List<KType>,
    /** The return type. */
    val returnType: KType,
    /** Whether the method uses ActivityContext as an extension receiver. */
    val hasContextReceiver: Boolean,
    /** Whether the method is suspending. */
    val isSuspend: Boolean,
)

/**
 * Registry for activity method implementations.
 *
 * Scans activity classes for methods annotated with @Activity and
 * provides lookup by activity type name.
 */
@InternalTemporalApi
class ActivityRegistry {
    private val methods = mutableMapOf<String, ActivityMethodInfo>()

    /**
     * Registers an activity.
     *
     * Handles two types of registrations:
     * - [ActivityRegistration.InstanceRegistration]: Scans the instance for @Activity annotations and registers each method
     * - [ActivityRegistration.FunctionRegistration]: Registers a specific method with a given activity type
     *
     * @param registration The activity registration
     * @throws IllegalArgumentException if no activity methods are found or duplicate types exist
     */
    fun register(registration: ActivityRegistration) {
        when (registration) {
            is ActivityRegistration.InstanceRegistration -> {
                registerInstance(registration.instance)
            }

            is ActivityRegistration.FunctionRegistration -> {
                registerFunction(
                    method = registration.method,
                    activityType = registration.activityType,
                )
            }

            is ActivityRegistration.DynamicRegistration -> {
                // Dynamic registrations are handled by ActivityDispatcher, not registry
            }
        }
    }

    /**
     * Registers all @Activity annotated methods from an instance.
     */
    private fun registerInstance(instance: Any) {
        val klass = instance::class

        // Find all methods annotated with @Activity
        val activityMethods = klass.declaredFunctions.filter { it.hasAnnotation<Activity>() }

        if (activityMethods.isEmpty()) {
            throw IllegalArgumentException(
                "Activity class ${klass.qualifiedName} has no @Activity annotations.",
            )
        }

        // Register each annotated method
        for (method in activityMethods) {
            registerMethod(method, instance, activityTypeOverride = null)
        }
    }

    /**
     * Registers a specific function as an activity (bound method reference).
     * Instance is null because it's captured in the bound method reference.
     */
    private fun registerFunction(
        method: KFunction<*>,
        activityType: String,
    ) {
        registerMethod(method, instance = null, activityTypeOverride = activityType)
    }

    /**
     * Registers a method as an activity.
     *
     * @param method The method to register
     * @param instance The instance to invoke the method on (null for bound method references)
     * @param activityTypeOverride Optional override for the activity type name
     */
    private fun registerMethod(
        method: KFunction<*>,
        instance: Any?,
        activityTypeOverride: String? = null,
    ) {
        method.isAccessible = true

        // Resolve activity type: override > @Activity annotation > function name
        val activityAnnotation = method.findAnnotation<Activity>()
        val activityType =
            activityTypeOverride
                ?: activityAnnotation?.name?.takeIf { it.isNotBlank() }
                ?: method.name

        // Check for duplicate registration
        if (methods.containsKey(activityType)) {
            throw IllegalArgumentException(
                "Duplicate activity type: $activityType. " +
                    "Activity types must be unique across all registrations.",
            )
        }

        // Check if method uses ActivityContext as extension receiver
        val extensionReceiver = method.extensionReceiverParameter
        val hasContextReceiver = extensionReceiver?.type?.classifier == ActivityContext::class

        // Get parameter types (excluding the extension receiver)
        val parameterTypes = method.valueParameters.map { it.type }

        val info =
            ActivityMethodInfo(
                activityType = activityType,
                method = method,
                instance = instance,
                parameterTypes = parameterTypes,
                returnType = method.returnType,
                hasContextReceiver = hasContextReceiver,
                isSuspend = method.isSuspend,
            )

        methods[activityType] = info
    }

    /**
     * Looks up an activity method by its type name.
     *
     * @param activityType The activity type name (e.g., "greet")
     * @return The activity method info, or null if not found
     */
    fun lookup(activityType: String): ActivityMethodInfo? = methods[activityType]

    /**
     * Gets all registered activity types.
     */
    fun registeredTypes(): Set<String> = methods.keys.toSet()

    /**
     * Checks if an activity type is registered.
     */
    fun contains(activityType: String): Boolean = methods.containsKey(activityType)
}
