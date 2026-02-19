package com.surrealdev.temporal.application.plugin

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskCompleted
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskFailed
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskFailedContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskStarted
import com.surrealdev.temporal.application.plugin.interceptor.ExecuteActivity
import com.surrealdev.temporal.application.plugin.interceptor.ExecuteActivityInput
import com.surrealdev.temporal.application.plugin.interceptor.Heartbeat
import com.surrealdev.temporal.application.plugin.interceptor.HeartbeatInput
import com.surrealdev.temporal.application.plugin.interceptor.Interceptor

/**
 * DSL builder for activity interceptors and observer hooks.
 *
 * Accessed via the `activity {}` block in plugin configuration:
 * ```kotlin
 * val MyPlugin = createApplicationPlugin("MyPlugin") {
 *     activity {
 *         // Inbound interceptor (server -> activity code)
 *         onExecute { input, proceed -> proceed(input) }
 *
 *         // Outbound interceptor (activity code -> SDK)
 *         onHeartbeat { input, proceed -> proceed(input) }
 *
 *         // Observer hooks (existing task-level hooks)
 *         onTaskStarted { ctx -> ... }
 *         onTaskCompleted { ctx -> ... }
 *         onTaskFailed { ctx -> ... }
 *     }
 * }
 * ```
 */
@TemporalDsl
class ActivityHookBuilder internal constructor(
    private val pluginBuilder: PluginBuilder<*>,
) {
    // ==================== Inbound Interceptors ====================

    /**
     * Intercepts activity execution (activity method invocation).
     */
    fun onExecute(interceptor: Interceptor<ExecuteActivityInput, Any?>) {
        pluginBuilder.on(ExecuteActivity, interceptor)
    }

    // ==================== Outbound Interceptors ====================

    /**
     * Intercepts activity heartbeat sending.
     */
    fun onHeartbeat(interceptor: Interceptor<HeartbeatInput, Unit>) {
        pluginBuilder.on(Heartbeat, interceptor)
    }

    // ==================== Observer Hooks ====================

    /**
     * Registers a handler for when an activity task starts.
     *
     * Called before dispatching an activity task.
     */
    fun onTaskStarted(handler: suspend (ActivityTaskContext) -> Unit) {
        pluginBuilder.on(ActivityTaskStarted, handler)
    }

    /**
     * Registers a handler for when an activity task completes successfully.
     *
     * Called after an activity task completes.
     */
    fun onTaskCompleted(handler: suspend (ActivityTaskCompletedContext) -> Unit) {
        pluginBuilder.on(ActivityTaskCompleted, handler)
    }

    /**
     * Registers a handler for when an activity task fails.
     *
     * Called when an activity task dispatch fails.
     */
    fun onTaskFailed(handler: suspend (ActivityTaskFailedContext) -> Unit) {
        pluginBuilder.on(ActivityTaskFailed, handler)
    }
}
