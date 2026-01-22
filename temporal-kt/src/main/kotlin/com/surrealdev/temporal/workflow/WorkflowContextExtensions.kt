package com.surrealdev.temporal.workflow

import java.lang.reflect.Proxy

/*
 * Extensions and utilities for WorkflowContext.
 */

/**
 * Reified variant which accepts a type parameter for the activity interface (or implementation) class.
 *
 * Returning the interface.
 */
inline fun <reified T> WorkflowContext.activity(options: ActivityOptions): T {
    // use kreflect to see if this is annotated with @Activity
    val activityType =
        T::class
            .annotations
            .filterIsInstance<com.surrealdev.temporal.annotation.Activity>()
            .firstOrNull()

    if (activityType == null) {
        throw IllegalArgumentException("Activity class ${T::class.qualifiedName} is not annotated with @Activity")
    }
    // check if T is an interface
    if (!T::class.java.isInterface) {
        throw IllegalArgumentException("Activity type parameter T must be an interface")
    }

    // create proxy interface
    val proxy =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { proxy, method, args ->
        }

    TODO()
}
