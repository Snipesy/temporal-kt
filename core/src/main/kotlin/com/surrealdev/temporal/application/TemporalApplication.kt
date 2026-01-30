package com.surrealdev.temporal.application

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.HookRegistry
import com.surrealdev.temporal.application.plugin.HookRegistryImpl
import com.surrealdev.temporal.application.plugin.PluginPipeline
import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetup
import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetupContext
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdown
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdownContext
import com.surrealdev.temporal.application.plugin.hooks.WorkerStarted
import com.surrealdev.temporal.application.plugin.hooks.WorkerStartedContext
import com.surrealdev.temporal.application.plugin.hooks.WorkerStopped
import com.surrealdev.temporal.application.plugin.hooks.WorkerStoppedContext
import com.surrealdev.temporal.application.worker.ManagedWorker
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.client.TemporalClientConfig
import com.surrealdev.temporal.client.TlsConfig
import com.surrealdev.temporal.core.CoreWorkerDeploymentOptions
import com.surrealdev.temporal.core.CoreWorkerDeploymentVersion
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.core.TemporalWorker
import com.surrealdev.temporal.core.TlsOptions
import com.surrealdev.temporal.core.WorkerConfig
import com.surrealdev.temporal.internal.ZombieEvictionConfig
import com.surrealdev.temporal.serialization.NoOpCodec
import com.surrealdev.temporal.serialization.payloadCodecOrNull
import com.surrealdev.temporal.serialization.payloadSerializer
import com.surrealdev.temporal.util.Attributes
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A Temporal application that manages workers and client connections.
 *
 * Usage:
 * ```kotlin
 * val app = TemporalApplication {
 *     connection {
 *         target = "http://localhost:7233"
 *         namespace = "default"
 *     }
 * }
 *
 * app.install(KotlinxSerialization) {
 *     json = Json { prettyPrint = true }
 * }
 *
 * app.taskQueue("my-task-queue") {
 *     workflow(MyWorkflowImpl())
 *     activity(MyActivityImpl())
 * }
 *
 * app.start()
 * ```
 */
