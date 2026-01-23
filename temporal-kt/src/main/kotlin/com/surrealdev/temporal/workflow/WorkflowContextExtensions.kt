package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.serialization.serialize
import io.temporal.api.common.v1.Payloads
import kotlinx.coroutines.currentCoroutineContext
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.typeOf
import kotlin.time.Duration

/*
 * Extensions and utilities for WorkflowContext.
 */

// =============================================================================
// Coroutine Context Access
// =============================================================================

/**
 * Returns the current [WorkflowContext] from the coroutine context.
 *
 * This is useful when you want to access the workflow context withotu carrying around the
 * WorkflowContext scope reference everywhere.
 *
 * It is safe to use this to launch coroutines within workflows.
 *
 * ```kotlin
 * val workflowContext = workflow()
 * workflowContext.launch {
 *    // do something
 * }
 *
 * @throws IllegalStateException if called outside of a workflow execution
 */
suspend fun workflow(): WorkflowContext =
    currentCoroutineContext()[WorkflowContext]
        ?: error("workflow() must be called from within a workflow execution")

// =============================================================================
// Child Workflow Extensions
// =============================================================================

/**
 * Starts a child workflow and returns a handle for tracking.
 *
 * The child workflow is started immediately but this method returns
 * without waiting for completion. Use [ChildWorkflowHandle.result] to
 * await the result.
 *
 * @param R The expected result type of the child workflow
 * @param workflowType The child workflow type name
 * @param args Pre-serialized arguments (use [Payloads.newBuilder])
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R> WorkflowContext.startChildWorkflow(
    workflowType: String,
    args: Payloads,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> =
    this.startChildWorkflow(
        workflowType = workflowType,
        args = args,
        options = options,
        returnType = typeOf<R>(),
    )

/**
 * Starts a child workflow with a single typed argument.
 *
 * @param R The expected result type of the child workflow
 * @param T The type of the argument
 * @param workflowType The child workflow type name
 * @param arg The argument to pass to the child workflow
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R, reified T> WorkflowContext.startChildWorkflow(
    workflowType: String,
    arg: T,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(serializer.serialize(arg))

    return this.startChildWorkflow(
        workflowType = workflowType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a child workflow without arguments.
 *
 * @param R The expected result type of the child workflow
 * @param workflowType The child workflow type name
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R> WorkflowContext.startChildWorkflow(
    workflowType: String,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> =
    this.startChildWorkflow(
        workflowType = workflowType,
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )

/**
 * Starts a child workflow with two typed arguments.
 *
 * @param R The expected result type of the child workflow
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param workflowType The child workflow type name
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R, reified T1, reified T2> WorkflowContext.startChildWorkflow(
    workflowType: String,
    arg1: T1,
    arg2: T2,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(serializer.serialize(arg1))
    payloadsBuilder.addPayloads(serializer.serialize(arg2))

    return this.startChildWorkflow(
        workflowType = workflowType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/*
 * =============================================================================
 * Activity Extensions - Full Options
 * =============================================================================
 */

/**
 * Starts an activity without arguments using full ActivityOptions.
 *
 * For simpler cases, use the timeout-parameter overloads below.
 *
 * @param R The expected result type of the activity
 * @param activityType The activity type name (e.g., "greet")
 * @param options Configuration for the activity (must have at least one timeout set)
 * @return A handle to the activity
 */
suspend inline fun <reified R> WorkflowContext.startActivity(
    activityType: String,
    options: ActivityOptions,
): ActivityHandle<R> =
    this.startActivity(
        activityType = activityType,
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )

/**
 * Starts an activity with a single typed argument using full ActivityOptions.
 */
