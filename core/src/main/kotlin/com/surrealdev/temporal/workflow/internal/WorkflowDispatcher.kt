package com.surrealdev.temporal.workflow.internal

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
) {
    private val logger = LoggerFactory.getLogger(WorkflowDispatcher::class.java)

    private val semaphore = Semaphore(maxConcurrent)

    /**
     * Entry containing the executor and its per-run mutex.
     * The mutex ensures activations for the same run_id are processed sequentially,
     * even if the Core SDK or polling somehow sends concurrent activations (defensive).
     */
    private data class ExecutorEntry(
        val executor: WorkflowExecutor,
        val mutex: Mutex = Mutex(),
    )

    /**
     * Cache of workflow executor entries by run_id.
     * Workflow runs can span multiple activations, so we cache executors.
     */
    private val executors = ConcurrentHashMap<String, ExecutorEntry>()

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
                it.mutex.withLock {
                    it.executor.terminateWorkflowExecutionJob()
                }
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
        return entry.mutex.withLock {
            entry.executor.activate(activation)
        }
    }

    private fun createExecutorEntry(activation: WorkflowActivation): ExecutorEntry? {
        val executor = createExecutor(activation) ?: return null
        return ExecutorEntry(executor)
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

    /**
     * Removes all cached executors and cancels their execution jobs.
     * Called during worker shutdown.
     */
    suspend fun clear() {
        executors.values.forEach { entry ->
            entry.mutex.withLock {
                entry.executor.terminateWorkflowExecutionJob()
            }
        }
        executors.clear()
    }

    /**
     * Gets the number of cached workflow executors.
     */
    fun cachedCount(): Int = executors.size
}