@TemporalDsl
open class TemporalApplication internal constructor(
    internal val config: TemporalApplicationConfig,
    public val parentCoroutineContext: CoroutineContext,
) : CoroutineScope,
    PluginPipeline {
    private val logger = LoggerFactory.getLogger(TemporalApplication::class.java)

    // Plugin framework - application is the root scope
    override val attributes: Attributes = Attributes(concurrent = true)
    override val parentScope: com.surrealdev.temporal.util.AttributeScope? = null

    /**
     * Hook registry for application-level lifecycle hooks.
     *
     * Plugins can register hooks via this registry to be notified of lifecycle events.
     */
    val hookRegistry: HookRegistry = HookRegistryImpl()

    private val applicationJob = SupervisorJob(parentCoroutineContext[Job])

    override val coroutineContext: CoroutineContext =
        parentCoroutineContext + applicationJob + CoroutineName("TemporalApp")

    // Task queues can be added before start() via extension functions
    internal val taskQueues = mutableListOf<TaskQueueConfig>()

    // Core infrastructure - initialized on start()
    private var runtime: TemporalRuntime? = null
    private var coreClient: TemporalCoreClient? = null
    private val workers = mutableMapOf<String, ManagedWorker>()

    @Volatile
    private var started = false

    /**
     * Starts the application, connecting to Temporal and starting all workers.
     *
     * @param wait If true, suspends until the application is terminated.
     *             If false (default), returns immediately after starting.
     * @throws IllegalStateException if already started
     */
    suspend fun start(wait: Boolean = false) {
        check(!started) { "Application already started" }
        started = true

        // Create the runtime
        val rt = TemporalRuntime.create()
        runtime = rt

        // Connect to the server with TLS if configured
        val tlsOptions =
            config.connection.tls?.let { tlsConfig ->
                TlsOptions(
                    serverRootCaCert = tlsConfig.serverRootCaCert,
                    domain = tlsConfig.domain,
                    clientCert = tlsConfig.clientCert,
                    clientPrivateKey = tlsConfig.clientPrivateKey,
                )
            }

        val client =
            TemporalCoreClient.connect(
                runtime = rt,
                targetUrl = config.connection.target,
                namespace = config.connection.namespace,
                tls = tlsOptions,
                apiKey = config.connection.apiKey,
            )
        coreClient = client

        // Fire ApplicationSetup hook
        hookRegistry.call(
            ApplicationSetup,
            ApplicationSetupContext(this, rt, client),
        )

        // Create and start workers for each task queue
        for (taskQueueConfig in taskQueues) {
            val effectiveNamespace = taskQueueConfig.namespace ?: config.connection.namespace

            // Convert application deployment options to core-bridge format
            val coreDeploymentOptions =
                config.deployment?.let { appDeployment ->
                    CoreWorkerDeploymentOptions(
                        version =
                            CoreWorkerDeploymentVersion(
                                deploymentName = appDeployment.version.deploymentName,
                                buildId = appDeployment.version.buildId,
                            ),
                        useWorkerVersioning = appDeployment.useWorkerVersioning,
                        defaultVersioningBehavior = appDeployment.defaultVersioningBehavior.value,
                    )
                }

            // Create the core bridge worker
            val coreWorker =
                TemporalWorker.create(
                    runtime = rt,
                    client = client,
                    taskQueue = taskQueueConfig.name,
                    namespace = effectiveNamespace,
                    config =
                        WorkerConfig(
                            deploymentOptions = coreDeploymentOptions,
                            maxConcurrentWorkflowTasks = taskQueueConfig.maxConcurrentWorkflows,
                            maxConcurrentActivities = taskQueueConfig.maxConcurrentActivities,
                            maxHeartbeatThrottleIntervalMs = taskQueueConfig.maxHeartbeatThrottleIntervalMs,
                            defaultHeartbeatThrottleIntervalMs = taskQueueConfig.defaultHeartbeatThrottleIntervalMs,
                        ),
                )

            // Wrap in ManagedWorker
            val managedWorker =
                ManagedWorker(
                    coreWorker = coreWorker,
                    config = taskQueueConfig,
                    parentContext = coroutineContext,
                    serializer = payloadSerializer(),
                    codec = payloadCodecOrNull() ?: NoOpCodec,
                    namespace = effectiveNamespace,
                    applicationHooks = hookRegistry,
                    application = this,
                )

            workers[taskQueueConfig.name] = managedWorker
            managedWorker.start()

            // Fire WorkerStarted hook
            hookRegistry.call(
                WorkerStarted,
                WorkerStartedContext(taskQueueConfig.name, effectiveNamespace),
            )
        }

        // Wait for all workers to be ready (first poll completed)
        for (worker in workers.values) {
            worker.awaitReady()
        }

        if (wait) {
            awaitTermination()
        }
    }

    /**
     * Closes the application, stopping all workers and cleaning up resources.
     *
     * Follows a two-phase shutdown pattern (similar to Ktor):
     * 1. Fire shutdown hook
     * 2. Stop workers with explicit stop calls
     * 3. Cancel application job with grace period
     * 4. Cleanup resources
     */
    suspend fun close() {
        if (!started) return

        // Phase 1: Fire shutdown hook
        hookRegistry.call(
            ApplicationShutdown,
            ApplicationShutdownContext(this),
        )

        // Phase 2: Stop workers with grace period
        // (Keep explicit stop calls until Phase 2 refactor is complete)
        for ((taskQueue, worker) in workers) {
            try {
                worker.stop()

                val namespace =
                    taskQueues.find { it.name == taskQueue }?.namespace
                        ?: config.connection.namespace
                hookRegistry.call(
                    WorkerStopped,
                    WorkerStoppedContext(taskQueue, namespace),
                )
            } catch (e: Exception) {
                logger.warn("Error stopping worker $taskQueue", e)
            }
        }
        workers.clear()

        // Phase 3: Cancel application job with timeout
        applicationJob.cancel()

        val completed =
            withTimeoutOrNull(config.shutdown.gracePeriodMs) {
                applicationJob.join()
                true
            }

        if (completed != true) {
            logger.warn(
                "Application job did not complete within grace period ({}ms), " +
                    "force cancellation in progress",
                config.shutdown.gracePeriodMs,
            )

            // Wait additional time for forced cancellation to complete
            withTimeoutOrNull(config.shutdown.forceTimeoutMs) {
                applicationJob.join()
            }
        }

        // Phase 4: Cleanup resources
        coreClient?.close()
        coreClient = null
        runtime?.close()
        runtime = null

        logger.info("Application closed")
    }

    /**
     * Suspends until the application is terminated.
     * This is typically called from the main function to keep the application running.
     */
    suspend fun awaitTermination() {
        applicationJob.join() // JVM is already shutting down, hook is running
    }

    /**
     * Creates a client for interacting with the Temporal service.
     *
     * @param configure Optional configuration block for the client.
     * @return A configured [TemporalClient] instance.
     * @throws IllegalStateException if the application hasn't been started.
     */
    suspend fun client(configure: TemporalClientConfig.() -> Unit = {}): TemporalClient {
        val coreClientInstance = coreClient ?: throw IllegalStateException("Application not started")

        val clientConfig =
            TemporalClientConfig().apply {
                // Inherit connection settings from application
                target = config.connection.target
                namespace = config.connection.namespace
                configure()
            }

        return TemporalClient.create(
            coreClient = coreClientInstance,
            namespace = clientConfig.namespace,
            serializer = payloadSerializer(),
            codec = payloadCodecOrNull() ?: NoOpCodec,
        )
    }

    /**
     * Gets the underlying core client for low-level operations.
     *
     * @throws IllegalStateException if the application hasn't been started
     */
    fun getCoreClient(): TemporalCoreClient = coreClient ?: throw IllegalStateException("Application not started")

    companion object {
        /**
         * Creates a new Temporal application with the given configuration.
         *
         * This is an internal API. Use [embeddedTemporal] instead for creating applications.
         *
         * @param parentCoroutineContext The parent coroutine context for the application.
         *                               Defaults to [Dispatchers.Default].
         * @param configure DSL configuration block.
         */
        @InternalTemporalApi
        operator fun invoke(
            parentCoroutineContext: CoroutineContext = Dispatchers.Default,
            configure: TemporalApplicationBuilder.() -> Unit,
        ): TemporalApplication {
            val builder = TemporalApplicationBuilder(parentCoroutineContext)
            builder.configure()
            return builder.build()
        }
    }

    public open class Configuration {
        public val parallelism: Int = Runtime.getRuntime().availableProcessors()
    }
}

