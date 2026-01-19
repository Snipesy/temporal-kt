package com.surrealdev.temporal.application.worker

import com.surrealdev.temporal.activity.internal.ActivityDispatcher
import com.surrealdev.temporal.activity.internal.ActivityRegistry
import com.surrealdev.temporal.application.TaskQueueConfig
import com.surrealdev.temporal.core.TemporalWorker
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.workflow.internal.WorkflowDispatcher
import com.surrealdev.temporal.workflow.internal.WorkflowRegistry
import coresdk.activity_task.ActivityTaskOuterClass
import coresdk.workflow_activation.WorkflowActivationOuterClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext

private val logger = Logger.getLogger("ManagedWorker")

/**
 * A managed worker that integrates a core worker with the application lifecycle.
 *
 * This class runs polling coroutines under a parent job and handles graceful shutdown.
 */
internal class ManagedWorker(
    private val coreWorker: TemporalWorker,
    private val config: TaskQueueConfig,
    parentContext: CoroutineContext,
    serializer: PayloadSerializer,
    namespace: String,
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
     * Waits for the worker to be ready (polling started for both workflow and activity polling).
     * This ensures the worker is registered with the Temporal server before workflows are started.
     */
    suspend fun awaitReady() {
        workflowPollingStarted.await()
        activityPollingStarted.await()
        // Yield to allow worker coroutines to proceed to the FFI poll calls
        // The poll calls register the worker with the server even though they block
        kotlinx.coroutines.yield()
    }

    // Build registries from config
    private val activityRegistry =
        ActivityRegistry().apply {
            config.activities.forEach { register(it) }
        }
    private val workflowRegistry =
        WorkflowRegistry().apply {
            config.workflows.forEach { register(it) }
        }

    // Dispatchers with concurrency limits from config
    private val activityDispatcher =
        ActivityDispatcher(
            registry = activityRegistry,
            serializer = serializer,
            taskQueue = config.name,
            maxConcurrent = config.maxConcurrentActivities,
            // TODO: Implement heartbeat
            heartbeatFn = { _, _ -> },
        )
    private val workflowDispatcher =
        WorkflowDispatcher(
            registry = workflowRegistry,
            serializer = serializer,
            taskQueue = config.name,
            namespace = namespace,
            maxConcurrent = config.maxConcurrentWorkflows,
        )

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

        logger.info("[start] Starting worker for taskQueue=$taskQueue")

        // Always start workflow polling - this activates the SDK's processing thread
        launch { pollWorkflowActivations() }

        // Always start activity polling too
        launch { pollActivityTasks() }

        logger.info("[start] Worker started for taskQueue=$taskQueue")
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

        logger.info("[stop] Stopping worker for taskQueue=$taskQueue")

        // Wait for polling to actually start before initiating shutdown
        // If this is not done then an internal temporal core-sdk race condition can occur
        logger.fine("[stop] Waiting for workflow polling to start...")
        workflowPollingStarted.await()
        logger.fine("[stop] Waiting for activity polling to start...")
        activityPollingStarted.await()

        // Initiate shutdown - this causes poll methods to return null
        logger.info("[stop] Initiating shutdown for taskQueue=$taskQueue")
        coreWorker.initiateShutdown()

        // Wait for polling coroutines to complete with a timeout
        // Activity polling doesn't have a "bump stream" mechanism like workflows,
        // so it may not exit immediately when shutdown is initiated
        logger.fine("[stop] Waiting for polling coroutines to complete...")
        val pollsCompleted =
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                workerJob.children.forEach { it.join() }
                true
            }

        if (pollsCompleted != true) {
            logger.info("[stop] Polling did not stop within timeout, cancelling coroutines")
            workerJob.cancel()
        }

        // Clean shutdown now works since polling was active
        logger.fine("[stop] Awaiting core worker shutdown...")
        coreWorker.awaitShutdown()
        coreWorker.close()

        logger.info("[stop] Worker stopped for taskQueue=$taskQueue")
    }

    private suspend fun pollWorkflowActivations() {
        logger.info("[pollWorkflowActivations] Starting workflow polling for taskQueue=$taskQueue")
        var firstPoll = true
        while (isActive && !coreWorker.isShutdownInitiated()) {
            try {
                // Signal BEFORE making the poll call - the FFI call itself registers the worker
                // with the Temporal server. The poll will block until work arrives.
                if (firstPoll) {
                    logger.info("[pollWorkflowActivations] Making first poll call to register worker...")
                    workflowPollingStarted.complete(Unit)
                    firstPoll = false
                }
                val activationBytes = coreWorker.pollWorkflowActivation()
                if (activationBytes == null) {
                    // Shutdown signal received
                    logger.info("[pollWorkflowActivations] Received shutdown signal, exiting")
                    break
                }

                // Parse the activation
                val activation = WorkflowActivationOuterClass.WorkflowActivation.parseFrom(activationBytes)
                logger.fine("[pollWorkflowActivations] Received activation for workflow ${activation.runId}")

                // Dispatch to workflow executor
                val completion = workflowDispatcher.dispatch(activation, this@ManagedWorker)

                // Send completion back to core
                coreWorker.completeWorkflowActivation(completion.toByteArray())
                logger.fine("[pollWorkflowActivations] Completed activation for workflow ${activation.runId}")
            } catch (_: CancellationException) {
                logger.info("[pollWorkflowActivations] Cancelled, exiting")
                break
            } catch (e: Exception) {
                // Log and continue on errors for now
                // In production, we'd want proper error handling
                if (!coreWorker.isShutdownInitiated()) {
                    logger.warning("[pollWorkflowActivations] Error: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        logger.info("[pollWorkflowActivations] Workflow polling stopped for taskQueue=$taskQueue")
    }

    private suspend fun pollActivityTasks() {
        logger.info("[pollActivityTasks] Starting activity polling for taskQueue=$taskQueue")
        var firstPoll = true
        while (isActive && !coreWorker.isShutdownInitiated()) {
            try {
                // Signal BEFORE making the poll call - the FFI call itself registers the worker
                // with the Temporal server. The poll will block until work arrives.
                if (firstPoll) {
                    logger.info("[pollActivityTasks] Making first poll call to register worker...")
                    activityPollingStarted.complete(Unit)
                    firstPoll = false
                }
                val taskBytes = coreWorker.pollActivityTask()
                if (taskBytes == null) {
                    // Shutdown signal received
                    logger.info("[pollActivityTasks] Received shutdown signal, exiting")
                    break
                }

                // Parse the activity task
                val task = ActivityTaskOuterClass.ActivityTask.parseFrom(taskBytes)
                val activityInfo = if (task.hasStart()) "type=${task.start.activityType}" else "cancel"
                logger.fine("[pollActivityTasks] Received activity task: $activityInfo")

                // Dispatch to activity executor (in a separate coroutine for parallelism)
                launch {
                    try {
                        val completion = activityDispatcher.dispatch(task)
                        coreWorker.completeActivityTask(completion.toByteArray())
                        logger.fine("[pollActivityTasks] Completed activity: $activityInfo")
                    } catch (e: Exception) {
                        logger.warning("[pollActivityTasks] Error dispatching activity: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (_: CancellationException) {
                logger.info("[pollActivityTasks] Cancelled, exiting")
                break
            } catch (e: Exception) {
                // Log and continue on errors for now
                if (!coreWorker.isShutdownInitiated()) {
                    logger.warning("[pollActivityTasks] Error: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        logger.info("[pollActivityTasks] Activity polling stopped for taskQueue=$taskQueue")
    }
}
