package com.surrealdev.temporal.client

import com.google.protobuf.util.Durations
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.PluginPipeline
import com.surrealdev.temporal.application.plugin.interceptor.CountWorkflowsInput
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorChain
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorRegistry
import com.surrealdev.temporal.application.plugin.interceptor.ListWorkflowsInput
import com.surrealdev.temporal.application.plugin.interceptor.StartWorkflowInput
import com.surrealdev.temporal.client.internal.WorkflowServiceClient
import com.surrealdev.temporal.common.SearchAttributeEncoder
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.NoOpCodec
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.payloadCodecOrNull
import com.surrealdev.temporal.serialization.payloadSerializer
import com.surrealdev.temporal.serialization.safeEncode
import com.surrealdev.temporal.util.Attributes
import io.temporal.api.common.v1.SearchAttributes
import io.temporal.api.common.v1.WorkflowType
import io.temporal.api.taskqueue.v1.TaskQueue
import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger(TemporalClientImpl::class.java)

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
interface TemporalClient {
    /**
     * The payload serializer used by this client.
     */
    val serializer: PayloadSerializer

    /**
     * Starts a new workflow execution and returns a handle to it.
     *
     * This uses raw payloads for arguments. For type-safe overloads, use the reified extension functions
     * [startWorkflow]
     *
     * @param R The expected result type of the workflow.
     * @param workflowType The workflow type name.
     * @param taskQueue The task queue to run the workflow on.
     * @param workflowId The workflow ID.
     * @param args Arguments to pass to the workflow.
     * @param options Additional workflow options.
     * @param resultTypeInfo Type information for the expected result type.
     * @return A handle to the started workflow execution.
     */
    @InternalTemporalApi
    suspend fun startWorkflowWithPayloads(
        workflowType: String,
        taskQueue: String,
        workflowId: String,
        args: TemporalPayloads,
        options: WorkflowStartOptions,
    ): WorkflowHandle

    /**
     * Gets a handle to an existing workflow. This is an internal API - use the
     * [getWorkflowHandle]` extension function instead for type-safe access.
     *
     * @param R The expected result type of the workflow.
     * @param workflowId The workflow ID.
     * @param runId Optional run ID. If not specified, the latest run is used.
     * @param resultTypeInfo Type information for the expected result type.
     * @return A handle to the workflow execution.
     */
    @InternalTemporalApi
    fun getWorkflowHandleInternal(
        workflowId: String,
        runId: String?,
    ): WorkflowHandle

    /**
     * Lists workflow executions matching the given query.
     *
     * Uses Temporal's visibility query language for filtering.
     *
     * Example:
     * ```kotlin
     * // List all running workflows
     * val running = client.listWorkflows("ExecutionStatus = 'Running'")
     *
     * // List workflows with custom search attribute
     * val premium = client.listWorkflows("CustomKeyword = 'premium'")
     * ```
     *
     * @param query Visibility query string (empty string returns all)
     * @param pageSize Maximum number of results to return
     * @return List of workflow executions matching the query
     */
    suspend fun listWorkflows(
        query: String = "",
        pageSize: Int = 100,
    ): WorkflowExecutionList

    /**
     * Counts workflow executions matching the given query.
     *
     * Uses Temporal's visibility query language for filtering.
     *
     * Example:
     * ```kotlin
     * // Count all running workflows
     * val runningCount = client.countWorkflows("ExecutionStatus = 'Running'")
     *
     * // Count workflows with custom search attribute
     * val premiumCount = client.countWorkflows("CustomKeyword = 'premium'")
     * ```
     *
     * @param query Visibility query string (empty string counts all)
     * @return Number of workflow executions matching the query
     */
    suspend fun countWorkflows(query: String = ""): Long

    /**
     * Closes the client connection.
     */
    suspend fun close()