/**
 * Configuration for a Temporal application.
 */
internal data class TemporalApplicationConfig(
    val connection: ConnectionConfig,
    val deployment: WorkerDeploymentOptions? = null,
    val shutdown: ShutdownConfig = ShutdownConfig(),
)

/**
 * Configuration for application shutdown behavior.
 * Follows Ktor's two-phase shutdown pattern.
 */
data class ShutdownConfig(
    /**
     * Grace period to wait for workers to complete gracefully.
     * After this timeout, workers will be force-cancelled.
     */
    val gracePeriodMs: Long = 10_000L,
    /**
     * Additional timeout after force cancellation to wait for cleanup.
     */
    val forceTimeoutMs: Long = 5_000L,
)

/**
 * Connection settings for the Temporal service.
 */
data class ConnectionConfig(
    /** Target address (e.g., "http://localhost:7233" or "https://my-namespace.tmprl.cloud:7233"). */
    val target: String = "http://localhost:7233",
    /** Namespace to use. */
    val namespace: String = "default",
    /**
     * TLS configuration for secure connections.
     *
     * When null, TLS is automatically enabled for `https://` URLs using system CA certificates.
     * For custom CA certificates, client certificates (mTLS), or domain overrides, provide a [TlsConfig].
     *
     * When [apiKey] is provided and [tls] is null, TLS is automatically enabled.
     */
    val tls: TlsConfig? = null,
    /**
     * API key for Temporal Cloud authentication.
     *
     * This is an alternative to mTLS authentication. The API key is sent as a Bearer token
     * in the Authorization header. When set, TLS is automatically enabled if not explicitly configured.
     *
     * Obtain API keys from the Temporal Cloud UI via Service Accounts.
     */
    val apiKey: String? = null,
)

