package com.surrealdev.temporal.application.worker

import com.google.protobuf.ByteString
import com.surrealdev.temporal.activity.internal.ActivityDispatcher
import com.surrealdev.temporal.activity.internal.ActivityRegistry
import com.surrealdev.temporal.activity.internal.ActivityVirtualThread
import com.surrealdev.temporal.application.TaskQueueConfig
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.HookRegistry
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskCompleted
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskFailed
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskFailedContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskStarted
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompleted
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskCompletedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailed
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskFailedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkflowTaskStarted
import com.surrealdev.temporal.core.TemporalWorker
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.SimpleAttributeScope
import com.surrealdev.temporal.workflow.internal.WorkflowDispatcher
import com.surrealdev.temporal.workflow.internal.WorkflowRegistry
import coresdk.activityHeartbeat
import coresdk.activity_task.ActivityTaskOuterClass
import coresdk.workflow_activation.WorkflowActivationOuterClass
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

/**
 * A managed worker that integrates a core worker with the application lifecycle.
 *
 * This class runs polling coroutines under a parent job and handles graceful shutdown.
 */
internal class ManagedWorker(
    private val coreWorker: TemporalWorker,
    private val config: TaskQueueConfig,
    parentContext: CoroutineContext,
    private val serializer: PayloadSerializer,
    private val codec: PayloadCodec,
    private val namespace: String,
    private val applicationHooks: HookRegistry,
    private val application: TemporalApplication,
) : CoroutineScope {
    private val workerJob = SupervisorJob(parentContext[Job])

    /** MDC context for logging with worker identifiers. */
    private val mdcContext =
        MDCContext(
            mapOf(
                "taskQueue" to config.name,
                "namespace" to namespace,
            ),
        )

    /** Virtual thread factory for workflow execution. Each workflow run gets a dedicated virtual thread. */
    private val workflowThreadFactory: java.util.concurrent.ThreadFactory =
        Thread
            .ofVirtual()
            .name("workflow-${config.name}-", 0)
            .factory()

    /** Virtual thread factory for activity execution. Each activity gets its own virtual thread. */
    private val activityThreadFactory: java.util.concurrent.ThreadFactory =
        Thread
            .ofVirtual()
            .name("activity-${config.name}-", 0)
            .factory()

    /**
     * Worker's coroutine context.
     * Workflows and activities run on dedicated virtual threads, so no custom dispatcher is needed.
     */
    override val coroutineContext: CoroutineContext =
        parentContext + workerJob + CoroutineName("TaskQueue-${config.name}") + mdcContext

    private val logger = LoggerFactory.getLogger(ManagedWorker::class.java)

    @Volatile
    private var started = false

    @Volatile
    private var stopped = false

    // Reference to workflow dispatcher for zombie count access
    @Volatile
    private var workflowDispatcher: WorkflowDispatcher? = null

    // Explicit references to polling jobs
    private var workflowPollingJob: Job? = null
    private var activityPollingJob: Job? = null

    // Shutdown signaling
    private val shutdownSignal: CompletableJob = Job()

    // Signals that polling has actually reached the Core SDK
    // This must happen before shutdown will work properly
    private val workflowPollingStarted = CompletableDeferred<Unit>()
    private val activityPollingStarted = CompletableDeferred<Unit>()

    /**
     * Tracks running activity virtual threads for zombie detection during shutdown.
     * Key is thread identity, value is activity metadata for logging.
     */
    private data class ActivityThreadInfo(
        val thread: ActivityVirtualThread,
        val activityType: String,
        val activityId: String,
    )

    private val runningActivityThreads = ConcurrentHashMap<Long, ActivityThreadInfo>()

    /**
     * Returns true if shutdown has been signaled.
     * Polling loops should check this.
     */
    val isShuttingDown: Boolean
        get() = shutdownSignal.isCompleted

    val taskQueue: String get() = config.name

    /**
     * Gets the current count of zombie workflow threads.
     * Zombies are threads that don't respond to interrupt.
     */
    fun getWorkflowZombieCount(): Int = workflowDispatcher?.getZombieCount() ?: 0

    /**
     * Gets the current count of zombie activity threads.
     * Zombies are threads that don't respond to interrupt.
     */
    fun getActivityZombieCount(): Int = activityDispatcher.getZombieCount()

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

    // Create the task queue scope for hierarchical attribute lookup
    // This scope's parent is the application, enabling taskQueue -> application fallback
    private val taskQueueScope =
        SimpleAttributeScope(
            attributes = config.attributes,
            parentScope = application,
        )

    // Dispatchers with concurrency limits from config
    private val activityDispatcher =
        ActivityDispatcher(
            registry = activityRegistry,
            serializer = serializer,
            codec = codec,
            taskQueue = config.name,
            maxConcurrent = config.maxConcurrentActivities,
            heartbeatFn = { taskToken, details ->
                recordActivityHeartbeat(taskToken, details)
            },
            taskQueueScope = taskQueueScope,
            zombieConfig = config.zombieEviction,
            onFatalError = {
                val closed =
                    withTimeoutOrNull(config.forceExitTimeout) {
                        application.close()
                        true
                    }
                if (closed == null) {
                    logger.error(
                        "[TKT1206] FATAL: Graceful shutdown timed out after {}. " +
                            "Stuck threads prevent clean shutdown. Forcing System.exit(1).",
                        config.forceExitTimeout,
                    )
                    exitProcess(1)
                }
            },
        )

    /**
     * Records an activity heartbeat to the Core SDK.
     *
     * The Core SDK handles heartbeat batching internally and sends heartbeats
     * to the server asynchronously. If cancellation is requested, the Core SDK
     * will send a Cancel task through the normal [pollActivityTasks] mechanism.
     */
    private fun recordActivityHeartbeat(
        taskToken: ByteString,
        details: Payload?,
    ) {
        val heartbeat =
            activityHeartbeat {
                this.taskToken = taskToken
                if (details != null) {
                    this.details += details
                }
            }
        coreWorker.recordActivityHeartbeat(heartbeat)
    }

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

        // Keep explicit references to polling jobs
        workflowPollingJob =
            launch(CoroutineName("WorkflowPoller-$taskQueue")) {
                pollWorkflowActivations()
            }

        activityPollingJob =
            launch(CoroutineName("ActivityPoller-$taskQueue")) {
                pollActivityTasks()
            }

        // This ensures that if a polling job fails unexpectedly, shutdown is triggered
        workflowPollingJob?.invokeOnCompletion { cause ->
            cause?.let {
                if (it !is CancellationException) {
                    logger.error("[start] Workflow polling job failed unexpectedly", it)
                    shutdownSignal.completeExceptionally(it)
                }
            }
        }

        activityPollingJob?.invokeOnCompletion { cause ->
            cause?.let {
                if (it !is CancellationException) {
                    logger.error("[start] Activity polling job failed unexpectedly", it)
                    shutdownSignal.completeExceptionally(it)
                }
            }
        }

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

        logger.info("[stop] Initiating shutdown for taskQueue=$taskQueue")

        // Phase 1: Signal shutdown intent
        shutdownSignal.complete()
        coreWorker.initiateShutdown()

        // Phase 2: Wait for polling jobs to complete gracefully
        val gracefulShutdown =
            withTimeoutOrNull(config.shutdownGracePeriodMs) {
                // Join polling jobs explicitly (they will cascade to children)
                workflowPollingJob?.join()
                activityPollingJob?.join()
                true
            }

        if (gracefulShutdown != true) {
            logger.warn(
                "[stop] Graceful shutdown timed out ({}ms), forcing cancellation",
                config.shutdownGracePeriodMs,
            )

            // Phase 3: Force cancel
            workflowPollingJob?.cancel()
            activityPollingJob?.cancel()

            // Phase 4: Wait for forced cancellation to complete
            workflowPollingJob?.join()
            activityPollingJob?.join()
        }

        // Phase 5: Cleanup core worker
        // Note: Workflow executors are cleaned up in pollWorkflowActivations() finally block
        logger.debug("[stop] Awaiting core worker shutdown...")
        coreWorker.awaitShutdown()
        coreWorker.close()

        // Phase 6: Complete this worker job
        workerJob.complete()

        logger.info("[stop] Worker stopped for taskQueue=$taskQueue")
    }

    private suspend fun pollWorkflowActivations() {
        logger.info("[pollWorkflowActivations] Starting workflow polling for taskQueue=$taskQueue")

        val rootExecutorJob = SupervisorJob(coroutineContext[Job])

        // Create a local dispatcher with the rootExecutorJob as parent
        // This ensures all executors created during this polling session are children
        val localWorkflowDispatcher =
            WorkflowDispatcher(
                registry = workflowRegistry,
                serializer = serializer,
                codec = codec,
                taskQueue = config.name,
                namespace = namespace,
                maxConcurrent = config.maxConcurrentWorkflows,
                taskQueueScope = taskQueueScope,
                parentJob = rootExecutorJob,
                workflowThreadFactory = workflowThreadFactory,
                deadlockTimeoutMs = config.workflowDeadlockTimeoutMs,
                zombieConfig = config.zombieEviction,
                onFatalError = {
                    val closed =
                        withTimeoutOrNull(config.forceExitTimeout) {
                            application.close()
                            true
                        }
                    if (closed == null) {
                        logger.error(
                            "[TKT1106] FATAL: Graceful shutdown timed out after {}. " +
                                "Stuck threads prevent clean shutdown. Forcing System.exit(1).",
                            config.forceExitTimeout,
                        )
                        exitProcess(1)
                    }
                },
            )
        workflowDispatcher = localWorkflowDispatcher

        // Root job for all workflow activations - isolated from each other (SupervisorJob)
        val rootWorkflowActivationJob = SupervisorJob(coroutineContext[Job])

        // Exception handler as safety net for uncaught exceptions in workflow activation coroutines
        val workflowExceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                logger.error("[pollWorkflowActivations] Uncaught exception in workflow coroutine", throwable)
            }

        // Workflow activation scope - activations run concurrently (like activities)
        val workflowScope =
            CoroutineScope(
                coroutineContext +
                    rootWorkflowActivationJob +
                    workflowExceptionHandler +
                    CoroutineName("workflows-$taskQueue"),
            )

        var firstPoll = true
        try {
            while (isActive && !shutdownSignal.isCompleted) {
                try {
                    // Signal BEFORE making the poll call - the FFI call itself registers the worker
                    // with the Temporal server. The poll will block until work arrives.
                    if (firstPoll) {
                        logger.info("[pollWorkflowActivations] Making first poll call to register worker...")
                        workflowPollingStarted.complete(Unit)
                        firstPoll = false
                    }
                    // Use zero-copy parsing - parse directly from native memory without ByteArray copy
                    val activation =
                        coreWorker.pollWorkflowActivation { input ->
                            WorkflowActivationOuterClass.WorkflowActivation.parseFrom(input)
                        }
                    if (activation == null) {
                        // Shutdown signal received
                        logger.info("[pollWorkflowActivations] Received shutdown signal, exiting")
                        break
                    }
                    logger.debug("[pollWorkflowActivations] Received activation for workflow ${activation.runId}")

                    // Extract workflow type from initialize job if present
                    val workflowType =
                        activation.jobsList
                            .find { it.hasInitializeWorkflow() }
                            ?.initializeWorkflow
                            ?.workflowType

                    // Create MDC context for this activation
                    val workflowMdcContext =
                        MDCContext(
                            mapOf(
                                "taskQueue" to config.name,
                                "namespace" to namespace,
                                "runId" to activation.runId,
                                "workflowType" to (workflowType ?: "unknown"),
                            ),
                        )

                    // Launch non-blocking - polling loop continues immediately to get more work
                    // Concurrency is controlled by WorkflowDispatcher.semaphore (maxConcurrentWorkflows)
                    // Per-run serialization is handled by WorkflowDispatcher's per-run Mutex
                    workflowScope.launch(workflowMdcContext + CoroutineName("Workflow-${activation.runId}")) {
                        val startTime = System.currentTimeMillis()

                        // Fire WorkflowTaskStarted hooks
                        val workflowContext =
                            WorkflowTaskContext(
                                activation = activation,
                                runId = activation.runId,
                                workflowType = workflowType,
                                taskQueue = config.name,
                                namespace = namespace,
                            )
                        applicationHooks.call(WorkflowTaskStarted, workflowContext)
                        config.hookRegistry.call(WorkflowTaskStarted, workflowContext)

                        try {
                            // Dispatch to workflow executor
                            val completion = localWorkflowDispatcher.dispatch(activation)

                            // Send completion back to core
                            coreWorker.completeWorkflowActivation(completion)
                            logger.debug(
                                "[pollWorkflowActivations] Completed activation for workflow ${activation.runId}",
                            )

                            // Fire WorkflowTaskCompleted hooks
                            val duration = (System.currentTimeMillis() - startTime).milliseconds
                            val completedContext =
                                WorkflowTaskCompletedContext(
                                    activation = activation,
                                    completion = completion,
                                    runId = activation.runId,
                                    duration = duration,
                                )
                            applicationHooks.call(WorkflowTaskCompleted, completedContext)
                            config.hookRegistry.call(WorkflowTaskCompleted, completedContext)
                        } catch (e: CancellationException) {
                            // Propagate cancellation
                            throw e
                        } catch (dispatchError: Exception) {
                            // Fire WorkflowTaskFailed hooks
                            val failedContext =
                                WorkflowTaskFailedContext(
                                    activation = activation,
                                    error = dispatchError,
                                    runId = activation.runId,
                                )
                            applicationHooks.call(WorkflowTaskFailed, failedContext)
                            config.hookRegistry.call(WorkflowTaskFailed, failedContext)

                            logger.warn(
                                "[pollWorkflowActivations] Error dispatching workflow",
                                dispatchError,
                            )
                        }
                    }
                } catch (_: CancellationException) {
                    logger.info("[pollWorkflowActivations] Cancelled, exiting")
                    break
                } catch (e: Exception) {
                    // Log and continue on errors for now
                    // In production, we'd want proper error handling
                    if (!shutdownSignal.isCompleted) {
                        logger.warn("[pollWorkflowActivations] Error in polling loop", e)
                    }
                }
            }
        } finally {
            // NonCancellable ensures cleanup completes even if parent scope is force-cancelled
            withContext(NonCancellable) {
                logger.debug("[pollWorkflowActivations] Waiting for in-flight activations to complete...")
                val shutdownStart = System.currentTimeMillis()

                // Cancel the workflow activation scope (signals no new work will be accepted)
                rootWorkflowActivationJob.cancel()

                // Wait for in-flight activations to complete with timeout
                val gracefullyCompleted =
                    withTimeoutOrNull(config.shutdownGracePeriodMs) {
                        rootWorkflowActivationJob.join()
                        true
                    } ?: false

                if (!gracefullyCompleted) {
                    logger.warn(
                        "[TKT1108] Workflow activation shutdown timeout after {}ms, some activations still in progress",
                        config.shutdownGracePeriodMs,
                    )
                }

                // Terminate all cached executors (clears dispatchers, cancels jobs)
                // This includes zombie eviction for any stuck threads
                localWorkflowDispatcher.clear()

                rootExecutorJob.complete()
                rootExecutorJob.join()

                val shutdownDuration = System.currentTimeMillis() - shutdownStart
                if (shutdownDuration > 1000) {
                    logger.info("[pollWorkflowActivations] Waited ${shutdownDuration}ms for executors to complete")
                }
                logger.info("[pollWorkflowActivations] Workflow polling stopped for taskQueue=$taskQueue")
            }
        }
    }

    private suspend fun pollActivityTasks() {
        logger.info("[pollActivityTasks] Starting activity polling for taskQueue=$taskQueue")

        // Root job for all activities - isolated from each other (SupervisorJob)
        val rootActivityJob = SupervisorJob(coroutineContext[Job])

        // Exception handler as safety net for uncaught exceptions in activity coroutines
        val exceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                logger.error("[pollActivityTasks] Uncaught exception in activity coroutine", throwable)
            }

        // Activity scope includes the configured dispatcher if set
        val activityScope =
            CoroutineScope(
                coroutineContext +
                    rootActivityJob +
                    exceptionHandler +
                    CoroutineName("activities-$taskQueue"),
            )

        var firstPoll = true
        try {
            while (isActive && !shutdownSignal.isCompleted) {
                try {
                    // with the Temporal server. The poll will block until work arrives.
                    if (firstPoll) {
                        logger.info("[pollActivityTasks] Making first poll call to register worker...")
                        activityPollingStarted.complete(Unit)
                        firstPoll = false
                    }
                    // Use zero-copy parsing - parse directly from native memory without ByteArray copy
                    val task =
                        coreWorker.pollActivityTask { input ->
                            ActivityTaskOuterClass.ActivityTask.parseFrom(input)
                        }
                    if (task == null) {
                        // Shutdown signal received
                        logger.info("[pollActivityTasks] Received shutdown signal, exiting")
                        break
                    }
                    val activityInfo = if (task.hasStart()) "type=${task.start.activityType}" else "cancel"
                    logger.debug("[pollActivityTasks] Received activity task: $activityInfo")

                    // Dispatch to activity executor in its own coroutine context
                    // Each activity gets its own context with MDC for logging
                    val activityMdcContext =
                        if (task.hasStart()) {
                            MDCContext(
                                mapOf(
                                    "taskQueue" to config.name,
                                    "namespace" to namespace,
                                    "activityType" to task.start.activityType,
                                    "activityId" to task.start.activityId,
                                    "workflowId" to task.start.workflowExecution.workflowId,
                                    "runId" to task.start.workflowExecution.runId,
                                ),
                            )
                        } else {
                            mdcContext // Cancel tasks use base MDC
                        }

                    activityScope.launch(activityMdcContext + CoroutineName("Activity-$activityInfo")) {
                        // Only fire hooks for Start tasks (not Cancel tasks)
                        if (task.hasStart()) {
                            val start = task.start
                            val activityContext =
                                ActivityTaskContext(
                                    task = task,
                                    activityType = start.activityType,
                                    activityId = start.activityId,
                                    workflowId = start.workflowExecution.workflowId,
                                    runId = start.workflowExecution.runId,
                                    taskQueue = config.name,
                                    namespace = namespace,
                                )

                            // Fire ActivityTaskStarted hooks
                            applicationHooks.call(ActivityTaskStarted, activityContext)
                            config.hookRegistry.call(ActivityTaskStarted, activityContext)

                            val startTime = System.currentTimeMillis()
                            // Run activity on dedicated virtual thread for proper thread interruption
                            val activityThread =
                                ActivityVirtualThread(
                                    activityDispatcher = activityDispatcher,
                                    task = task,
                                    threadFactory = activityThreadFactory,
                                    mdcContextMap = org.slf4j.MDC.getCopyOfContextMap(),
                                )

                            // Track for zombie detection during shutdown
                            val threadId = activityThread.getThread().threadId()
                            runningActivityThreads[threadId] =
                                ActivityThreadInfo(
                                    thread = activityThread,
                                    activityType = start.activityType,
                                    activityId = start.activityId,
                                )

                            try {
                                val completion = activityThread.start().await()

                                coreWorker.completeActivityTask(completion)
                                logger.debug("[pollActivityTasks] Completed activity: $activityInfo")

                                // Fire ActivityTaskCompleted hooks
                                val duration = (System.currentTimeMillis() - startTime).milliseconds
                                val completedContext =
                                    ActivityTaskCompletedContext(
                                        task = task,
                                        activityType = start.activityType,
                                        duration = duration,
                                    )
                                applicationHooks.call(ActivityTaskCompleted, completedContext)
                                config.hookRegistry.call(ActivityTaskCompleted, completedContext)
                            } catch (e: CancellationException) {
                                // Re-throw cancellation to properly propagate it
                                throw e
                            } catch (e: Exception) {
                                // Fire ActivityTaskFailed hooks
                                val failedContext =
                                    ActivityTaskFailedContext(
                                        task = task,
                                        activityType = start.activityType,
                                        error = e,
                                    )
                                applicationHooks.call(ActivityTaskFailed, failedContext)
                                config.hookRegistry.call(ActivityTaskFailed, failedContext)

                                logger.warn("[pollActivityTasks] Error dispatching activity", e)
                            } finally {
                                // Untrack thread
                                runningActivityThreads.remove(threadId)
                            }
                        } else {
                            // Cancel task - dispatch directly without virtual thread
                            // This allows it to interrupt running activities immediately
                            activityDispatcher.dispatchCancel(task)
                        }
                    }
                } catch (_: CancellationException) {
                    logger.info("[pollActivityTasks] Cancelled, exiting")
                    break
                } catch (e: Exception) {
                    // Log and continue on errors for now
                    if (!shutdownSignal.isCompleted) {
                        logger.warn("[pollActivityTasks] Error in polling loop", e)
                    }
                }
            }
        } finally {
            // NonCancellable ensures cleanup completes even if parent scope is force-cancelled
            withContext(NonCancellable) {
                logger.debug("[pollActivityTasks] Waiting for running activities to complete...")
                val shutdownStart = System.currentTimeMillis()
                rootActivityJob.cancel()

                // Wait for graceful completion with timeout (configurable per task queue)
                val gracefullyCompleted =
                    withTimeoutOrNull(config.shutdownGracePeriodMs) {
                        rootActivityJob.join()
                        true
                    } ?: false

                if (!gracefullyCompleted && runningActivityThreads.isNotEmpty()) {
                    // Some activities didn't complete - attempt to terminate their threads
                    logger.warn(
                        "[TKT1201] Activity shutdown timeout, {} activities still running. Attempting forced termination.",
                        runningActivityThreads.size,
                    )

                    // Try to terminate each remaining thread
                    runningActivityThreads.values.forEach { info ->
                        if (info.thread.isAlive()) {
                            info.thread.terminate(immediate = true)
                        }
                    }

                    // Final join (may still block on zombies but that's expected)
                    rootActivityJob.join()
                }

                // Wait for any zombie eviction jobs (launched from ActivityDispatcher on cancel)
                activityDispatcher.awaitZombieEviction()

                val shutdownDuration = System.currentTimeMillis() - shutdownStart
                if (shutdownDuration > 1000) {
                    logger.info("[pollActivityTasks] Waited ${shutdownDuration}ms for activities to complete")
                }
                logger.info("[pollActivityTasks] Activity polling stopped for taskQueue=$taskQueue")
            }
        }
    }
}
