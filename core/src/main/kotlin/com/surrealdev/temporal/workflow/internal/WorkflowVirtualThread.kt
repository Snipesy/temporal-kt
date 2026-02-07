package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.common.exceptions.WorkflowDeadlockException
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory

/**
 * Manages a dedicated virtual thread for a single workflow run.
 */
internal class WorkflowVirtualThread(
    private val executor: WorkflowExecutor,
    threadFactory: ThreadFactory,
    private val deadlockTimeoutMs: Long = 2000L,
) {
    private val logger = LoggerFactory.getLogger(WorkflowVirtualThread::class.java)

    /**
     * Activation item with a CompletableDeferred for suspend-friendly completion.
     * Using CompletableDeferred avoids blocking the poller's carrier thread.
     */
    private data class ActivationItem(
        val activation: WorkflowActivation,
        val completion: CompletableDeferred<WorkflowActivationCompletion>,
    )

    private sealed class QueueItem {
        data class Activation(
            val item: ActivationItem,
        ) : QueueItem()

        data object Terminate : QueueItem()
    }

    private val activationQueue = LinkedBlockingQueue<QueueItem>()

    // Track the current coroutine job for graceful cancellation
    @Volatile
    private var currentJob: Job? = null

    private val thread: Thread =
        threadFactory
            .newThread {
                runLoop()
            }.apply { start() }

    private fun runLoop() {
        try {
            while (true) {
                // Parks here between activations - cheap for virtual threads
                val item =
                    try {
                        activationQueue.take()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }

                when (item) {
                    is QueueItem.Terminate -> {
                        break
                    }

                    is QueueItem.Activation -> {
                        val activationItem = item.item
                        try {
                            // Run coroutine on THIS thread.
                            // Note: We don't use executor.workflowDispatcher as the runBlocking context
                            // because WorkflowCoroutineDispatcher queues tasks without processing them.
                            // The activate() method handles task processing via processAllWork().
                            val completion =
                                runBlocking {
                                    currentJob = coroutineContext[Job]
                                    try {
                                        executor.activate(activationItem.activation)
                                    } finally {
                                        currentJob = null
                                    }
                                }
                            // Complete the deferred - resumes the waiting poller coroutine
                            activationItem.completion.complete(completion)
                        } catch (e: Exception) {
                            activationItem.completion.completeExceptionally(e)
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.debug("Workflow virtual thread interrupted, exiting")
        } catch (e: Exception) {
            logger.error("Unexpected error in workflow virtual thread", e)
        }
    }

    /**
     * Dispatches an activation to this workflow's virtual thread.
     * Called from the poller coroutine; suspends until completion.
     *
     * @throws com.surrealdev.temporal.common.exceptions.WorkflowDeadlockException if the workflow doesn't yield within deadlockTimeoutMs
     */
    suspend fun dispatch(activation: WorkflowActivation): WorkflowActivationCompletion {
        val deferred = CompletableDeferred<WorkflowActivationCompletion>()
        activationQueue.put(QueueItem.Activation(ActivationItem(activation, deferred)))

        return if (deadlockTimeoutMs > 0) {
            val result =
                withTimeoutOrNull(deadlockTimeoutMs) {
                    deferred.await()
                }
            if (result != null) {
                result
            } else {
                // Deadlock detected - capture stack trace from the stuck thread
                val detectionTimestamp = System.currentTimeMillis()
                throw WorkflowDeadlockException.fromThread(
                    thread = thread,
                    timeoutMs = deadlockTimeoutMs,
                    detectionTimestamp = detectionTimestamp,
                )
            }
        } else {
            deferred.await()
        }
    }

    /**
     * Terminates this workflow's virtual thread.
     *
     * This method is NON-BLOCKING. Use [awaitTermination] to wait for the thread to actually stop.
     *
     * @param immediate If true, immediately interrupts the thread in addition to signaling termination.
     *                  Use this when the thread is known to be stuck (e.g., deadlock detected).
     */
    fun terminate(immediate: Boolean = false) {
        // Signal termination via queue
        activationQueue.put(QueueItem.Terminate)

        // Cancel the current coroutine job
        currentJob?.cancel()

        if (immediate) {
            // Immediate termination - also interrupt in case thread is stuck
            thread.interrupt()
        }
    }

    /**
     * Returns true if the thread is still alive.
     */
    fun isAlive(): Boolean = thread.isAlive

    /**
     * Interrupts the thread without adding a Terminate to the queue.
     * Use this for retry attempts after terminate() has already been called.
     */
    fun interruptThread() {
        currentJob?.cancel()
        thread.interrupt()
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
