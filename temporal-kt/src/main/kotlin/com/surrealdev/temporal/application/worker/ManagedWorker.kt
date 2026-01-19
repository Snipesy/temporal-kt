package com.surrealdev.temporal.application.worker

import com.surrealdev.temporal.application.TaskQueueConfig
import com.surrealdev.temporal.core.TemporalWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * A managed worker that integrates a core worker with the application lifecycle.
 *
 * This class runs polling coroutines under a parent job and handles graceful shutdown.
 */
internal class ManagedWorker(
    private val coreWorker: TemporalWorker,
    private val config: TaskQueueConfig,
    parentContext: CoroutineContext,
) : CoroutineScope {
    private val workerJob = SupervisorJob(parentContext[Job])
    override val coroutineContext: CoroutineContext = parentContext + workerJob

    @Volatile
    private var started = false

    @Volatile
    private var stopped = false

    // Signals that polling has actually reached the Core SDK
    // This must happen before shutdown will work properly
    private val workflowPollingStarted = CompletableDeferred<Unit>()
    private val activityPollingStarted = CompletableDeferred<Unit>()

    val taskQueue: String get() = config.name

    /**
     * Starts the worker polling loops.
     *
     * Polling is always started regardless of whether workflows/activities are registered.
     * This is necessary because the SDK's processing thread waits for the first poll to
     * activate, and awaitShutdown() will hang if no polling ever occurred.
     *
     * @return The job representing the worker's lifecycle
     */
    fun start(): Job {
        check(!started) { "Worker already started" }
        started = true

        // Always start workflow polling - this activates the SDK's processing thread
        launch { pollWorkflowActivations() }

        // Always start activity polling too
        launch { pollActivityTasks() }

        return workerJob
    }

    /**
     * Stops the worker gracefully.
     *
     * This waits for polling to start (activating the SDK's processing thread),
     * then initiates shutdown, waits for polling loops to exit naturally,
     * and performs a clean shutdown of the core worker.
     */
    suspend fun stop() {
        if (stopped) return
        stopped = true

        // Wait for polling to actually start before initiating shutdown
        // If this is not done then an internal temporal core-sdk race condition can occur
        workflowPollingStarted.await()
        activityPollingStarted.await()

        // Initiate shutdown - this causes poll methods to return null
        coreWorker.initiateShutdown()

        // Wait for all polling coroutines to complete naturally
        // Don't cancel - let them exit when poll returns null
        workerJob.children.forEach { it.join() }

        // Clean shutdown now works since polling was active
        coreWorker.awaitShutdown()
        coreWorker.close()
    }

    private suspend fun pollWorkflowActivations() {
        var firstPoll = true
        while (isActive && !coreWorker.isShutdownInitiated()) {
            try {
                // Signal that we're about to make the first poll
                // This activates the SDK's processing thread
                if (firstPoll) {
                    workflowPollingStarted.complete(Unit)
                    firstPoll = false
                }
                val activationBytes = coreWorker.pollWorkflowActivation()
                if (activationBytes == null) {
                    // Shutdown signal received
                    break
                }

                // For now, we just acknowledge the activation without doing anything
                // The actual workflow dispatch will be implemented later
                // TODO: Implement proper workflow dispatch and completion
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                // Log and continue on errors for now
                // In production, we'd want proper error handling
                if (!coreWorker.isShutdownInitiated()) {
                    System.err.println("Error polling workflow activation: ${e.message}")
                }
            }
        }
    }

    private suspend fun pollActivityTasks() {
        var firstPoll = true
        while (isActive && !coreWorker.isShutdownInitiated()) {
            try {
                // Signal that we're about to make the first poll
                if (firstPoll) {
                    activityPollingStarted.complete(Unit)
                    firstPoll = false
                }
                val taskBytes = coreWorker.pollActivityTask()
                if (taskBytes == null) {
                    // Shutdown signal received
                    break
                }

                // For now, we just acknowledge the task without doing anything
                // The actual activity dispatch will be implemented later
                // TODO: Implement proper activity dispatch and completion
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                // Log and continue on errors for now
                if (!coreWorker.isShutdownInitiated()) {
                    System.err.println("Error polling activity task: ${e.message}")
                }
            }
        }
    }
}
