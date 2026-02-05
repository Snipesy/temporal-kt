package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.serialization.serialize
import io.temporal.api.common.v1.Payloads
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.time.Duration

/*
 * Extensions for starting remote activities from workflows.
 */

// =============================================================================
// Type Extraction Helper
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

// =============================================================================
// Activity Extensions - Full Options
// =============================================================================

/**
 * Starts an activity without arguments using full ActivityOptions.
 *
 * For simpler cases, use the timeout-parameter overloads below.
 *
 * @param activityType The activity type name (e.g., "greet")
 * @param options Configuration for the activity (must have at least one timeout set)
 * @return A handle to the activity
 */
suspend fun WorkflowContext.startActivity(
    activityType: String,
    options: ActivityOptions,
): RemoteActivityHandle =
    this.startActivityWithPayloads(
        activityType = activityType,
        args = Payloads.getDefaultInstance(),
        options = options,
    )

/**
 * Starts an activity with a single typed argument using full ActivityOptions.
 */
suspend inline fun <reified T> WorkflowContext.startActivity(
    activityType: String,
    arg: T,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity with two typed arguments using full ActivityOptions.
 */
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity with three typed arguments using full ActivityOptions.
 */
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity with pre-serialized arguments using full ActivityOptions.
 */
suspend fun WorkflowContext.startActivity(
    activityType: String,
    args: Payloads,
    options: ActivityOptions,
): RemoteActivityHandle =
    this.startActivityWithPayloads(
        activityType = activityType,
        args = args,
        options = options,
    )

// =============================================================================
// Activity Extensions - scheduleToCloseTimeout Required
// =============================================================================

/**
 * Starts an activity without arguments, with scheduleToCloseTimeout required.
 *
 * @param activityType The activity type name
 * @param scheduleToCloseTimeout Maximum time from scheduling to completion (including retries) - REQUIRED
 * @param startToCloseTimeout Maximum time for a single execution attempt
 * @param scheduleToStartTimeout Maximum time from scheduling to worker pickup
 * @param heartbeatTimeout Maximum time between heartbeats (required for cancellation detection)
 * @param taskQueue Task queue to run the activity on
 * @param retryPolicy Retry policy for the activity
 * @param activityId Custom activity ID (auto-generated if null)
 * @param cancellationType How to handle cancellation
 * @return A handle to the activity
 */
suspend fun WorkflowContext.startActivity(
    activityType: String,
    scheduleToCloseTimeout: Duration,
    startToCloseTimeout: Duration? = null,
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
        args = Payloads.getDefaultInstance(),
        options = options,
    )
}

/**
 * Starts an activity with a single typed argument, with scheduleToCloseTimeout required.
 */
suspend inline fun <reified T> WorkflowContext.startActivity(
    activityType: String,
    arg: T,
    scheduleToCloseTimeout: Duration,
    startToCloseTimeout: Duration? = null,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity with two typed arguments, with scheduleToCloseTimeout required.
 */
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    scheduleToCloseTimeout: Duration,
    startToCloseTimeout: Duration? = null,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity with three typed arguments, with scheduleToCloseTimeout required.
 */
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    scheduleToCloseTimeout: Duration,
    startToCloseTimeout: Duration? = null,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

// =============================================================================
// Activity Extensions - startToCloseTimeout Required
// =============================================================================

/**
 * Starts an activity without arguments, with startToCloseTimeout required.
 *
 * @param activityType The activity type name
 * @param startToCloseTimeout Maximum time for a single execution attempt - REQUIRED
 * @param scheduleToCloseTimeout Maximum time from scheduling to completion (including retries)
 * @param scheduleToStartTimeout Maximum time from scheduling to worker pickup
 * @param heartbeatTimeout Maximum time between heartbeats (required for cancellation detection)
 * @param taskQueue Task queue to run the activity on
 * @param retryPolicy Retry policy for the activity
 * @param activityId Custom activity ID (auto-generated if null)
 * @param cancellationType How to handle cancellation
 * @return A handle to the activity
 */
suspend fun WorkflowContext.startActivityWithStartToClose(
    activityType: String,
    startToCloseTimeout: Duration,
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
        args = Payloads.getDefaultInstance(),
        options = options,
    )
}

/**
 * Starts an activity with a single typed argument, with startToCloseTimeout required.
 */
suspend inline fun <reified T> WorkflowContext.startActivityWithStartToClose(
    activityType: String,
    arg: T,
    startToCloseTimeout: Duration,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity with two typed arguments, with startToCloseTimeout required.
 */
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivityWithStartToClose(
    activityType: String,
    arg1: T1,
    arg2: T2,
    startToCloseTimeout: Duration,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity with three typed arguments, with startToCloseTimeout required.
 */
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivityWithStartToClose(
    activityType: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    startToCloseTimeout: Duration,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivityWithPayloads(
        activityType = activityType,
        args = payloadsBuilder.build(),
        options = options,
    )
}

// =============================================================================
// Activity Extensions - KFunction-Based with Full Options
// =============================================================================

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
suspend fun WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    options: ActivityOptions,
): RemoteActivityHandle =
    this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = Payloads.getDefaultInstance(),
        options = options,
    )