/**
 * Configuration for a task queue.
 */
internal data class TaskQueueConfig(
    val name: String,
    /** Namespace override for this task queue. If null, uses the application default. */
    val namespace: String? = null,
    val workflows: List<WorkflowRegistration>,
    val activities: List<ActivityRegistration>,
    /** Maximum number of concurrent workflow executions. */
    val maxConcurrentWorkflows: Int = 200,
    /** Maximum number of concurrent activity executions. */
    val maxConcurrentActivities: Int = 200,
    /** Attributes for task-queue-scoped plugin storage. */
    val attributes: Attributes = Attributes(concurrent = false),
    /** Hook registry for task-queue-scoped hooks. */
    val hookRegistry: HookRegistry = HookRegistryImpl(),
    /**
     * Grace period for shutdown to wait for polling jobs to complete gracefully.
     * After this timeout, polling jobs will be force-cancelled.
     */
    val shutdownGracePeriodMs: Long = 10_000L,
    /**
     * Maximum interval for throttling activity heartbeats.
     * Heartbeats will be throttled to at most this interval.
     */
    val maxHeartbeatThrottleIntervalMs: Long = 60_000L,
    /**
     * Default interval for throttling activity heartbeats when no heartbeat timeout is set.
     * When a heartbeat timeout is configured, throttling uses 80% of that timeout instead.
     */
    val defaultHeartbeatThrottleIntervalMs: Long = 30_000L,
    /**
     * Timeout in milliseconds for detecting workflow deadlocks.
     * If a workflow activation doesn't complete within this time, a WorkflowDeadlockException is thrown.
     * Set to 0 to disable deadlock detection.
     *
     * Default: 2000ms (2 seconds)
     */
    val workflowDeadlockTimeoutMs: Long = 2000L,
    /**
     * Configuration for zombie thread eviction.
     */
    val zombieEviction: ZombieEvictionConfig = ZombieEvictionConfig(),
    /**
     * Timeout for force exit when shutdown is stuck due to stuck threads.
     * If application.close() doesn't complete within this time, System.exit(1) is called.
     *
     * Default: 60 seconds
     */
    val forceExitTimeout: Duration = 1.minutes,
)

/**
 * Registration info for a workflow.
 *
 * @property workflowType The workflow type name
 * @property implementation The workflow implementation instance (used to scan for methods)
 * @property instanceFactory Optional factory to create workflow instances. If null, a factory
 *   will be created that calls the no-arg constructor. For tests that need to inspect workflow
 *   state, this can be set to `{ implementation }` to reuse the same instance.
 */
@PublishedApi
internal data class WorkflowRegistration(
    val workflowType: String,
    val implementation: Any,
    val instanceFactory: (() -> Any)? = null,
)

/**
 * Registration info for an activity.
 */
@InternalTemporalApi
data class ActivityRegistration(
    val activityType: String,
    val implementation: Any,
)

/**
 * Registers a task queue with the application.
 *
 * This extension function allows configuring task queues on a [TemporalApplication]
 * instance before calling [TemporalApplication.start].
 *
 * Usage:
 * ```kotlin
 * val app = TemporalApplication { connection { ... } }
 * app.taskQueue("my-queue") {
 *     workflow(MyWorkflowImpl())
 *     activity(MyActivityImpl())
 * }
 * app.start()
 * ```
 */
fun TemporalApplication.taskQueue(
    name: String,
    block: TaskQueueBuilder.() -> Unit = {},
) {
    val builder = TaskQueueBuilder(name, parentApplication = this)
    builder.block()
    taskQueues.add(builder.build())
}
