package com.surrealdev.temporal.client

import com.google.protobuf.util.Durations
import com.surrealdev.temporal.client.internal.WorkflowServiceClient
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.typeInfoOf
import io.temporal.api.common.v1.Payloads
import io.temporal.api.common.v1.WorkflowType
import io.temporal.api.taskqueue.v1.TaskQueue
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger(TemporalClient::class.java)

/**
 * Client for interacting with the Temporal service.
 *
 * Provides methods for starting workflows, getting handles to existing workflows,
 * and interacting with workflow executions.
 *
 * Usage:
 * ```kotlin
 * // Get client from application
 * val client = app.client()
 *
 * // Start a workflow
 * val handle = client.startWorkflow<String>(
 *     workflowType = "GreetingWorkflow",
 *     taskQueue = "greetings",
 *     args = listOf("World"),
 * )
 *
 * // Wait for result
 * val result = handle.result(timeout = 30.seconds)
 * println("Result: $result")
 *
 * // Get handle to existing workflow
 * val existingHandle = client.getWorkflowHandle<String>("workflow-id-123")
 * val history = existingHandle.getHistory()
 * ```
 */
class TemporalClient internal constructor(
    private val coreClient: TemporalCoreClient,
    private val config: TemporalClientConfig,
    val serializer: PayloadSerializer,
) {
    private val serviceClient = WorkflowServiceClient(coreClient, config.namespace)

    /**
     * Internal method to start a new workflow execution.
     */
    @PublishedApi
    internal suspend fun <R> startWorkflowInternal(
        workflowType: String,
        taskQueue: String,
        workflowId: String,
        args: Payloads,
        options: WorkflowStartOptions,
        resultTypeInfo: com.surrealdev.temporal.serialization.TypeInfo,
    ): WorkflowHandle<R> {
        // Build the request
        val requestBuilder =
            StartWorkflowExecutionRequest
                .newBuilder()
                .setNamespace(config.namespace)
                .setWorkflowId(workflowId)
                .setWorkflowType(
                    WorkflowType
                        .newBuilder()
                        .setName(workflowType)
                        .build(),
                ).setTaskQueue(
                    TaskQueue
                        .newBuilder()
                        .setName(taskQueue)
                        .build(),
                ).setInput(args)
                .setRequestId(UUID.randomUUID().toString())
                .setWorkflowIdReusePolicy(options.workflowIdReusePolicy.toProto())
                .setWorkflowIdConflictPolicy(options.workflowIdConflictPolicy.toProto())

        // Apply optional timeouts
        options.workflowExecutionTimeout?.let {
            requestBuilder.setWorkflowExecutionTimeout(
                Durations.fromMillis(it.inWholeMilliseconds),
            )
        }
        options.workflowRunTimeout?.let {
            requestBuilder.setWorkflowRunTimeout(
                Durations.fromMillis(it.inWholeMilliseconds),
            )
        }
        options.workflowTaskTimeout?.let {
            requestBuilder.setWorkflowTaskTimeout(
                Durations.fromMillis(it.inWholeMilliseconds),
            )
        }

        // Apply retry policy if specified
        options.retryPolicy?.let { retryPolicy ->
            val retryBuilder =
                io.temporal.api.common.v1.RetryPolicy
                    .newBuilder()
            retryPolicy.initialInterval?.let {
                retryBuilder.setInitialInterval(Durations.fromMillis(it.inWholeMilliseconds))
            }
            retryBuilder.setBackoffCoefficient(retryPolicy.backoffCoefficient)
            retryPolicy.maximumInterval?.let {
                retryBuilder.setMaximumInterval(Durations.fromMillis(it.inWholeMilliseconds))
            }
            retryBuilder.setMaximumAttempts(retryPolicy.maximumAttempts)
            retryPolicy.nonRetryableErrorTypes.forEach {
                retryBuilder.addNonRetryableErrorTypes(it)
            }
            requestBuilder.setRetryPolicy(retryBuilder.build())
        }

        // Apply cron schedule if specified
        options.cronSchedule?.let {
            requestBuilder.setCronSchedule(it)
        }

        logger.info(
            "[startWorkflow] Starting workflow type=$workflowType, taskQueue=$taskQueue, workflowId=$workflowId",
        )

        val response = serviceClient.startWorkflowExecution(requestBuilder.build())

        logger.info("[startWorkflow] Workflow started: workflowId=$workflowId, runId=${response.runId}")

        return WorkflowHandleImpl(
            workflowId = workflowId,
            runId = response.runId,
            resultTypeInfo = resultTypeInfo,
            serviceClient = serviceClient,
            serializer = serializer,
        )
    }

    /**
     * Gets a handle to an existing workflow execution.
     *
     * @param R The expected result type of the workflow.
     * @param workflowId The workflow ID.
     * @param runId Optional run ID. If not specified, the latest run is used.
     * @return A handle to the workflow execution.
     */
    inline fun <reified R> getWorkflowHandle(
        workflowId: String,
        runId: String? = null,
    ): WorkflowHandle<R> =
        getWorkflowHandleInternal(
            workflowId = workflowId,
            runId = runId,
            resultTypeInfo = typeInfoOf<R>(),
        )

    @PublishedApi
    internal fun <R> getWorkflowHandleInternal(
        workflowId: String,
        runId: String?,
        resultTypeInfo: com.surrealdev.temporal.serialization.TypeInfo,
    ): WorkflowHandle<R> =
        WorkflowHandleImpl(
            workflowId = workflowId,
            runId = runId,
            resultTypeInfo = resultTypeInfo,
            serviceClient = serviceClient,
            serializer = serializer,
        )

    /**
     * Closes the client connection.
     *
     * Note: The underlying core client may be shared with other components.
     */
    suspend fun close() {
        // Currently no-op since the core client is managed by the application
    }

    companion object {
        /**
         * Creates a new client connected to the specified Temporal service.
         *
         * @param coreClient The low-level core client.
         * @param namespace The namespace to use.
         * @param serializer The payload serializer. Defaults to JSON serializer.
         */
        fun create(
            coreClient: TemporalCoreClient,
            namespace: String = "default",
            serializer: PayloadSerializer = KotlinxJsonSerializer.default(),
        ): TemporalClient {
            val config =
                TemporalClientConfig().apply {
                    this.target = coreClient.targetUrl
                    this.namespace = namespace
                }
            return TemporalClient(coreClient, config, serializer)
        }
    }
}

/**
 * Configuration for a Temporal client.
 */
class TemporalClientConfig {
    /** Target address of the Temporal service. */
    var target: String = "localhost:7233"

    /** Namespace to connect to. */
    var namespace: String = "default"

    /** Whether to use TLS. TODO */
    var useTls: Boolean = false
}
