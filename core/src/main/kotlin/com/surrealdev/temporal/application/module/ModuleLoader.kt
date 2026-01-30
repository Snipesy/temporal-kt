package com.surrealdev.temporal.application.module

import com.surrealdev.temporal.application.TemporalApplication
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Type alias for a Temporal module function.
 * Modules are extension functions on [TemporalApplication] that configure
 * task queues, workflows, and activities.
 *
 * Example module:
 * ```kotlin
 * fun TemporalApplication.myModule() {
 *     taskQueue("my-queue") {
 *         workflow<MyWorkflow>()
 *     }
 * }
 * ```
 */
typealias TemporalModule = TemporalApplication.() -> Unit

/**
 * Loads Temporal modules by their fully-qualified function names using reflection.
 *
 * Module names should be in the format: `com.example.ModuleFileKt.moduleFunctionName`
 *
 * For a function defined as:
 * ```kotlin
 * // File: com/example/OrdersModule.kt
 * package com.example
 *
 * fun TemporalApplication.ordersModule() { ... }
 * ```
 *
 * The qualified name would be: `com.example.OrdersModuleKt.ordersModule`
 *
 * Note: Kotlin compiles top-level functions into a class named `<FileName>Kt`.
 */
internal object ModuleLoader {
    /**
     * Loads a single module by its fully-qualified name.
     *
     * @param qualifiedName The fully-qualified function name (e.g., "com.example.ModuleKt.myModule")
     * @return The loaded [TemporalModule]
     * @throws ModuleLoadException if the module cannot be loaded
     */
    fun loadModule(qualifiedName: String): TemporalModule {
        val lastDotIndex = qualifiedName.lastIndexOf('.')
        if (lastDotIndex == -1) {
            throw ModuleLoadException(
                "Invalid module name: $qualifiedName. " +
                    "Expected format: com.example.ModuleKt.functionName",
            )
        }

        val className = qualifiedName.substring(0, lastDotIndex)
        val functionName = qualifiedName.substring(lastDotIndex + 1)

        val clazz =
            try {
                Class.forName(className)
            } catch (e: ClassNotFoundException) {
                throw ModuleLoadException(
                    "Module class not found: $className. " +
                        "Make sure the class is on the classpath.",
                    e,
                )
            }

        // Top-level extension functions are compiled as static methods
        // with the receiver as the first parameter
        val method: Method =
            clazz.declaredMethods.find { method ->
                method.name == functionName &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0].isAssignableFrom(TemporalApplication::class.java)
            } ?: throw ModuleLoadException(
                "Module function not found: $functionName in $className. " +
                    "Make sure it is an extension function on TemporalApplication.",
            )

        return {
            try {
                method.invoke(null, this)
            } catch (e: Exception) {
                throw ModuleLoadException(
                    "Failed to execute module: $qualifiedName",
                    e.cause ?: e,
                )
            }
        }
    }

    /**
     * Loads multiple modules by their fully-qualified names.
     *
     * @param qualifiedNames List of fully-qualified function names
     * @return List of loaded [TemporalModule]s in the same order
     * @throws ModuleLoadException if any module cannot be loaded
     */
    fun loadModules(qualifiedNames: List<String>): List<TemporalModule> = qualifiedNames.map { loadModule(it) }
}

/**
 * Exception thrown when module loading fails.
 */
class ModuleLoadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
