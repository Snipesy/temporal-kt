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
    /** The activity instance to invoke the method on. */
    val instance: Any,
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
     * Registers an activity implementation.
     *
     * Scans the implementation for @Activity annotations and registers each method.
     *
     * @param registration The activity registration containing the implementation
     * @throws IllegalArgumentException if no activity methods are found or duplicate types exist
     */
    fun register(registration: ActivityRegistration) {
        val implementation = registration.implementation
        val klass = implementation::class

        // Find all methods annotated with @Activity
        val activityMethods = klass.declaredFunctions.filter { it.hasAnnotation<Activity>() }

        if (activityMethods.isEmpty()) {
            throw IllegalArgumentException(
                "Activity class ${klass.qualifiedName} has no @Activity annotations. ",
            )
        } else {
            // Register explicitly annotated methods
            for (method in activityMethods) {
                registerMethod(method, implementation)
            }
        }
    }

    private fun registerMethod(
        method: KFunction<*>,
        instance: Any,
    ) {
        method.isAccessible = true

        // Get the activity name from @Activity annotation or use function name
        val activityAnnotation = method.findAnnotation<Activity>()
        val activityType =
            when {
                activityAnnotation?.name?.isNotBlank() == true -> activityAnnotation.name
                else -> method.name
            }

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
