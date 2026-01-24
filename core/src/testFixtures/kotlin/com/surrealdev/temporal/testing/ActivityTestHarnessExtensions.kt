package com.surrealdev.temporal.testing

import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.workflow.getActivityType
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf

/*
 * Extension functions for [ActivityTestHarness] providing typed argument handling.
 *
 * These extensions serialize arguments using reified type parameters to preserve
 * full type information including generics.
 */

/**
 * Executes an activity without arguments.
 *
 * @param R The expected result type
 * @param activityType The full activity type name (e.g., "GreetingActivity::greet")
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R> ActivityTestHarness.execute(activityType: String): R =
    executeWithPayloads(activityType, typeOf<R>(), emptyList())

/**
 * Executes an activity with a single typed argument.
 *
 * @param R The expected result type
 * @param T The type of the argument
 * @param activityType The full activity type name
 * @param arg The argument to pass to the activity
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R, reified T> ActivityTestHarness.execute(
    activityType: String,
    arg: T,
): R =
    executeWithPayloads(
        activityType,
        typeOf<R>(),
        listOf(serializer.serialize(arg)),
    )

/**
 * Executes an activity with two typed arguments.
 *
 * @param R The expected result type
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param activityType The full activity type name
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R, reified T1, reified T2> ActivityTestHarness.execute(
    activityType: String,
    arg1: T1,
    arg2: T2,
): R =
    executeWithPayloads(
        activityType,
        typeOf<R>(),
        listOf(
            serializer.serialize(arg1),
            serializer.serialize(arg2),
        ),
    )

/**
 * Executes an activity with three typed arguments.
 *
 * @param R The expected result type
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param activityType The full activity type name
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> ActivityTestHarness.execute(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
): R =
    executeWithPayloads(
        activityType,
        typeOf<R>(),
        listOf(
            serializer.serialize(arg1),
            serializer.serialize(arg2),
            serializer.serialize(arg3),
        ),
    )

/**
 * Executes an activity with four typed arguments.
 *
 * @param R The expected result type
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param T4 The type of the fourth argument
 * @param activityType The full activity type name
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @param arg4 The fourth argument
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3, reified T4> ActivityTestHarness.execute(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4,
): R =
    executeWithPayloads(
        activityType,
        typeOf<R>(),
        listOf(
            serializer.serialize(arg1),
            serializer.serialize(arg2),
            serializer.serialize(arg3),
            serializer.serialize(arg4),
        ),
    )

// =============================================================================
// KFunction-Based Overloads
// =============================================================================

/**
 * Executes an activity using a function reference without arguments.
 *
 * The activity type is automatically determined from the @Activity annotation
 * or the function name.
 *
 * Example:
 * ```kotlin
 * val result = execute<String>(MyActivities::greet)
 * ```
 *
 * @param R The expected result type
 * @param activityFunc The function reference annotated with @Activity
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R> ActivityTestHarness.execute(activityFunc: KFunction<*>): R =
    executeWithPayloads(activityFunc.getActivityType(), typeOf<R>(), emptyList())

/**
 * Executes an activity using a function reference with a single argument.
 *
 * @param R The expected result type
 * @param T The type of the argument
 * @param activityFunc The function reference annotated with @Activity
 * @param arg The argument to pass to the activity
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R, reified T> ActivityTestHarness.execute(
    activityFunc: KFunction<*>,
    arg: T,
): R =
    executeWithPayloads(
        activityFunc.getActivityType(),
        typeOf<R>(),
        listOf(serializer.serialize(arg)),
    )

/**
 * Executes an activity using a function reference with two arguments.
 *
 * @param R The expected result type
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param activityFunc The function reference annotated with @Activity
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R, reified T1, reified T2> ActivityTestHarness.execute(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
): R =
    executeWithPayloads(
        activityFunc.getActivityType(),
        typeOf<R>(),
        listOf(
            serializer.serialize(arg1),
            serializer.serialize(arg2),
        ),
    )

/**
 * Executes an activity using a function reference with three arguments.
 *
 * @param R The expected result type
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param activityFunc The function reference annotated with @Activity
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> ActivityTestHarness.execute(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
): R =
    executeWithPayloads(
        activityFunc.getActivityType(),
        typeOf<R>(),
        listOf(
            serializer.serialize(arg1),
            serializer.serialize(arg2),
            serializer.serialize(arg3),
        ),
    )

/**
 * Executes an activity using a function reference with four arguments.
 *
 * @param R The expected result type
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param T4 The type of the fourth argument
 * @param activityFunc The function reference annotated with @Activity
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @param arg4 The fourth argument
 * @return The deserialized result from the activity
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3, reified T4> ActivityTestHarness.execute(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4,
): R =
    executeWithPayloads(
        activityFunc.getActivityType(),
        typeOf<R>(),
        listOf(
            serializer.serialize(arg1),
            serializer.serialize(arg2),
            serializer.serialize(arg3),
            serializer.serialize(arg4),
        ),
    )