suspend inline fun <reified R, reified T> WorkflowContext.startActivity(
    activityType: String,
    arg: T,
    options: ActivityOptions,
): ActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivity(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity with two typed arguments using full ActivityOptions.
 */
suspend inline fun <reified R, reified T1, reified T2> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    options: ActivityOptions,
): ActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivity(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity with three typed arguments using full ActivityOptions.
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ActivityOptions,
): ActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivity(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity with pre-serialized arguments using full ActivityOptions.
 */
suspend inline fun <reified R> WorkflowContext.startActivity(
    activityType: String,
    args: Payloads,
    options: ActivityOptions,
): ActivityHandle<R> =
    this.startActivity(
        activityType = activityType,
        args = args,
        options = options,
        returnType = typeOf<R>(),
    )

// =============================================================================
// Activity Extensions - Timeout Parameters
// =============================================================================

/**
 * Starts an activity without arguments, specifying timeouts inline.
 *
 * At least one of startToCloseTimeout or scheduleToCloseTimeout must be non-null.
 *
 * @param R The expected result type of the activity
 * @param activityType The activity type name
 * @param startToCloseTimeout Maximum time for a single execution attempt
 * @param scheduleToCloseTimeout Maximum time from scheduling to completion (including retries)
 * @param scheduleToStartTimeout Maximum time from scheduling to worker pickup
 * @param heartbeatTimeout Maximum time between heartbeats (required for cancellation detection)
 * @param taskQueue Task queue to run the activity on
 * @param retryPolicy Retry policy for the activity
 * @param activityId Custom activity ID (auto-generated if null)
 * @param cancellationType How to handle cancellation
 * @return A handle to the activity
 */
suspend inline fun <reified R> WorkflowContext.startActivity(
    activityType: String,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): ActivityHandle<R> {
    val options =
        ActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            heartbeatTimeout = heartbeatTimeout,
            taskQueue = taskQueue,
            retryPolicy = retryPolicy,
            activityId = activityId,
            cancellationType = cancellationType,
        )
    return this.startActivity(
        activityType = activityType,
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity with a single typed argument, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T> WorkflowContext.startActivity(
    activityType: String,
    arg: T,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): ActivityHandle<R> {
    val options =
        ActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            heartbeatTimeout = heartbeatTimeout,
            taskQueue = taskQueue,
            retryPolicy = retryPolicy,
            activityId = activityId,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivity(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity with two typed arguments, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T1, reified T2> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): ActivityHandle<R> {
    val options =
        ActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            heartbeatTimeout = heartbeatTimeout,
            taskQueue = taskQueue,
            retryPolicy = retryPolicy,
            activityId = activityId,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivity(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity with three typed arguments, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): ActivityHandle<R> {
    val options =
        ActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            heartbeatTimeout = heartbeatTimeout,
            taskQueue = taskQueue,
            retryPolicy = retryPolicy,
            activityId = activityId,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivity(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

// =============================================================================
// Activity Extensions - Reflection-Based (KFunction)
// =============================================================================

/**
 * Extracts the activity type name from a function reference.
 *
 * Uses the @Activity annotation's name if present and non-blank,
 * otherwise falls back to the function name.
 */
fun KFunction<*>.getActivityType(): String {
    val annotation = this.findAnnotation<Activity>()
    return when {
        annotation?.name?.isNotBlank() == true -> annotation.name
        else -> this.name
    }
}

/**
 * Starts an activity using a function reference, with full ActivityOptions.
 *
 * The activity type is automatically determined from the @Activity annotation
 * or the function name.
 *
 * Example:
 * ```kotlin
 * val result = startActivity(
 *     MyActivities::greet,
 *     ActivityOptions(scheduleToCloseTimeout = 30.seconds)
 * ).result()
 * ```
 *
 * @param R The expected result type of the activity
 * @param activityFunc The function reference annotated with @Activity
 * @param options Configuration for the activity
 * @return A handle to the activity
 */
suspend inline fun <reified R> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    options: ActivityOptions,
): ActivityHandle<R> =
    this.startActivity(
        activityType = activityFunc.getActivityType(),
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )

/**
 * Starts an activity using a function reference with a single argument and full ActivityOptions.
 */
suspend inline fun <reified R, reified T> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg: T,
    options: ActivityOptions,
): ActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivity(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity using a function reference with two arguments and full ActivityOptions.
 */
suspend inline fun <reified R, reified T1, reified T2> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    options: ActivityOptions,
): ActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivity(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity using a function reference with three arguments and full ActivityOptions.
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ActivityOptions,
): ActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivity(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity using a function reference, specifying timeouts inline.
 *
 * The activity type is automatically determined from the @Activity annotation
 * or the function name.
 *
 * Example:
 * ```kotlin
 * val result = startActivity<String>(
 *     MyActivities::greet,
 *     scheduleToCloseTimeout = 30.seconds,
 *     heartbeatTimeout = 5.seconds
 * ).result()
 * ```
 *
 * @param R The expected result type of the activity
 * @param activityFunc The function reference annotated with @Activity
 * @param startToCloseTimeout Maximum time for a single execution attempt
 * @param scheduleToCloseTimeout Maximum time from scheduling to completion (including retries)
 * @param scheduleToStartTimeout Maximum time from scheduling to worker pickup
 * @param heartbeatTimeout Maximum time between heartbeats (required for cancellation detection)
 * @param taskQueue Task queue to run the activity on
 * @param retryPolicy Retry policy for the activity
 * @param activityId Custom activity ID (auto-generated if null)
 * @param cancellationType How to handle cancellation
 * @return A handle to the activity
 */
suspend inline fun <reified R> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): ActivityHandle<R> {
    val options =
        ActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            heartbeatTimeout = heartbeatTimeout,
            taskQueue = taskQueue,
            retryPolicy = retryPolicy,
            activityId = activityId,
            cancellationType = cancellationType,
        )
    return this.startActivity(
        activityType = activityFunc.getActivityType(),
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity using a function reference with a single argument, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg: T,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): ActivityHandle<R> {
    val options =
        ActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            heartbeatTimeout = heartbeatTimeout,
            taskQueue = taskQueue,
            retryPolicy = retryPolicy,
            activityId = activityId,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivity(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity using a function reference with two arguments, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T1, reified T2> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): ActivityHandle<R> {
    val options =
        ActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            heartbeatTimeout = heartbeatTimeout,
            taskQueue = taskQueue,
            retryPolicy = retryPolicy,
            activityId = activityId,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivity(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts an activity using a function reference with three arguments, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): ActivityHandle<R> {
    val options =
        ActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            heartbeatTimeout = heartbeatTimeout,
            taskQueue = taskQueue,
            retryPolicy = retryPolicy,
            activityId = activityId,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivity(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}
