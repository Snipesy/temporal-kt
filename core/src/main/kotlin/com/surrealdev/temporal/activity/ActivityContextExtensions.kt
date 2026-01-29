package com.surrealdev.temporal.activity

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Returns the current [ActivityContext] from the coroutine context.
 *
 * This function must be called from within an activity execution to access
 * the activity's context
 *
 * @throws IllegalStateException if called outside of an activity execution
 */
suspend fun activity(): ActivityContext =
    currentCoroutineContext()[ActivityContext]
        ?: error("activity() must be called from within an activity execution")

/**
 * Returns an SLF4J logger for this activity.
 *
 * The logger name is based on the activity type (e.g., "temporal.activity.greet").
 * MDC context (activityId, activityType, workflowId, runId, taskQueue, namespace)
 * is automatically populated by the framework and will be included in log output.
 *
 * Example:
 * ```kotlin
 * @Activity
 * suspend fun ActivityContext.greet(name: String): String {
 *     val log = logger()
 *     log.info("Greeting {}", name)
 *     return "Hello, $name"
 * }
 * ```
 */
fun ActivityContext.logger(): Logger = LoggerFactory.getLogger("temporal.activity.${info.activityType}")
