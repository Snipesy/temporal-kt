package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.application.plugin.interceptor.InterceptorRegistry
import com.surrealdev.temporal.common.exceptions.WorkflowDeadlockException
import com.surrealdev.temporal.common.failure.FAILURE_SOURCE
import com.surrealdev.temporal.internal.ZombieEvictionConfig
import com.surrealdev.temporal.internal.ZombieEvictionManager
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.AttributeScope
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_completion.WorkflowCompletion
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dispatches workflow activations to the appropriate workflow executors.
 *
 * This dispatcher:
 * - Maintains a cache of workflow executors by run_id
 * - Creates new executors when a workflow is initialized
 * - Routes activations to existing executors for ongoing workflows
 * - Handles eviction/removal of executors from the cache
 * - Enforces concurrency limits via semaphore
 * - Serializes activations per run_id via per-executor mutex
 *
 * Thread safety: The dispatcher is designed for concurrent access.
 * Different workflow runs can be processed in parallel (up to maxConcurrent).
 * Individual workflow executors are processed sequentially via mutex (one activation at a time per run_id).
 */
internal class WorkflowDispatcher(
    private val registry: WorkflowRegistry,
    private val serializer: PayloadSerializer,
    private val codec: PayloadCodec,
    private val taskQueue: String,
    private val namespace: String,
    maxConcurrent: Int,
    /**
     * The task queue scope for hierarchical attribute lookup.
     * Its parentScope should be the application.
     */
    private val taskQueueScope: AttributeScope,
    /**
     * Merged interceptor registry (application + task-queue level).
     * Passed to workflow executors and context for interceptor chain execution.
     */
    private val interceptorRegistry: InterceptorRegistry = InterceptorRegistry.EMPTY,
    /**
     * Parent job for structured concurrency.
     * All workflow executors will be children of this job (rootExecutorJob).
     */
    private val parentJob: Job,
    /**
     * Thread factory for creating virtual threads for workflow execution.
     * Each workflow run gets a dedicated virtual thread.
     */
    private val workflowThreadFactory: ThreadFactory,
    /**
     * Timeout in milliseconds for detecting workflow deadlocks.
     * If a workflow activation doesn't complete within this time, a WorkflowDeadlockException is thrown.
     * Set to 0 to disable deadlock detection.
     */
    private val deadlockTimeoutMs: Long = 2000L,
    /**
     * Configuration for zombie thread eviction.
     */
    private val zombieConfig: ZombieEvictionConfig = ZombieEvictionConfig(),
    /**
     * Callback invoked when a fatal error occurs (e.g., zombie threshold exceeded).
     * This allows the application to gracefully shut down instead of calling System.exit().
     * The callback is invoked from a coroutine context, so it can be a suspend function.
     */
    private val onFatalError: (suspend () -> Unit)? = null,
) {
    private val logger = LoggerFactory.getLogger(WorkflowDispatcher::class.java)

    private val semaphore = Semaphore(maxConcurrent)

    /** Flag indicating shutdown is in progress - prevents new dispatches from creating executors */
    @Volatile
    private var shuttingDown = false

    /** Counter for in-flight dispatch operations */
    private val inFlightDispatches = AtomicInteger(0)

    /**
     * Entry containing the executor, its per-run mutex, and its dedicated virtual thread.
     * The mutex ensures activations for the same run_id are processed sequentially,
     * even if the Core SDK or polling somehow sends concurrent activations (defensive).
     * The virtualThread ensures all activations run on the same thread, preserving ThreadLocals.
     */
    private data class ExecutorEntry(
        val executor: WorkflowExecutor,
        val mutex: Mutex = Mutex(),
        val virtualThread: WorkflowVirtualThread,
    )

    /**
     * Cache of workflow executor entries by run_id.
     * Workflow runs can span multiple activations, so we cache executors.
     */
    private val executors = ConcurrentHashMap<String, ExecutorEntry>()

    /**
     * Manages zombie thread detection and eviction.
     */
    private val zombieManager =
        ZombieEvictionManager(
            logger = logger,
            taskQueue = taskQueue,
            config = zombieConfig,
            onFatalError = onFatalError,
            errorCodePrefix = "TKT11",
            entityType = "workflow",
        )

    /**
     * Dispatches a workflow activation to the appropriate executor.
     *
     * This method is safe to call concurrently for different workflow runs.
     * Activations for the same run_id are serialized via a per-run mutex.
     *
     * @param activation The workflow activation from the Temporal server
     * @return The completion to send back to the server
     */
    suspend fun dispatch(activation: WorkflowActivation): WorkflowCompletion.WorkflowActivationCompletion {
        val runId = activation.runId

        // Handle eviction (always allowed, even during shutdown)
        if (isEviction(activation)) {
            val entry = executors.remove(runId)
            entry?.let {
                // Cancel the executor job
                it.executor.terminateWorkflowExecutionJob()
                // Launch async termination - doesn't block the poll
                launchTerminationJob(
                    virtualThread = it.virtualThread,
                    runId = runId,
                    workflowType = "evicted", // Type unknown at eviction time
                )
            }
            return buildEmptyCompletion(runId)
        }

        // Reject new work during shutdown
        if (shuttingDown) {
            logger.warn("Rejecting activation during shutdown. run_id={}", runId)
            return buildShutdownFailure(activation)
        }

        // Track in-flight dispatch
        inFlightDispatches.incrementAndGet()
        try {
            return semaphore.withPermit {
                // Double-check after acquiring permit
                if (shuttingDown) {
                    return buildShutdownFailure(activation)
                }
                dispatchInternal(activation)
            }
        } finally {
            inFlightDispatches.decrementAndGet()
        }
    }

    private suspend fun dispatchInternal(
        activation: WorkflowActivation,
    ): WorkflowCompletion.WorkflowActivationCompletion {
        val runId = activation.runId
        val hasInitJob = activation.jobsList.any { it.hasInitializeWorkflow() }

        // Get or create executor entry
        val entry =
            executors.getOrPut(runId) {
                if (!hasInitJob) {
                    // No executor and no init job - workflow was unexpectedly evicted
                    logger.warn(
                        "Received activation for unknown workflow run_id={} without init job. " +
                            "Workflow may have been unexpectedly evicted from cache.",
                        runId,
                    )
                    return buildUnexpectedEvictionFailure(activation)
                }
                createExecutorEntry(activation) ?: return buildNotFoundFailure(activation)
            }

        // Warn if we got an init job for an already-cached workflow (shouldn't happen)
        if (hasInitJob && executors.containsKey(runId)) {
            val existingEntry = executors[runId]
            if (existingEntry != null && existingEntry !== entry) {
                logger.warn(
                    "Received init job for workflow that already exists in cache. run_id={}",
                    runId,
                )
            }
        }

        // Serialize activations for this run_id using the per-executor mutex
        // Route through virtual thread to ensure same thread handles all activations
        return entry.mutex.withLock {
            try {
                entry.virtualThread.dispatch(activation)
            } catch (e: WorkflowDeadlockException) {
                // Deadlock detected - clean up and fail the workflow task
                val workflowType =
                    activation.jobsList
                        .find { it.hasInitializeWorkflow() }
                        ?.initializeWorkflow
                        ?.workflowType ?: "unknown"

                logger.error(
                    "[TKT1101] Workflow deadlock detected. " +
                        "run_id={}, workflow_type={}, task_queue={}. {}",
                    runId,
                    workflowType,
                    taskQueue,
                    e.message,
                )

                // Remove from cache
                executors.remove(runId)
                entry.executor.terminateWorkflowExecutionJob()

                // Launch NonCancellable coroutine to keep trying to evict the zombie thread
                launchTerminationJob(entry.virtualThread, runId, workflowType)

                // Return failure completion to Core SDK (workflow task will retry)
                return@withLock buildDeadlockFailure(activation, e)
            }
        }
    }

    private fun createExecutorEntry(activation: WorkflowActivation): ExecutorEntry? {
        val executor = createExecutor(activation) ?: return null
        val virtualThread =
            WorkflowVirtualThread(
                executor = executor,
                threadFactory = workflowThreadFactory,
                deadlockTimeoutMs = deadlockTimeoutMs,
            )
        return ExecutorEntry(executor, Mutex(), virtualThread)
    }

    private fun createExecutor(activation: WorkflowActivation): WorkflowExecutor? {
        // Find the workflow type from the init job
        val initJob = activation.jobsList.find { it.hasInitializeWorkflow() }
        val workflowType =
            initJob?.initializeWorkflow?.workflowType
                ?: return null // No init job, can't determine workflow type

        // Look up the workflow method
        val methodInfo =
            registry.lookup(workflowType)
                ?: return null // Workflow type not registered

        return WorkflowExecutor(
            runId = activation.runId,
            methodInfo = methodInfo,
            serializer = serializer,
            codec = codec,
            taskQueue = taskQueue,
            namespace = namespace,
            taskQueueScope = taskQueueScope,
            interceptorRegistry = interceptorRegistry,
            parentJob = parentJob,
        )
    }

    private fun isEviction(activation: WorkflowActivation): Boolean =
        activation.jobsList.any { it.hasRemoveFromCache() }

    private fun buildEmptyCompletion(runId: String): WorkflowCompletion.WorkflowActivationCompletion =
        WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(runId)
            .setSuccessful(WorkflowCompletion.Success.newBuilder())
            .build()

    private fun buildNotFoundFailure(activation: WorkflowActivation): WorkflowCompletion.WorkflowActivationCompletion {
        val workflowType =
            activation.jobsList
                .find { it.hasInitializeWorkflow() }
                ?.initializeWorkflow
                ?.workflowType
                ?: "unknown"

        val failure =
            Failure
                .newBuilder()
                .setMessage("Workflow type not registered: $workflowType")
                .setSource(FAILURE_SOURCE)
                .build()

        return WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(activation.runId)
            .setFailed(
                WorkflowCompletion.Failure
                    .newBuilder()
                    .setFailure(failure),
            ).build()
    }

    private fun buildUnexpectedEvictionFailure(
        activation: WorkflowActivation,
    ): WorkflowCompletion.WorkflowActivationCompletion {
        val failure =
            Failure
                .newBuilder()
                .setMessage(
                    "Workflow not found in cache (may have been unexpectedly evicted). " +
                        "run_id=${activation.runId}",
                ).setSource(FAILURE_SOURCE)
                .build()

        return WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(activation.runId)
            .setFailed(
                WorkflowCompletion.Failure
                    .newBuilder()
                    .setFailure(failure),
            ).build()
    }

    private fun buildDeadlockFailure(
        activation: WorkflowActivation,
        exception: WorkflowDeadlockException,
    ): WorkflowCompletion.WorkflowActivationCompletion {
        val failure =
            Failure
                .newBuilder()
                .setMessage(exception.message ?: "Workflow deadlock detected")
                .setSource(FAILURE_SOURCE)
                .build()

        return WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(activation.runId)
            .setFailed(
                WorkflowCompletion.Failure
                    .newBuilder()
                    .setFailure(failure),
            ).build()
    }

    private fun buildShutdownFailure(activation: WorkflowActivation): WorkflowCompletion.WorkflowActivationCompletion {
        val failure =
            Failure
                .newBuilder()
                .setMessage("Worker is shutting down")
                .setSource(FAILURE_SOURCE)
                .build()

        return WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(activation.runId)
            .setFailed(
                WorkflowCompletion.Failure
                    .newBuilder()
                    .setFailure(failure),
            ).build()
    }

    /**
     * Removes all cached executors, terminates their virtual threads, and cancels their execution jobs.
     * Called during worker shutdown.
     */
    suspend fun clear() {
        // Signal shutdown to prevent new dispatches from creating executors
        shuttingDown = true

        // Wait for in-flight dispatches to complete (with timeout)
        val drainStart = System.currentTimeMillis()
        while (inFlightDispatches.get() > 0) {
            if (System.currentTimeMillis() - drainStart > DRAIN_TIMEOUT_MS) {
                logger.warn(
                    "[TKT1107] Timeout waiting for {} in-flight dispatches to complete during shutdown",
                    inFlightDispatches.get(),
                )
                break
            }
            delay(10)
        }

        zombieManager.logShutdownWarning()

        // Now safe to iterate and clear - no new entries will be added
        executors.forEach { (runId, entry) ->
            entry.executor.terminateWorkflowExecutionJob()
            launchTerminationJob(
                virtualThread = entry.virtualThread,
                runId = runId,
                workflowType = "shutdown",
            )
        }
        executors.clear()

        // Wait for all termination jobs to complete
        zombieManager.awaitAllEvictions()
    }

    /**
     * Launches an async job to terminate a workflow thread and monitor for zombies.
     * This method does NOT block - it launches a job that handles termination asynchronously.
     *
     * Termination is always immediate (cancel + interrupt) with no grace period.
     * At this point the task should be done, anything lingering is considered a leak.
     */
    private fun launchTerminationJob(
        virtualThread: WorkflowVirtualThread,
        runId: String,
        workflowType: String,
    ) {
        zombieManager.launchEviction(
            zombieId = runId,
            entityId = runId,
            entityName = workflowType,
            terminateFn = { virtualThread.terminate(immediate = true) },
            interruptFn = { virtualThread.interruptThread() },
            isAliveFn = { virtualThread.isAlive() },
            joinFn = { timeout -> virtualThread.awaitTermination(timeout.inWholeMilliseconds) },
            getStackTraceFn = { virtualThread.getStackTrace() },
        )
    }

    /**
     * Gets the number of cached workflow executors.
     */
    fun cachedCount(): Int = executors.size

    /**
     * Gets the current count of zombie workflow threads.
     */
    fun getZombieCount(): Int = zombieManager.getZombieCount()

    companion object {
        /**
         * Timeout for draining in-flight dispatches during shutdown.
         * If dispatches don't complete within this time, shutdown proceeds anyway.
         */
        private const val DRAIN_TIMEOUT_MS = 10000
    }
}