/**
 * Starts an activity using a function reference with a single argument and full ActivityOptions.
 */
suspend inline fun <reified T> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg: T,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity using a function reference with two arguments and full ActivityOptions.
 */
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity using a function reference with three arguments and full ActivityOptions.
 */
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: ActivityOptions,
): RemoteActivityHandle {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}

// =============================================================================
// Activity Extensions - KFunction-Based with scheduleToCloseTimeout Required
// =============================================================================

/**
 * Starts an activity using a function reference, with scheduleToCloseTimeout required.
 *
 * The activity type is automatically determined from the @Activity annotation
 * or the function name.
 *
 * Example:
 * ```kotlin
 * val result = startActivity(
 *     MyActivities::greet,
 *     scheduleToCloseTimeout = 30.seconds,
 *     heartbeatTimeout = 5.seconds
 * ).result()
 * ```
 *
 * @param activityFunc The function reference annotated with @Activity
 * @param scheduleToCloseTimeout Maximum time from scheduling to completion (including retries) - REQUIRED
 * @param startToCloseTimeout Maximum time for a single execution attempt
 * @param scheduleToStartTimeout Maximum time from scheduling to worker pickup
 * @param heartbeatTimeout Maximum time between heartbeats (required for cancellation detection)
 * @param taskQueue Task queue to run the activity on
 * @param retryPolicy Retry policy for the activity
 * @param activityId Custom activity ID (auto-generated if null)
 * @param cancellationType How to handle cancellation
 * @return A handle to the activity
 */
suspend fun WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    scheduleToCloseTimeout: Duration,
    startToCloseTimeout: Duration? = null,
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
        args = Payloads.getDefaultInstance(),
        options = options,
    )
}

/**
 * Starts an activity using a function reference with a single argument, with scheduleToCloseTimeout required.
 */
suspend inline fun <reified T> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg: T,
    scheduleToCloseTimeout: Duration,
    startToCloseTimeout: Duration? = null,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity using a function reference with two arguments, with scheduleToCloseTimeout required.
 */
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    scheduleToCloseTimeout: Duration,
    startToCloseTimeout: Duration? = null,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity using a function reference with three arguments, with scheduleToCloseTimeout required.
 */
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivity(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    scheduleToCloseTimeout: Duration,
    startToCloseTimeout: Duration? = null,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}

// =============================================================================
// Activity Extensions - KFunction-Based with startToCloseTimeout Required
// =============================================================================

/**
 * Starts an activity using a function reference, with startToCloseTimeout required.
 *
 * The activity type is automatically determined from the @Activity annotation
 * or the function name.
 *
 * @param activityFunc The function reference annotated with @Activity
 * @param startToCloseTimeout Maximum time for a single execution attempt - REQUIRED
 * @param scheduleToCloseTimeout Maximum time from scheduling to completion (including retries)
 * @param scheduleToStartTimeout Maximum time from scheduling to worker pickup
 * @param heartbeatTimeout Maximum time between heartbeats (required for cancellation detection)
 * @param taskQueue Task queue to run the activity on
 * @param retryPolicy Retry policy for the activity
 * @param activityId Custom activity ID (auto-generated if null)
 * @param cancellationType How to handle cancellation
 * @return A handle to the activity
 */
suspend fun WorkflowContext.startActivityWithStartToClose(
    activityFunc: KFunction<*>,
    startToCloseTimeout: Duration,
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
        args = Payloads.getDefaultInstance(),
        options = options,
    )
}

/**
 * Starts an activity using a function reference with a single argument, with startToCloseTimeout required.
 */
suspend inline fun <reified T> WorkflowContext.startActivityWithStartToClose(
    activityFunc: KFunction<*>,
    arg: T,
    startToCloseTimeout: Duration,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity using a function reference with two arguments, with startToCloseTimeout required.
 */
suspend inline fun <reified T1, reified T2> WorkflowContext.startActivityWithStartToClose(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    startToCloseTimeout: Duration,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}

/**
 * Starts an activity using a function reference with three arguments, with startToCloseTimeout required.
 */
suspend inline fun <reified T1, reified T2, reified T3> WorkflowContext.startActivityWithStartToClose(
    activityFunc: KFunction<*>,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    startToCloseTimeout: Duration,
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
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))

    return this.startActivityWithPayloads(
        activityType = activityFunc.getActivityType(),
        args = payloadsBuilder.build(),
        options = options,
    )
}
