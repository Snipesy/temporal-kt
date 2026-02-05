package com.surrealdev.temporal.testing

import com.surrealdev.temporal.activity.ActivityInfo
import com.surrealdev.temporal.activity.ActivityWorkflowInfo
import kotlin.time.Duration
import kotlin.time.Instant

/*
 * Extension functions and helpers for [ActivityTestHarness].
 */

/**
 * Configures the activity info for the next test execution.
 *
 * Example:
 * ```kotlin
 * runActivityTest {
 *     configureActivityInfo {
 *         activityType = "MyCustomActivity"
 *         attempt = 3
 *         deadline = Instant.now() + 30.seconds
 *     }
 *
 *     val result = withActivityContext {
 *         myActivity.doWork()
 *     }
 * }
 * ```
 */
inline fun ActivityTestHarness.configureActivityInfo(block: ActivityInfoBuilder.() -> Unit) {
    val builder = ActivityInfoBuilder(activityInfo)
    builder.block()
    activityInfo = builder.build()
}

/**
 * Builder for creating custom [ActivityInfo] in tests.
 */
class ActivityInfoBuilder(
    baseInfo: ActivityInfo,
) {
    var activityId: String = baseInfo.activityId
    var activityType: String = baseInfo.activityType
    var taskQueue: String = baseInfo.taskQueue
    var attempt: Int = baseInfo.attempt
    var startTime: Instant = baseInfo.startTime
    var deadline: Instant? = baseInfo.deadline
    var heartbeatDetails: com.surrealdev.temporal.activity.HeartbeatDetails? = baseInfo.heartbeatDetails
    var workflowId: String = baseInfo.workflowInfo.workflowId
    var runId: String = baseInfo.workflowInfo.runId
    var workflowType: String = baseInfo.workflowInfo.workflowType
    var namespace: String = baseInfo.workflowInfo.namespace

    fun build(): ActivityInfo =
        ActivityInfo(
            activityId = activityId,
            activityType = activityType,
            taskQueue = taskQueue,
            attempt = attempt,
            startTime = startTime,
            deadline = deadline,
            heartbeatDetails = heartbeatDetails,
            workflowInfo =
                ActivityWorkflowInfo(
                    workflowId = workflowId,
                    runId = runId,
                    workflowType = workflowType,
                    namespace = namespace,
                ),
        )
}

/**
 * Sets a deadline relative to the start time.
 *
 * Example:
 * ```kotlin
 * configureActivityInfo {
 *     deadlineIn(30.seconds)
 * }
 * ```
 */
fun ActivityInfoBuilder.deadlineIn(duration: Duration) {
    deadline = startTime + duration
}

/**
 * Configures the activity to simulate a retry attempt with previous heartbeat details.
 *
 * Note: This is a simplified simulation. For full retry testing with serialization,
 * use integration tests with [runTemporalTest].
 *
 * Example:
 * ```kotlin
 * runActivityTest {
 *     configureActivityInfo {
 *         attempt = 2
 *         // heartbeatDetails would need to be manually created with serializer
 *     }
 *
 *     val result = withActivityContext {
 *         // Activity can check info.attempt and info.heartbeatDetails
 *         myActivity.resumableWork()
 *     }
 * }
 * ```
 */
fun ActivityInfoBuilder.asRetry(attemptNumber: Int) {
    attempt = attemptNumber
}
