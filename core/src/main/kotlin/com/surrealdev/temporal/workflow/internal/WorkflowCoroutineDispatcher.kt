package com.surrealdev.temporal.workflow.internal

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.CoroutineContext

/**
 * Interface for scheduling workflow timers.
 *
 * This allows the dispatcher to delegate [kotlinx.coroutines.delay] and
 * [kotlinx.coroutines.withTimeout] calls to the workflow's durable timer system.
 */
internal interface WorkflowTimerScheduler {
    /**
     * Schedules a timer that will resume the continuation after the specified delay.
     * Used by [kotlinx.coroutines.delay].
     *
     * @param delayMillis The delay in milliseconds
     * @param continuation The continuation to resume when the timer fires
     */
    fun scheduleTimer(
        delayMillis: Long,
        continuation: CancellableContinuation<Unit>,
    )

    /**
     * Schedules a timer that will execute a callback after the specified delay.
     * Used by [kotlinx.coroutines.withTimeout] for timeout handling.
     *
     * @param delayMillis The delay in milliseconds
     * @param block The callback to execute when the timer fires
     * @return A handle that can be used to cancel the timer
     */
    fun scheduleTimeoutCallback(
        delayMillis: Long,
        block: Runnable,
    ): DisposableHandle
}

/**
 * Custom dispatcher that ensures deterministic, single-threaded workflow execution.
 *
 * Instead of dispatching to a thread pool, work is queued and processed
 * synchronously on the activation thread via [processAllWork].
 *
 * This mimics Python SDK's custom event loop and .NET SDK's task scheduler.
 *
 * This dispatcher implements [Delay] to intercept calls to
 * [kotlinx.coroutines.delay]. When a [timerScheduler] is provided, delay calls
 * are delegated to the workflow's durable timer system. Otherwise, an error
 * is thrown guiding users to use [com.surrealdev.temporal.workflow.WorkflowContext.sleep].
 *
 * @param timerScheduler Optional scheduler for delegating delay() calls to durable timers.
 *                       When null, delay() calls will throw an error.
 */
@OptIn(InternalCoroutinesApi::class)
internal class WorkflowCoroutineDispatcher(
    private val timerScheduler: WorkflowTimerScheduler? = null,
) : CoroutineDispatcher(),
    Delay {
    private val taskQueue = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        // Queue work
        taskQueue.addLast(block)
    }

    /**
     * Intercepts [kotlinx.coroutines.delay] calls.
     *
     * When a [timerScheduler] is configured, this delegates to the workflow's
     * durable timer system, making delay() work correctly in workflows.
     *
     * When no scheduler is configured (e.g., dispatcher used outside workflow context),
     * this throws an error guiding users to use WorkflowContext.sleep().
     */
    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        if (timerScheduler != null) {
            // Delegate to the workflow's durable timer system
            timerScheduler.scheduleTimer(timeMillis, continuation)
        } else {
            throw IllegalStateException(
                "Cannot use kotlinx.coroutines.delay() inside a workflow. " +
                    "Use WorkflowContext.sleep() instead for durable timers that survive replay.",
            )
        }
    }

    /**
     * Intercepts timeout scheduling from [kotlinx.coroutines.withTimeout].
     *
     * When a [timerScheduler] is configured, this delegates to the workflow's
     * durable timer system, making withTimeout() work correctly in workflows.
     */
    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        if (timerScheduler != null) {
            return timerScheduler.scheduleTimeoutCallback(timeMillis, block)
        } else {
            throw IllegalStateException(
                "Cannot use kotlinx.coroutines.withTimeout() inside a workflow. " +
                    "Use WorkflowContext.awaitCondition() with timeout for durable timeouts that survive replay.",
            )
        }
    }

    /**
     * Processes all queued work synchronously on the current thread.
     * Called after each workflow suspension point to let workflow progress.
     *
     * Exceptions from tasks are allowed to propagate - they represent workflow
     * failures that should be caught at the activation level and converted
     * to workflow failure completions.
     */
    fun processAllWork() {
        while (taskQueue.isNotEmpty()) {
            val task = taskQueue.removeFirst()
            task.run()
        }
    }

    /**
     * Returns true if there's pending work.
     */
    fun hasPendingWork(): Boolean = taskQueue.isNotEmpty()

    /**
     * Clears all pending work (used during cleanup).
     */
    fun clear() {
        taskQueue.clear()
    }
}
