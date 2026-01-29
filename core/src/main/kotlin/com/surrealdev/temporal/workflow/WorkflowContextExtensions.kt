package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize
import io.temporal.api.common.v1.Payloads
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
 * ```
 *
 * @throws IllegalStateException if called outside of a workflow execution
 */
suspend fun workflow(): WorkflowContext =
    currentCoroutineContext()[WorkflowContext]
        ?: error("workflow() must be called from within a workflow execution")

// =============================================================================
// Logging
// =============================================================================

/**
 * Returns an SLF4J logger for this workflow.
 *
 * The logger name is based on the workflow type (e.g., "temporal.workflow.MyWorkflow").
 * MDC context (workflowId, runId, taskQueue, namespace, workflowType) is automatically
 * populated by the framework and will be included in log output.
 *
 * Example:
 * ```kotlin
 * @WorkflowRun
 * suspend fun WorkflowContext.run(name: String): String {
 *     val log = logger()
 *     log.info("Starting workflow with name: {}", name)
 *     return "Hello, $name"
 * }
 * ```
 */
fun WorkflowContext.logger(): Logger = LoggerFactory.getLogger("temporal.workflow.${info.workflowType}")

// =============================================================================
// Type Extraction Helpers
// =============================================================================

/**
 * Extracts the workflow type name from a workflow class.
 *
 * Uses the @Workflow annotation's name if present and non-blank,
 * otherwise falls back to the class simple name.
 */
fun KClass<*>.getWorkflowType(): String {
    val annotation = this.findAnnotation<Workflow>()
    return when {
        annotation?.name?.isNotBlank() == true -> annotation.name
        else -> this.simpleName ?: error("Cannot determine workflow type from anonymous class")
    }
}

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
    this.startChildWorkflowWithPayloads(
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

    return this.startChildWorkflowWithPayloads(
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
    this.startChildWorkflowWithPayloads(
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

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

// =============================================================================
// Child Workflow Extensions - KClass-Based
// =============================================================================

/**
 * Starts a child workflow using a workflow class reference without arguments.
 *
 * The workflow type is automatically determined from the @Workflow annotation
 * or the class name.
 *
 * @param R The expected result type of the child workflow
 * @param workflowClass The workflow class annotated with @Workflow
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> =
    this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )

/**
 * Starts a child workflow using a workflow class reference with a single argument.
 *
 * @param R The expected result type of the child workflow
 * @param T The type of the argument
 * @param workflowClass The workflow class annotated with @Workflow
 * @param arg The argument to pass to the child workflow
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R, reified T> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    arg: T,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> {
    val payloads =
        Payloads
            .newBuilder()
            .addPayloads(serializer.serialize(arg))
            .build()

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = payloads,
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a child workflow using a workflow class reference with two arguments.
 *
 * @param R The expected result type of the child workflow
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param workflowClass The workflow class annotated with @Workflow
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R, reified T1, reified T2> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    arg1: T1,
    arg2: T2,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> {
    val payloads =
        Payloads
            .newBuilder()
            .addPayloads(serializer.serialize(arg1))
            .addPayloads(serializer.serialize(arg2))
            .build()

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = payloads,
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a child workflow using a workflow class reference with three arguments.
 *
 * @param R The expected result type of the child workflow
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param workflowClass The workflow class annotated with @Workflow
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> {
    val payloads =
        Payloads
            .newBuilder()
            .addPayloads(serializer.serialize(arg1))
            .addPayloads(serializer.serialize(arg2))
            .addPayloads(serializer.serialize(arg3))
            .build()

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = payloads,
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a child workflow using a workflow class reference with four arguments.
 *
 * @param R The expected result type of the child workflow
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param T4 The type of the fourth argument
 * @param workflowClass The workflow class annotated with @Workflow
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @param arg4 The fourth argument
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3, reified T4> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle<R> {
    val payloads =
        Payloads
            .newBuilder()
            .addPayloads(serializer.serialize(arg1))
            .addPayloads(serializer.serialize(arg2))
            .addPayloads(serializer.serialize(arg3))
            .addPayloads(serializer.serialize(arg4))
            .build()

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = payloads,
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
): RemoteActivityHandle<R> =
    this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> =
    this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
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
    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
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

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
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

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
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

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> =
    this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
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
    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
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

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
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

    return this.startActivityWithPayloads(
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
): RemoteActivityHandle<R> {
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

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

// =============================================================================
// Local Activity Extensions - Full Options
// =============================================================================

/**
 * Starts a local activity without arguments using full LocalActivityOptions.
 *
 * Local activities run in the same worker process as the workflow.
 * They're useful for short operations that don't need server-side scheduling.
 *
 * @param R The expected result type of the local activity
 * @param activityType The activity type name (e.g., "greet")
 * @param options Configuration for the local activity
 * @return A handle to the local activity
 */
suspend inline fun <reified R> WorkflowContext.startLocalActivity(
    activityType: String,
    options: LocalActivityOptions,
): LocalActivityHandle<R> =
    this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )

/**
 * Starts a local activity with a single typed argument using full LocalActivityOptions.
 */
suspend inline fun <reified R, reified T> WorkflowContext.startLocalActivity(
    activityType: String,
    arg: T,
    options: LocalActivityOptions,
): LocalActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a local activity with two typed arguments using full LocalActivityOptions.
 */
suspend inline fun <reified R, reified T1, reified T2> WorkflowContext.startLocalActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    options: LocalActivityOptions,
): LocalActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a local activity with three typed arguments using full LocalActivityOptions.
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> WorkflowContext.startLocalActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: LocalActivityOptions,
): LocalActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

// =============================================================================
// Local Activity Extensions - Timeout Parameters
// =============================================================================

/**
 * Starts a local activity without arguments, specifying timeouts inline.
 *
 * At least one of startToCloseTimeout or scheduleToCloseTimeout must be non-null.
 *
 * @param R The expected result type of the local activity
 * @param activityType The activity type name
 * @param startToCloseTimeout Maximum time for a single execution attempt
 * @param scheduleToCloseTimeout Maximum time from scheduling to completion (including retries)
 * @param scheduleToStartTimeout Maximum time from scheduling to worker pickup
 * @param retryPolicy Retry policy for the local activity
 * @param activityId Custom activity ID (auto-generated if null)
 * @param localRetryThreshold If backoff exceeds this, lang schedules timer (default 1 minute)
 * @param cancellationType How to handle cancellation (default WAIT_CANCELLATION_COMPLETED)
 * @return A handle to the local activity
 */
suspend inline fun <reified R> WorkflowContext.startLocalActivity(
    activityType: String,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle<R> {
    val options =
        LocalActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            retryPolicy = retryPolicy,
            activityId = activityId,
            localRetryThreshold = localRetryThreshold,
            cancellationType = cancellationType,
        )
    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a local activity with a single typed argument, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T> WorkflowContext.startLocalActivity(
    activityType: String,
    arg: T,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle<R> {
    val options =
        LocalActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            retryPolicy = retryPolicy,
            activityId = activityId,
            localRetryThreshold = localRetryThreshold,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a local activity with two typed arguments, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T1, reified T2> WorkflowContext.startLocalActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle<R> {
    val options =
        LocalActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            retryPolicy = retryPolicy,
            activityId = activityId,
            localRetryThreshold = localRetryThreshold,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a local activity with three typed arguments, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T1, reified T2, reified T3> WorkflowContext.startLocalActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle<R> {
    val options =
        LocalActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            retryPolicy = retryPolicy,
            activityId = activityId,
            localRetryThreshold = localRetryThreshold,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

// =============================================================================
// Local Activity Extensions - KFunction-Based
// =============================================================================

/**
 * Starts a local activity using a function reference with full LocalActivityOptions.
 *
 * The activity type is automatically determined from the @Activity annotation
 * or the function name.
 */
suspend inline fun <reified R> WorkflowContext.startLocalActivity(
    activityFunc: KFunction<*>,
    options: LocalActivityOptions,
): LocalActivityHandle<R> =
    this.startLocalActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )

/**
 * Starts a local activity using a function reference with a single argument.
 */
suspend inline fun <reified R, reified T> WorkflowContext.startLocalActivity(
    activityFunc: KFunction<*>,
    arg: T,
    options: LocalActivityOptions,
): LocalActivityHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startLocalActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a local activity using a function reference, specifying timeouts inline.
 */
suspend inline fun <reified R> WorkflowContext.startLocalActivity(
    activityFunc: KFunction<*>,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle<R> {
    val options =
        LocalActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            retryPolicy = retryPolicy,
            activityId = activityId,
            localRetryThreshold = localRetryThreshold,
            cancellationType = cancellationType,
        )
    return this.startLocalActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = Payloads.getDefaultInstance(),
        options = options,
        returnType = typeOf<R>(),
    )
}

/**
 * Starts a local activity using a function reference with a single argument, specifying timeouts inline.
 */
suspend inline fun <reified R, reified T> WorkflowContext.startLocalActivity(
    activityFunc: KFunction<*>,
    arg: T,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle<R> {
    val options =
        LocalActivityOptions(
            startToCloseTimeout = startToCloseTimeout,
            scheduleToCloseTimeout = scheduleToCloseTimeout,
            scheduleToStartTimeout = scheduleToStartTimeout,
            retryPolicy = retryPolicy,
            activityId = activityId,
            localRetryThreshold = localRetryThreshold,
            cancellationType = cancellationType,
        )
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startLocalActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
        returnType = typeOf<R>(),
    )
}

// =============================================================================
// Handler Extensions
// =============================================================================

/**
 * Registers or replaces a query handler at runtime with a single typed argument.
 *
 * This is a type-safe wrapper around [WorkflowContext.setQueryHandlerWithPayloads] that handles
 * serialization and deserialization automatically.
 *
 * @param T The argument type of the query
 * @param R The return type of the query
 * @param name The query name to register
 * @param handler The handler function that receives the typed argument and returns a typed result
 */
inline fun <reified T : Any, reified R> WorkflowContext.setQueryHandler(
    name: String,
    crossinline handler: (suspend (T) -> R),
) {
    setQueryHandlerWithPayloads(name) { argsPayloads ->
        if (argsPayloads.size != 1) {
            error("Expected exactly one argument payload for query handler '$name', but got ${argsPayloads.size}")
        }
        val arg = serializer.deserialize<T>(argsPayloads[0])
        val result: R = handler(arg)
        serializer.serialize<R>(result)
    }
}

/**
 * Registers or replaces a signal handler at runtime with a single typed argument.
 *
 * This is a type-safe wrapper around [WorkflowContext.setSignalHandlerWithPayloads] that handles
 * deserialization automatically.
 *
 * @param T The argument type of the signal
 * @param name The signal name to register
 * @param handler The handler function that receives the typed argument
 */
inline fun <reified T : Any> WorkflowContext.setSignalHandler(
    name: String,
    crossinline handler: (suspend (T) -> Unit),
) {
    setSignalHandlerWithPayloads(name) { argsPayloads ->
        if (argsPayloads.size != 1) {
            error("Expected exactly one argument payload for signal handler '$name', but got ${argsPayloads.size}")
        }
        val arg = serializer.deserialize<T>(argsPayloads[0])
        handler(arg)
    }
}

/**
 * Registers or replaces an update handler at runtime with a single typed argument.
 *
 * This is a type-safe wrapper around [WorkflowContext.setUpdateHandlerWithPayloads] that handles
 * serialization and deserialization automatically.
 *
 * @param T The argument type of the update
 * @param R The return type of the update
 * @param name The update name to register
 * @param handler The suspend function to handle the update, receiving the typed argument and returning a typed result
 * @param validator An optional synchronous validator that runs before the handler (in read-only mode)
 */
inline fun <reified T : Any, reified R> WorkflowContext.setUpdateHandler(
    name: String,
    crossinline handler: (suspend (T) -> R),
    noinline validator: ((T) -> Unit)? = null,
) {
    setUpdateHandlerWithPayloads(
        name,
        handler = { argsPayloads ->
            if (argsPayloads.size != 1) {
                error("Expected exactly one argument payload for update handler '$name', but got ${argsPayloads.size}")
            }
            val arg = serializer.deserialize<T>(argsPayloads[0])
            val result: R = handler(arg)
            serializer.serialize<R>(result)
        },
        validator =
            validator?.let { validator ->
                { argsPayloads ->
                    if (argsPayloads.size != 1) {
                        error(
                            "Expected exactly one argument payload for update validator '$name', but got ${argsPayloads.size}",
                        )
                    }
                    val arg = serializer.deserialize<T>(argsPayloads[0])
                    validator(arg)
                }
            },
    )
}

// =============================================================================
// Continue-As-New Extensions
// =============================================================================

/**
 * Continues the workflow as a new execution without arguments.
 *
 * This function never returns - it throws [ContinueAsNewException] which is
 * caught by the workflow executor to generate the appropriate command.
 *
 * After calling this, the current workflow run will complete and a new run
 * will start with the same workflow ID but a new run ID.
 *
 * **Important:** Do not catch [ContinueAsNewException] in workflow code.
 *
 * Example:
 * ```kotlin
 * @WorkflowRun
 * suspend fun WorkflowContext.run(iteration: Int): String {
 *     if (iteration >= 100) {
 *         return "completed after $iteration iterations"
 *     }
 *     // Continue to next iteration
 *     continueAsNew(iteration + 1)
 * }
 * ```
 *
 * @param options Configuration for the new execution (workflow type, task queue, timeouts, etc.)
 * @throws ContinueAsNewException Always - this exception triggers the continue-as-new
 */
fun WorkflowContext.continueAsNew(options: ContinueAsNewOptions = ContinueAsNewOptions()): Nothing =
    throw ContinueAsNewException(options, emptyList())

/**
 * Continues the workflow as a new execution with a single typed argument.
 *
 * @param T The type of the argument
 * @param arg The argument to pass to the new execution
 * @param options Configuration for the new execution
 * @throws ContinueAsNewException Always
 */
inline fun <reified T> WorkflowContext.continueAsNew(
    arg: T,
    options: ContinueAsNewOptions = ContinueAsNewOptions(),
): Nothing = throw ContinueAsNewException(options, listOf(typeOf<T>() to arg))

/**
 * Continues the workflow as a new execution with two typed arguments.
 *
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param options Configuration for the new execution
 * @throws ContinueAsNewException Always
 */
inline fun <reified T1, reified T2> WorkflowContext.continueAsNew(
    arg1: T1,
    arg2: T2,
    options: ContinueAsNewOptions = ContinueAsNewOptions(),
): Nothing =
    throw ContinueAsNewException(
        options,
        listOf(
            typeOf<T1>() to arg1,
            typeOf<T2>() to arg2,
        ),
    )

/**
 * Continues the workflow as a new execution with three typed arguments.
 *
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @param options Configuration for the new execution
 * @throws ContinueAsNewException Always
 */
inline fun <reified T1, reified T2, reified T3> WorkflowContext.continueAsNew(
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ContinueAsNewOptions = ContinueAsNewOptions(),
): Nothing =
    throw ContinueAsNewException(
        options,
        listOf(
            typeOf<T1>() to arg1,
            typeOf<T2>() to arg2,
            typeOf<T3>() to arg3,
        ),
    )

/**
 * Continues the workflow as a new execution with four typed arguments.
 *
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param T4 The type of the fourth argument
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @param arg4 The fourth argument
 * @param options Configuration for the new execution
 * @throws ContinueAsNewException Always
 */
inline fun <reified T1, reified T2, reified T3, reified T4> WorkflowContext.continueAsNew(
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4,
    options: ContinueAsNewOptions = ContinueAsNewOptions(),
): Nothing =
    throw ContinueAsNewException(
        options,
        listOf(
            typeOf<T1>() to arg1,
            typeOf<T2>() to arg2,
            typeOf<T3>() to arg3,
            typeOf<T4>() to arg4,
        ),
    )

// =============================================================================
// Continue-As-New Extensions - KClass-Based (Type-Safe Workflow Target)
// =============================================================================

/**
 * Continues as a new execution of a different workflow type, without arguments.
 *
 * The workflow type is automatically determined from the @Workflow annotation
 * on the target workflow class.
 *
 * Example:
 * ```kotlin
 * @WorkflowRun
 * suspend fun WorkflowContext.run(): String {
 *     // Continue as a different workflow type
 *     continueAsNewTo(TargetWorkflow::class)
 * }
 * ```
 *
 * @param workflowClass The target workflow class annotated with @Workflow
 * @param options Additional options (task queue, timeouts, etc.)
 * @throws ContinueAsNewException Always
 */
fun WorkflowContext.continueAsNewTo(
    workflowClass: KClass<*>,
    options: ContinueAsNewOptions = ContinueAsNewOptions(),
): Nothing {
    val effectiveOptions =
        options.copy(
            workflowType = options.workflowType ?: workflowClass.getWorkflowType(),
        )
    throw ContinueAsNewException(effectiveOptions, emptyList())
}

/**
 * Continues as a new execution of a different workflow type with a single argument.
 *
 * @param T The type of the argument
 * @param workflowClass The target workflow class annotated with @Workflow
 * @param arg The argument to pass to the new execution
 * @param options Additional options (task queue, timeouts, etc.)
 * @throws ContinueAsNewException Always
 */
inline fun <reified T> WorkflowContext.continueAsNewTo(
    workflowClass: KClass<*>,
    arg: T,
    options: ContinueAsNewOptions = ContinueAsNewOptions(),
): Nothing {
    val effectiveOptions =
        options.copy(
            workflowType = options.workflowType ?: workflowClass.getWorkflowType(),
        )
    throw ContinueAsNewException(effectiveOptions, listOf(typeOf<T>() to arg))
}

/**
 * Continues as a new execution of a different workflow type with two arguments.
 *
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param workflowClass The target workflow class annotated with @Workflow
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param options Additional options (task queue, timeouts, etc.)
 * @throws ContinueAsNewException Always
 */
inline fun <reified T1, reified T2> WorkflowContext.continueAsNewTo(
    workflowClass: KClass<*>,
    arg1: T1,
    arg2: T2,
    options: ContinueAsNewOptions = ContinueAsNewOptions(),
): Nothing {
    val effectiveOptions =
        options.copy(
            workflowType = options.workflowType ?: workflowClass.getWorkflowType(),
        )
    throw ContinueAsNewException(
        effectiveOptions,
        listOf(
            typeOf<T1>() to arg1,
            typeOf<T2>() to arg2,
        ),
    )
}

/**
 * Continues as a new execution of a different workflow type with three arguments.
 *
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param T3 The type of the third argument
 * @param workflowClass The target workflow class annotated with @Workflow
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param arg3 The third argument
 * @param options Additional options (task queue, timeouts, etc.)
 * @throws ContinueAsNewException Always
 */
inline fun <reified T1, reified T2, reified T3> WorkflowContext.continueAsNewTo(
    workflowClass: KClass<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ContinueAsNewOptions = ContinueAsNewOptions(),
): Nothing {
    val effectiveOptions =
        options.copy(
            workflowType = options.workflowType ?: workflowClass.getWorkflowType(),
        )
    throw ContinueAsNewException(
        effectiveOptions,
        listOf(
            typeOf<T1>() to arg1,
            typeOf<T2>() to arg2,
            typeOf<T3>() to arg3,
        ),
    )
}
