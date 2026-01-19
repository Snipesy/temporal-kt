package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.PayloadSerializer
import coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivation
import coresdk.workflow_completion.WorkflowCompletion
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 *
 * Thread safety: The dispatcher is designed for concurrent access.
 * Individual workflow executors are processed sequentially (one activation at a time).
 */
internal class WorkflowDispatcher(
    private val registry: WorkflowRegistry,
    private val serializer: PayloadSerializer,
    private val taskQueue: String,
    private val namespace: String,
    maxConcurrent: Int,
) {
    private val semaphore = Semaphore(maxConcurrent)

    /**
     * Cache of workflow executors by run_id.
     * Workflow runs can span multiple activations, so we cache executors.
     */
    private val executors = ConcurrentHashMap<String, WorkflowExecutor>()

    /**
     * Dispatches a workflow activation to the appropriate executor.
     *
     * @param activation The workflow activation from the Temporal server
     * @param scope The coroutine scope for workflow execution
     * @return The completion to send back to the server
     */
    suspend fun dispatch(
        activation: WorkflowActivation,
        scope: CoroutineScope,
    ): WorkflowCompletion.WorkflowActivationCompletion {
        val runId = activation.runId

        // Handle eviction (remove from cache)
        if (isEviction(activation)) {
            executors.remove(runId)
            return buildEmptyCompletion(runId)
        }

        return semaphore.withPermit {
            dispatchInternal(activation, scope)
        }
    }

    private suspend fun dispatchInternal(
        activation: WorkflowActivation,
        scope: CoroutineScope,
    ): WorkflowCompletion.WorkflowActivationCompletion {
        val runId = activation.runId

        // Get or create executor
        val executor =
            executors.getOrPut(runId) {
                createExecutor(activation) ?: return buildNotFoundFailure(activation)
            }

        // Process the activation
        return executor.activate(activation, scope)
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
            taskQueue = taskQueue,
            namespace = namespace,
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

    /**
     * Removes all cached executors.
     * Called during worker shutdown.
     */
    fun clear() {
        executors.clear()
    }

    /**
     * Gets the number of cached workflow executors.
     */
    fun cachedCount(): Int = executors.size
}
