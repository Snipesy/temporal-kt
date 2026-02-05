package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.common.SearchAttributesBuilder
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.searchAttributes
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize
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
 * @param workflowType The child workflow type name
 * @param args Pre-serialized arguments (use [Payloads.newBuilder])
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
suspend fun WorkflowContext.startChildWorkflow(
    workflowType: String,
    args: TemporalPayloads,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle =
    this.startChildWorkflowWithPayloads(
        workflowType = workflowType,
        args = args,
        options = options,
    )

/**
 * Starts a child workflow with a single typed argument.
 *
 * @param T The type of the argument
 * @param workflowType The child workflow type name
 * @param arg The argument to pass to the child workflow
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startChildWorkflow(
    workflowType: String,
    arg: T,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle {
    val payload = serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts a child workflow without arguments.
 *
 * @param workflowType The child workflow type name
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startChildWorkflow(
    workflowType: String,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle =
    this.startChildWorkflowWithPayloads(
        workflowType = workflowType,
        args = TemporalPayloads.EMPTY,
        options = options,
    )

/**
 * Starts a child workflow with two typed arguments.
 *
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param workflowType The child workflow type name
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2> WorkflowContext.startChildWorkflow(
    workflowType: String,
    arg1: T1,
    arg2: T2,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowType,
        args = payloads,
        options = options,
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
 * @param workflowClass The workflow class annotated with @Workflow
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle =
    this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = TemporalPayloads.EMPTY,
        options = options,
    )

/**
 * Starts a child workflow using a workflow class reference with a single argument.
 *
 * @param T The type of the argument
 * @param workflowClass The workflow class annotated with @Workflow
 * @param arg The argument to pass to the child workflow
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    arg: T,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle {
    val payload = serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = payloads,
        options = options,
    )
}

/**
 * Starts a child workflow using a workflow class reference with two arguments.
 *
 * @param T1 The type of the first argument
 * @param T2 The type of the second argument
 * @param workflowClass The workflow class annotated with @Workflow
 * @param arg1 The first argument
 * @param arg2 The second argument
 * @param options Configuration for the child workflow
 * @return A handle to the child workflow
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    arg1: T1,
    arg2: T2,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = payloads,
        options = options,
    )
}

/**
 * Starts a child workflow using a workflow class reference with three arguments.
 *
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
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payload3 = serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = payloads,
        options = options,
    )
}

/**
 * Starts a child workflow using a workflow class reference with four arguments.
 *
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
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2, reified T3, reified T4> WorkflowContext.startChildWorkflow(
    workflowClass: KClass<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payload3 = serializer.serialize(arg3)
    val payload4 = serializer.serialize(arg4)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3, payload4))

    return this.startChildWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        args = payloads,
        options = options,
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
 * @param activityType The activity type name (e.g., "greet")
 * @param options Configuration for the activity (must have at least one timeout set)
 * @return A handle to the activity
 */
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startActivity(
    activityType: String,
    options: ActivityOptions,
): RemoteActivityHandle =
    this.startActivityWithPayloads(
        activityType = activityType,
        args = TemporalPayloads.EMPTY,
        options = options,
    )

/**
 * Starts an activity with a single typed argument using full ActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startActivity(
    activityType: String,
    arg: T,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payload = this.serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity with two typed arguments using full ActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity with three typed arguments using full ActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payload3 = this.serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity with pre-serialized arguments using full ActivityOptions.
 */
suspend fun WorkflowContext.startActivity(
    activityType: String,
    args: TemporalPayloads,
    options: ActivityOptions,
): RemoteActivityHandle =
    this.startActivityWithPayloads(
        activityType = activityType,
        args = args,
        options = options,
    )

// =============================================================================
// Activity Extensions - Timeout Parameters
// =============================================================================

/**
 * Starts an activity without arguments, specifying timeouts inline.
 *
 * At least one of startToCloseTimeout or scheduleToCloseTimeout must be non-null.
 *
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
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startActivity(
    activityType: String,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): RemoteActivityHandle {
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
        args = TemporalPayloads.EMPTY,
        options = options,
    )
}

/**
 * Starts an activity with a single typed argument, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startActivity(
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
): RemoteActivityHandle {
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
    val payload = this.serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity with two typed arguments, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivity(
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
): RemoteActivityHandle {
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
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity with three typed arguments, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivity(
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
): RemoteActivityHandle {
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
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payload3 = this.serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
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
 * @param activityFunc The function reference annotated with @Activity
 * @param options Configuration for the activity
 * @return A handle to the activity
 */
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    options: ActivityOptions,
): RemoteActivityHandle =
    this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = TemporalPayloads.EMPTY,
        options = options,
    )