    companion object {
        /**
         * Creates a new client from an existing core client.
         *
         * @param coreClient The low-level core client.
         * @param namespace The namespace to use.
         * @param serializer The payload serializer. Defaults to JSON serializer.
         * @param codec The payload codec. Defaults to no-op codec.
         */
        fun create(
            coreClient: TemporalCoreClient,
            namespace: String = "default",
            serializer: PayloadSerializer = CompositePayloadSerializer.default(),
            codec: PayloadCodec = NoOpCodec,
            interceptorRegistry: InterceptorRegistry = InterceptorRegistry.EMPTY,
        ): TemporalClient {
            val config =
                TemporalClientConfig().apply {
                    this.target = coreClient.targetUrl
                    this.namespace = namespace
                }
            return TemporalClientImpl(coreClient, config, serializer, codec, interceptorRegistry)
        }

        /**
         * Connects to a Temporal service and returns a client.
         *
         * This is the primary way to create a standalone client for interacting with Temporal.
         * Plugins can be installed for serialization, codecs, and interceptors.
         *
         * Example with API key:
         * ```kotlin
         * val client = TemporalClient.connect {
         *     target = "https://myns.abc123.tmprl.cloud:7233"
         *     namespace = "myns.abc123"
         *     apiKey = System.getenv("TEMPORAL_API_KEY")
         * }
         * ```
         *
         * Example with plugins:
         * ```kotlin
         * val client = TemporalClient.connect {
         *     target = "http://localhost:7233"
         *     install(SerializationPlugin) { json { prettyPrint = true } }
         *     install(CodecPlugin) { compression() }
         *     install(MyPlugin) { enabled = true }
         * }
         * ```
         *
         * @param configure Configuration block for connection settings and plugin installation.
         * @return A connected TemporalClient.
         */
        suspend fun connect(configure: TemporalClientConfig.() -> Unit): TemporalClient {
            val config = TemporalClientConfig().apply(configure)

            val serializer = config.payloadSerializer()
            val codec = config.payloadCodecOrNull() ?: NoOpCodec

            val runtime =
                com.surrealdev.temporal.core.TemporalRuntime
                    .create()

            val tlsOptions =
                config.tls?.let { tlsConfig ->
                    com.surrealdev.temporal.core.TlsOptions(
                        serverRootCaCert = tlsConfig.serverRootCaCert,
                        domain = tlsConfig.domain,
                        clientCert = tlsConfig.clientCert,
                        clientPrivateKey = tlsConfig.clientPrivateKey,
                    )
                }

            val coreClient =
                TemporalCoreClient.connect(
                    runtime = runtime,
                    targetUrl = config.target,
                    namespace = config.namespace,
                    tls = tlsOptions,
                    apiKey = config.apiKey,
                )

            return ConnectedTemporalClient(
                coreClient,
                config,
                serializer,
                codec,
                runtime,
                config.interceptorRegistry,
            )
        }
    }
}

/**
 * A TemporalClient that owns its connection and runtime.
 * Closing this client will close the underlying core client and runtime.
 */
private class ConnectedTemporalClient(
    private val coreClient: TemporalCoreClient,
    config: TemporalClientConfig,
    serializer: PayloadSerializer,
    codec: PayloadCodec,
    private val runtime: com.surrealdev.temporal.core.TemporalRuntime,
    interceptorRegistry: InterceptorRegistry = InterceptorRegistry.EMPTY,
) : TemporalClient by TemporalClientImpl(coreClient, config, serializer, codec, interceptorRegistry) {
    override suspend fun close() {
        coreClient.close()
        runtime.close()
    }
}

/**
 * Gets a handle to an existing workflow execution.
 *
 * @param workflowId The workflow ID.
 * @param runId Optional run ID. If not specified, the latest run is used.
 * @return A handle to the workflow execution.
 */
fun TemporalClient.getWorkflowHandle(
    workflowId: String,
    runId: String? = null,
): WorkflowHandle =
    getWorkflowHandleInternal(
        workflowId = workflowId,
        runId = runId,
    )

/**
 * Default implementation of [TemporalClient].
 */
