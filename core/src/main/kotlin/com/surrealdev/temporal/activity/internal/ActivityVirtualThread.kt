package com.surrealdev.temporal.activity.internal

import coresdk.activity_task.ActivityTaskOuterClass.ActivityTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.slf4j.MDC
import java.util.concurrent.ThreadFactory

/**
 * Runs a single activity on a dedicated virtual thread.
 *
 * Unlike workflows, activities don't persist across invocations - each activity
 * gets a fresh virtual thread that terminates when the activity completes.
 */
internal class ActivityVirtualThread(
    private val activityDispatcher: ActivityDispatcher,
    private val task: ActivityTask,
    threadFactory: ThreadFactory,
    private val mdcContextMap: Map<String, String>? = null,
) {
    private val completion = CompletableDeferred<ActivityDispatchResult>()

    @Volatile
    private var currentJob: Job? = null

    private val thread: Thread = threadFactory.newThread { runActivity() }

    /**
     * Returns the thread that will execute this activity.
     * Available immediately after construction, before [start] is called.
     */
    fun getThread(): Thread = thread

    private fun runActivity() {
        // Restore MDC context on virtual thread
        mdcContextMap?.let { MDC.setContextMap(it) }

        try {
            val result =
                runCatching {
                    runBlocking {
                        currentJob = coroutineContext[Job]
                        try {
                            activityDispatcher.dispatchStart(task, virtualThread = this@ActivityVirtualThread)
                        } finally {
                            currentJob = null
                        }
                    }
                }
            result.onSuccess { completion.complete(it) }
            result.onFailure { completion.completeExceptionally(it) }
        } finally {
            MDC.clear()
        }
    }

    fun start(): Deferred<ActivityDispatchResult> {
        thread.start()
        return completion
    }

    /**
     * Returns true if the activity thread is still alive.
     */
    fun isAlive(): Boolean = thread.isAlive

    /**
     * Interrupts the thread without full termination logic.
     * Use this for retry attempts after terminate() has already been called.
     */
    fun interruptThread() {
        currentJob?.cancel()
        thread.interrupt()
    }

    /**
     * Signals the activity thread to terminate.
     *
     * This method is NON-BLOCKING. Use [awaitTermination] to wait for the thread to actually stop.
     *
     * @param immediate If true, immediately interrupts the thread in addition to cancelling the job.
     *                  Use this when the thread is known to be stuck.
     */
    fun terminate(immediate: Boolean = false) {
        // Cancel the coroutine job
        currentJob?.cancel()

        if (immediate) {
            thread.interrupt()
        }
    }

    /**
     * Waits for the thread to terminate, with timeout.
     * This is a BLOCKING call - use from a coroutine with appropriate dispatcher (e.g., Dispatchers.IO).
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if thread terminated, false if timeout reached or interrupted
     */
    fun awaitTermination(timeoutMs: Long): Boolean {
        try {
            thread.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    /**
     * Gets the current stack trace of the virtual thread.
     * Useful for debugging zombie threads that don't respond to interrupt.
     */
    fun getStackTrace(): String = thread.stackTrace.joinToString("\n") { "    at $it" }
}