/**
 * Starts an activity using a function reference with a single argument and full ActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg: T,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payload = this.serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity using a function reference with two arguments and full ActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity using a function reference with three arguments and full ActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payload3 = this.serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloads,
        options = options,
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
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    heartbeatTimeout: Duration? = null,
    taskQueue: String? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
): RemoteActivityHandle {
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
        args = TemporalPayloads.EMPTY,
        options = options,
    )
}

/**
 * Starts an activity using a function reference with a single argument, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startActivity(
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
): RemoteActivityHandle {
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
    val payload = this.serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity using a function reference with two arguments, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivity(
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
): RemoteActivityHandle {
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
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloads,
        options = options,
    )
}

/**
 * Starts an activity using a function reference with three arguments, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivity(
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
): RemoteActivityHandle {
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
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payload3 = this.serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloads,
        options = options,
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
 * @param activityType The activity type name (e.g., "greet")
 * @param options Configuration for the local activity
 * @return A handle to the local activity
 */
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startLocalActivity(
    activityType: String,
    options: LocalActivityOptions,
): LocalActivityHandle =
    this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = TemporalPayloads.EMPTY,
        options = options,
    )

/**
 * Starts a local activity with a single typed argument using full LocalActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startLocalActivity(
    activityType: String,
    arg: T,
    options: LocalActivityOptions,
): LocalActivityHandle {
    val payload = this.serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts a local activity with two typed arguments using full LocalActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2> WorkflowContext.startLocalActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    options: LocalActivityOptions,
): LocalActivityHandle {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts a local activity with three typed arguments using full LocalActivityOptions.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startLocalActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: LocalActivityOptions,
): LocalActivityHandle {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payload3 = this.serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
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
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startLocalActivity(
    activityType: String,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle {
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
        args = TemporalPayloads.EMPTY,
        options = options,
    )
}

/**
 * Starts a local activity with a single typed argument, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startLocalActivity(
    activityType: String,
    arg: T,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle {
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
    val payload = this.serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts a local activity with two typed arguments, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2> WorkflowContext.startLocalActivity(
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
): LocalActivityHandle {
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
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
    )
}

/**
 * Starts a local activity with three typed arguments, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startLocalActivity(
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
): LocalActivityHandle {
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
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payload3 = this.serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startLocalActivityWithPayloads(
        activityType = activityType,
        args = payloads,
        options = options,
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
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startLocalActivity(
    activityFunc: KFunction<*>,
    options: LocalActivityOptions,
): LocalActivityHandle =
    this.startLocalActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = TemporalPayloads.EMPTY,
        options = options,
    )

/**
 * Starts a local activity using a function reference with a single argument.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startLocalActivity(
    activityFunc: KFunction<*>,
    arg: T,
    options: LocalActivityOptions,
): LocalActivityHandle {
    val payload = this.serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startLocalActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloads,
        options = options,
    )
}

/**
 * Starts a local activity using a function reference, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend fun WorkflowContext.startLocalActivity(
    activityFunc: KFunction<*>,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle {
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
        args = TemporalPayloads.EMPTY,
        options = options,
    )
}

/**
 * Starts a local activity using a function reference with a single argument, specifying timeouts inline.
 */
@OptIn(InternalTemporalApi::class)
suspend inline fun <reified T> WorkflowContext.startLocalActivity(
    activityFunc: KFunction<*>,
    arg: T,
    startToCloseTimeout: Duration? = null,
    scheduleToCloseTimeout: Duration? = null,
    scheduleToStartTimeout: Duration? = null,
    retryPolicy: RetryPolicy? = null,
    activityId: String? = null,
    localRetryThreshold: Duration = 1.minutes,
    cancellationType: ActivityCancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
): LocalActivityHandle {
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
    val payload = this.serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startLocalActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloads,
        options = options,
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
@OptIn(InternalTemporalApi::class)
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
@OptIn(InternalTemporalApi::class)
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
@OptIn(InternalTemporalApi::class)
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

// =============================================================================
// Search Attributes Extensions
// =============================================================================

/**
 * Updates search attributes for this workflow execution using the DSL builder.
 *
 * This merges the provided attributes with existing ones.
 * To remove an attribute, set its value to null.
 *
 * Example:
 * ```kotlin
 * upsertSearchAttributes {
 *     CUSTOMER_STATUS to "premium"
 *     ORDER_COUNT to currentOrderCount
 *     OLD_ATTRIBUTE to null  // Removes this attribute
 * }
 * ```
 *
 * @param block DSL builder block to define search attributes
 */
suspend fun WorkflowContext.upsertSearchAttributes(block: SearchAttributesBuilder.() -> Unit) {
    upsertSearchAttributes(searchAttributes(block))
}
