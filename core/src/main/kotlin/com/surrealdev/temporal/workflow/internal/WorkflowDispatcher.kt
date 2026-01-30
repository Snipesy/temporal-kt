package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.internal.ZombieEvictionManager
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.AttributeScope
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_completion.WorkflowCompletion
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory

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
     * Grace period in milliseconds to wait for a workflow thread to terminate after interrupt.
     * If the thread doesn't terminate within this time, it's considered a zombie.
     */
    private val terminationGracePeriodMs: Long = 60_000L,
    /**
     * Maximum number of zombie threads before forcing worker shutdown.
     * When this threshold is exceeded, the onFatalError callback is invoked.
     * Set to 0 to disable (not recommended).
     */
    private val maxZombieCount: Int = 10,
    /**
     * Callback invoked when a fatal error occurs (e.g., zombie threshold exceeded).
     * This allows the application to gracefully shut down instead of calling System.exit().
     * The callback is invoked from a coroutine context, so it can be a suspend function.
     */
    private val onFatalError: (suspend () -> Unit)? = null,
    /**
     * Maximum number of retry attempts for zombie eviction before giving up.
     */
    private val maxZombieRetries: Int = 100,
    /**
     * Interval between zombie eviction retry attempts.
     */
    private val zombieRetryIntervalMs: Long = 5_000L,
    /**
     * Timeout for waiting on zombie eviction jobs during shutdown.
     */
    private val zombieEvictionShutdownTimeoutMs: Long = 30_000L,
) {
    private val logger = LoggerFactory.getLogger(WorkflowDispatcher::class.java)

    private val semaphore = Semaphore(maxConcurrent)

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
            terminationGracePeriodMs = terminationGracePeriodMs,
            maxZombieCount = maxZombieCount,
            maxZombieRetries = maxZombieRetries,
            zombieRetryIntervalMs = zombieRetryIntervalMs,
            evictionShutdownTimeoutMs = zombieEvictionShutdownTimeoutMs,
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

        // Handle eviction (remove from cache)
        if (isEviction(activation)) {
            val entry = executors.remove(runId)
            entry?.let {
                // Cancel the executor job
                it.executor.terminateWorkflowExecutionJob()
                // Launch async termination - doesn't block the poll
                // Use graceful termination since the thread should be idle
                launchTerminationJob(
                    virtualThread = it.virtualThread,
                    runId = runId,
                    workflowType = "evicted", // Type unknown at eviction time
                    immediate = false,
                )
            }
            return buildEmptyCompletion(runId)
        }

        return semaphore.withPermit {
            dispatchInternal(activation)
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
                launchTerminationJob(entry.virtualThread, runId, workflowType, immediate = true)

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
                .setSource("Kotlin")
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
                ).setSource("Kotlin")
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
                .setSource("Kotlin")
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
        zombieManager.logShutdownWarning()

        // Launch async termination for all executors
        executors.forEach { (runId, entry) ->
            entry.executor.terminateWorkflowExecutionJob()
            launchTerminationJob(
                virtualThread = entry.virtualThread,
                runId = runId,
                workflowType = "shutdown",
                immediate = false,
            )
        }
        executors.clear()

        // Wait for all termination jobs to complete
        zombieManager.awaitAllEvictions()
    }

    /**
     * Launches an async job to terminate a workflow thread and monitor for zombies.
     * This method does NOT block - it launches a job that handles termination asynchronously.
     */
    private fun launchTerminationJob(
        virtualThread: WorkflowVirtualThread,
        runId: String,
        workflowType: String,
        immediate: Boolean = true,
    ) {
        zombieManager.launchEviction(
            zombieId = runId,
            entityId = runId,
            entityName = workflowType,
            terminateFn = { imm -> virtualThread.terminate(immediate = imm) },
            interruptFn = { virtualThread.interruptThread() },
            isAliveFn = { virtualThread.isAlive() },
            awaitTerminationFn = { timeout -> virtualThread.awaitTermination(timeout) },
            immediate = immediate,
        )
    }

    /**
     * Gets the number of cached workflow executors.
     */
    fun cachedCount(): Int = executors.size
}
