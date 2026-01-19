package com.surrealdev.temporal.activity.internal

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.ActivityMethod
import com.surrealdev.temporal.application.ActivityRegistration
import kotlin.reflect.KClass
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
internal data class ActivityMethodInfo(
    /** The full activity type name (e.g., "GreetingActivity::greet"). */
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
 * Scans activity classes for methods annotated with @ActivityMethod and
 * provides lookup by activity type name.
 */
internal class ActivityRegistry {
    private val methods = mutableMapOf<String, ActivityMethodInfo>()

    /**
     * Registers an activity implementation.
     *
     * Scans the implementation for @ActivityMethod annotations and registers each method.
     *
     * @param registration The activity registration containing the implementation
     * @throws IllegalArgumentException if no activity methods are found or duplicate types exist
     */
    fun register(registration: ActivityRegistration) {
        val implementation = registration.implementation
        val klass = implementation::class

        // Get the activity prefix from @Activity annotation or use the provided type
        val activityAnnotation = klass.findAnnotation<Activity>()
        val activityPrefix =
            when {
                registration.activityType.isNotBlank() -> registration.activityType
                activityAnnotation?.name?.isNotBlank() == true -> activityAnnotation.name
                else -> klass.simpleName ?: error("Cannot determine activity type for ${klass.qualifiedName}")
            }

        // Find all methods annotated with @ActivityMethod
        val activityMethods = klass.declaredFunctions.filter { it.hasAnnotation<ActivityMethod>() }

        if (activityMethods.isEmpty()) {
            // If no @ActivityMethod annotations, check if class has @Activity
            // and treat all public methods as activity methods (convenience mode)
            if (activityAnnotation != null) {
                registerAllPublicMethods(klass, implementation, activityPrefix)
            } else {
                throw IllegalArgumentException(
                    "Activity class ${klass.qualifiedName} has no @ActivityMethod annotations. " +
                        "Either annotate methods with @ActivityMethod or the class with @Activity.",
                )
            }
        } else {
            // Register explicitly annotated methods
            for (method in activityMethods) {
                registerMethod(method, implementation, activityPrefix)
            }
        }
    }

    private fun registerAllPublicMethods(
        klass: KClass<*>,
        instance: Any,
        activityPrefix: String,
    ) {
        val publicMethods =
            klass.declaredFunctions.filter {
                // Exclude standard Object methods and internal methods
                it.name !in setOf("equals", "hashCode", "toString", "copy") &&
                    !it.name.startsWith("component")
            }

        for (method in publicMethods) {
            registerMethod(method, instance, activityPrefix)
        }
    }

    private fun registerMethod(
        method: KFunction<*>,
        instance: Any,
        activityPrefix: String,
    ) {
        method.isAccessible = true

        val methodAnnotation = method.findAnnotation<ActivityMethod>()
        val methodName =
            when {
                methodAnnotation?.name?.isNotBlank() == true -> methodAnnotation.name
                else -> method.name
            }

        // Build full activity type: "ActivityClass::methodName"
        val activityType = "$activityPrefix::$methodName"

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
     * @param activityType The activity type name (e.g., "GreetingActivity::greet")
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