class TemporalClientImpl internal constructor(
    private val coreClient: TemporalCoreClient,
    private val config: TemporalClientConfig,
    override val serializer: PayloadSerializer,
    internal val codec: PayloadCodec,
    internal val interceptorRegistry: InterceptorRegistry = InterceptorRegistry.EMPTY,
) : TemporalClient {
    internal val serviceClient = WorkflowServiceClient(coreClient, config.namespace)

    override suspend fun startWorkflowWithPayloads(
        workflowType: String,
        taskQueue: String,
        workflowId: String,
        args: TemporalPayloads,
        options: WorkflowStartOptions,
    ): WorkflowHandle {
        val input =
            StartWorkflowInput(
                workflowType = workflowType,
                taskQueue = taskQueue,
                workflowId = workflowId,
                args = args,
                options = options,
            )

        return InterceptorChain(interceptorRegistry.startWorkflow).execute(input) { inp ->
            doStartWorkflow(inp)
        }
    }

    private suspend fun doStartWorkflow(input: StartWorkflowInput): WorkflowHandle {
        // Encode args through codec before sending to server
        val encodedArgs =
            if (!input.args.isEmpty) {
                codec.safeEncode(input.args).toProto()
            } else {
                input.args.toProto()
            }

        // Build the request
        val requestBuilder =
            StartWorkflowExecutionRequest
                .newBuilder()
                .setNamespace(config.namespace)
                .setWorkflowId(input.workflowId)
                .setWorkflowType(
                    WorkflowType
                        .newBuilder()
                        .setName(input.workflowType)
                        .build(),
                ).setTaskQueue(
                    TaskQueue
                        .newBuilder()
                        .setName(input.taskQueue)
                        .build(),
                ).setInput(encodedArgs)
                .setRequestId(UUID.randomUUID().toString())
                .setWorkflowIdReusePolicy(input.options.workflowIdReusePolicy.toProto())
                .setWorkflowIdConflictPolicy(input.options.workflowIdConflictPolicy.toProto())

        // Apply optional timeouts
        input.options.workflowExecutionTimeout?.let {
            requestBuilder.setWorkflowExecutionTimeout(
                Durations.fromMillis(it.inWholeMilliseconds),
            )
        }
        input.options.workflowRunTimeout?.let {
            requestBuilder.setWorkflowRunTimeout(
                Durations.fromMillis(it.inWholeMilliseconds),
            )
        }
        input.options.workflowTaskTimeout?.let {
            requestBuilder.setWorkflowTaskTimeout(
                Durations.fromMillis(it.inWholeMilliseconds),
            )
        }

        // Apply retry policy if specified
        input.options.retryPolicy?.let { retryPolicy ->
            val retryBuilder =
                io.temporal.api.common.v1.RetryPolicy
                    .newBuilder()
            retryBuilder.setInitialInterval(Durations.fromMillis(retryPolicy.initialInterval.inWholeMilliseconds))
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
        input.options.cronSchedule?.let {
            requestBuilder.setCronSchedule(it)
        }

        // Apply search attributes if specified
        input.options.searchAttributes?.let { attrs ->
            if (attrs.isNotEmpty()) {
                val encoded = SearchAttributeEncoder.encode(attrs)
                requestBuilder.setSearchAttributes(
                    SearchAttributes
                        .newBuilder()
                        .putAllIndexedFields(encoded),
                )
            }
        }

        // Apply headers from interceptor input (may be modified by interceptors)
        if (input.headers.isNotEmpty()) {
            requestBuilder.setHeader(
                io.temporal.api.common.v1.Header
                    .newBuilder()
                    .putAllFields(input.headers.mapValues { (_, v) -> v.toProto() }),
            )
        }

        logger.info(
            "[startWorkflow] Starting workflow type=${input.workflowType}, taskQueue=${input.taskQueue}, workflowId=${input.workflowId}",
        )

        val response = serviceClient.startWorkflowExecution(requestBuilder.build())

        logger.info("[startWorkflow] Workflow started: workflowId=${input.workflowId}, runId=${response.runId}")

        return WorkflowHandleImpl(
            workflowId = input.workflowId,
            runId = response.runId,
            serviceClient = serviceClient,
            serializer = serializer,
            codec = codec,
            interceptorRegistry = interceptorRegistry,
        )
    }

    override fun getWorkflowHandleInternal(
        workflowId: String,
        runId: String?,
    ): WorkflowHandle =
        WorkflowHandleImpl(
            workflowId = workflowId,
            runId = runId,
            serviceClient = serviceClient,
            serializer = serializer,
            codec = codec,
            interceptorRegistry = interceptorRegistry,
        )

    override suspend fun listWorkflows(
        query: String,
        pageSize: Int,
    ): WorkflowExecutionList {
        val input = ListWorkflowsInput(query = query, pageSize = pageSize)
        return InterceptorChain(interceptorRegistry.listWorkflows).execute(input) { inp ->
            doListWorkflows(inp)
        }
    }

    private suspend fun doListWorkflows(input: ListWorkflowsInput): WorkflowExecutionList {
        val request =
            ListWorkflowExecutionsRequest
                .newBuilder()
                .setNamespace(config.namespace)
                .setQuery(input.query)
                .setPageSize(input.pageSize)
                .build()

        val response = serviceClient.listWorkflowExecutions(request)

        return WorkflowExecutionList(
            executions =
                response.executionsList.map { info ->
                    WorkflowExecutionInfo(
                        workflowId = info.execution.workflowId,
                        runId = info.execution.runId,
                        workflowType = info.type.name,
                        status = WorkflowExecutionStatus.fromProto(info.status),
                        startTime = info.startTime.seconds * 1000 + info.startTime.nanos / 1_000_000,
                        closeTime =
                            if (info.hasCloseTime()) {
                                info.closeTime.seconds * 1000 + info.closeTime.nanos / 1_000_000
                            } else {
                                null
                            },
                        historyLength = info.historyLength,
                        taskQueue = info.taskQueue,
                    )
                },
            nextPageToken =
                response.nextPageToken
                    .takeIf { !it.isEmpty }
                    ?.let {
                        com.surrealdev.temporal.common
                            .TemporalByteString(it)
                    },
        )
    }

    override suspend fun countWorkflows(query: String): Long {
        val input = CountWorkflowsInput(query = query)
        return InterceptorChain(interceptorRegistry.countWorkflows).execute(input) { inp ->
            doCountWorkflows(inp)
        }
    }

    private suspend fun doCountWorkflows(input: CountWorkflowsInput): Long {
        val request =
            CountWorkflowExecutionsRequest
                .newBuilder()
                .setNamespace(config.namespace)
                .setQuery(input.query)
                .build()

        return serviceClient.countWorkflowExecutions(request).count
    }

    override suspend fun close() {
        // Currently no-op since the core client is managed by the application
    }
}

/**
 * Configuration for a Temporal client.
 *
 * Example:
 * ```kotlin
 * val client = TemporalClient.connect {
 *     target = "https://myns.abc123.tmprl.cloud:7233"
 *     namespace = "myns.abc123"
 *     apiKey = System.getenv("TEMPORAL_API_KEY")
 * }
 * ```
 */
@TemporalDsl
class TemporalClientConfig : PluginPipeline {
    /** Target address of the Temporal service (e.g., "http://localhost:7233" or "https://myns.tmprl.cloud:7233"). */
    var target: String = "http://localhost:7233"

    /** Namespace to connect to. */
    var namespace: String = "default"

    /** TLS configuration. If null and target uses https://, TLS is auto-enabled with system CAs. */
    var tls: TlsConfig? = null

    /** API key for Temporal Cloud authentication (alternative to mTLS). */
    var apiKey: String? = null

    // PluginPipeline implementation
    override val attributes: Attributes = Attributes(concurrent = false)
    override val parentScope: com.surrealdev.temporal.util.AttributeScope? = null

    /** Interceptor registry for client interceptors installed via plugins. */
    val interceptorRegistry: InterceptorRegistry = InterceptorRegistry()

    /**
     * Configure TLS using a builder.
     */
    fun tls(block: TlsConfigBuilder.() -> Unit) {
        tls = TlsConfigBuilder().apply(block).build()
    }
}

/**
 * Builder for TlsConfig within TemporalClientConfig.
 */
class TlsConfigBuilder {
    var serverRootCaCert: ByteArray? = null
    var domain: String? = null
    var clientCert: ByteArray? = null
    var clientPrivateKey: ByteArray? = null

    fun fromFiles(
        serverRootCaCertPath: String? = null,
        clientCertPath: String? = null,
        clientPrivateKeyPath: String? = null,
    ) {
        serverRootCaCert = serverRootCaCertPath?.let { java.io.File(it).readBytes() }
        clientCert = clientCertPath?.let { java.io.File(it).readBytes() }
        clientPrivateKey = clientPrivateKeyPath?.let { java.io.File(it).readBytes() }
    }

    internal fun build(): TlsConfig =
        TlsConfig(
            serverRootCaCert = serverRootCaCert,
            domain = domain,
            clientCert = clientCert,
            clientPrivateKey = clientPrivateKey,
        )
}
