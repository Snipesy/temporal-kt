package com.surrealdev.temporal.application

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.EncodedPayloads
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
import com.surrealdev.temporal.core.CorePollerBehavior
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.core.TemporalWorker
import com.surrealdev.temporal.core.TlsConfig
import com.surrealdev.temporal.core.WorkerConfig
import com.surrealdev.temporal.core.WorkerDeploymentOptions
import com.surrealdev.temporal.internal.ZombieEvictionConfig
import com.surrealdev.temporal.serialization.NoOpCodec
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.payloadCodecOrNull
import com.surrealdev.temporal.serialization.payloadSerializationOrNull
import com.surrealdev.temporal.serialization.payloadSerializer
import com.surrealdev.temporal.util.AttributeKey
import com.surrealdev.temporal.util.Attributes
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess

/**
 * Handler for dynamic activities - called for activity types not registered statically.
 *
 * The handler receives the activity type name and encoded payloads, allowing
 * runtime dispatch to arbitrary implementations. Returns a [Payload] directly
 * since type information is not available at compile time.
 *
 * ```kotlin
 * dynamicActivity { activityType, payloads ->
 *     when (activityType) {
 *         "httpGet" -> {
 *             val result = httpClient.get(payloads.decode<String>(0))
 *             serializer.serialize<String>(result)
 *         }
 *         else -> throw IllegalArgumentException("Unknown: $activityType")
 *     }
 * }
 * ```
 *
 * Within the handler, `this` is [ActivityContext], providing access to heartbeat,
 * cancellation checking, activity info, and [ActivityContext.serializer] for
 * serializing the result.
 */
typealias DynamicActivityHandler = suspend ActivityContext.(
    activityType: String,
    payloads: EncodedPayloads,
) -> com.surrealdev.temporal.common.TemporalPayload?

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
 *     workflow<MyWorkflowImpl>()
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
     * Unified hook registry for application-level lifecycle hooks and interceptors.
     *
     * Plugins register both hooks and interceptors via this registry. Interceptors are
     * merged with task-queue-level interceptors at worker startup.
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

    private val closeStarted = AtomicBoolean(false)
    private val fatalShutdownTriggered = AtomicBoolean(false)

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

        // Create the runtime, with Core metrics bridge if OTel plugin provided a Meter
        val coreMetricsMeter = attributes.getOrNull(CoreMetricsMeterKey)
        val rt = TemporalRuntime.create(coreMetricsMeter)
        runtime = rt

        val client =
            TemporalCoreClient.connect(
                runtime = rt,
                targetUrl = config.connection.target,
                namespace = config.connection.namespace,
                tls = config.connection.tls,
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

            // Resolve identity: user-provided > default (pid@hostname)
            val effectiveIdentity =
                taskQueueConfig.workerIdentity
                    ?: run {
                        val pid = ProcessHandle.current().pid()
                        val hostname =
                            java.net.InetAddress
                                .getLocalHost()
                                .hostName
                        "$pid@$hostname"
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
                            deploymentOptions = config.deployment,
                            maxCachedWorkflows = taskQueueConfig.maxCachedWorkflows,
                            maxConcurrentWorkflowTasks = taskQueueConfig.maxConcurrentWorkflows,
                            maxConcurrentActivities = taskQueueConfig.maxConcurrentActivities,
                            maxHeartbeatThrottleIntervalMs = taskQueueConfig.maxHeartbeatThrottleIntervalMs,
                            defaultHeartbeatThrottleIntervalMs = taskQueueConfig.defaultHeartbeatThrottleIntervalMs,
                            workflowPollerBehavior = taskQueueConfig.workflowPollerBehavior,
                            activityPollerBehavior = taskQueueConfig.activityPollerBehavior,
                            maxActivitiesPerSecond = taskQueueConfig.maxActivitiesPerSecond,
                            maxTaskQueueActivitiesPerSecond = taskQueueConfig.maxTaskQueueActivitiesPerSecond,
                            workerIdentity = effectiveIdentity,
                            nonstickyToStickyPollRatio = taskQueueConfig.nonstickyToStickyPollRatio,
                            stickyQueueScheduleToStartTimeoutMs = taskQueueConfig.stickyQueueScheduleToStartTimeoutMs,
                            nondeterminismAsWorkflowFail = taskQueueConfig.nondeterminismAsWorkflowFail,
                            nondeterminismAsWorkflowFailForTypes = taskQueueConfig.nondeterminismAsWorkflowFailForTypes,
                        ),
                )

            // Wrap in ManagedWorker
            val managedWorker =
                ManagedWorker(
                    coreWorker = coreWorker,
                    config = taskQueueConfig,
                    parentContext = coroutineContext,
                    serializer = taskQueueConfig.serializer ?: payloadSerializer(),
                    codec = taskQueueConfig.codec ?: payloadCodecOrNull() ?: NoOpCodec,
                    namespace = effectiveNamespace,
                    applicationHooks = hookRegistry,
                    application = this,
                )

            workers[taskQueueConfig.name] = managedWorker
            managedWorker.start()

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
        if (!closeStarted.compareAndSet(false, true)) return

        // Phase 1: Fire shutdown hook
        hookRegistry.call(
            ApplicationShutdown,
            ApplicationShutdownContext(this),
        )

        // Phase 2: Stop workers with grace period
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
     * Returns true if any worker for the given task queue has initiated shutdown.
     *
     * This can happen due to fatal errors (java.lang.Error) in activity or workflow
     * processing, or an explicit stop() call.
     */
    fun isWorkerShuttingDown(taskQueue: String): Boolean =
        closeStarted.get() || workers[taskQueue]?.isShuttingDown == true

    /**
     * Single entry point for all fatal shutdown paths.
     *
     * Executes at most once regardless of concurrent calls. Launches shutdown in a
     * detached [CoroutineScope] to avoid deadlocks when the caller is inside a polling
     * loop that [close] needs to join.
     *
     * @param errorCode Unique error code for log correlation (e.g. "TKT1209")
     * @param reason Human-readable description of the fatal condition
     * @param cause Optional throwable that triggered the fatal shutdown
     */
    internal fun fatalShutdown(
        errorCode: String,
        reason: String,
        cause: Throwable? = null,
    ) {
        if (!fatalShutdownTriggered.compareAndSet(false, true)) return

        logger.error("[{}] FATAL: {}. Initiating application shutdown.", errorCode, reason, cause)

        // Launch in detached scope — callers may be inside polling loops
        // that close() needs to join, so we can't block the caller.
        CoroutineScope(Dispatchers.Default).launch {
            val timeoutMs = config.shutdown.fatalShutdownTimeoutMs
            val closed =
                withTimeoutOrNull(timeoutMs) {
                    close()
                    true
                }
            if (closed == null) {
                logger.error(
                    "[{}] Graceful shutdown timed out after {}ms. Forcing System.exit(1).",
                    errorCode,
                    timeoutMs,
                )
                exitProcess(1)
            }
        }
    }

    /**
     * Creates a client for interacting with the Temporal service.
     *
     * @param configure Optional configuration block for the client.
     * @return A configured [TemporalClient] instance.
     * @throws IllegalStateException if the application hasn't been started.
     */
    fun client(configure: TemporalClientConfig.() -> Unit = {}): TemporalClient {
        val coreClientInstance = coreClient ?: throw IllegalStateException("Application not started")

        val clientConfig =
            TemporalClientConfig().apply {
                // Inherit connection settings from application
                target = this@TemporalApplication.config.connection.target
                namespace = this@TemporalApplication.config.connection.namespace
                tls = this@TemporalApplication.config.connection.tls
                apiKey = this@TemporalApplication.config.connection.apiKey
                configure()
            }

        // Use client config's plugins if explicitly installed, else fall back to app-level
        val serializer =
            clientConfig.payloadSerializationOrNull()?.serializer
                ?: payloadSerializer()
        val codec =
            clientConfig.payloadCodecOrNull()
                ?: payloadCodecOrNull()
                ?: NoOpCodec

        // Merge: app-level hooks/interceptors first, then client config
        val mergedRegistry = hookRegistry.mergeWith(clientConfig.hookRegistry)

        return TemporalClient.create(
            coreClient = coreClientInstance,
            namespace = clientConfig.namespace,
            serializer = serializer,
            codec = codec,
            hookRegistry = mergedRegistry,
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
    /**
     * Timeout for fatal shutdown (e.g. java.lang.Error, zombie threshold exceeded).
     * If [TemporalApplication.close] doesn't complete within this time, `System.exit(1)` is called.
     *
     * Default: 60 seconds
     */
    val fatalShutdownTimeoutMs: Long = 60_000L,
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
    /** Unified hook registry for task-queue-scoped hooks and interceptors. */
    val hookRegistry: HookRegistry = HookRegistryImpl(),
    /**
     * Grace period for shutdown to wait for polling jobs to complete gracefully.
     * After this timeout, polling jobs will be force-canceled.
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
     * Dynamic activity handler as fallback for unregistered activity types.
     * If null, unregistered activity types will result in an error.
     */
    val dynamicActivityHandler: DynamicActivityHandler? = null,
    /**
     * Poller behavior for workflow tasks. Controls how many concurrent gRPC long-polls are issued
     * to the Temporal server for workflow activations.
     */
    val workflowPollerBehavior: CorePollerBehavior = CorePollerBehavior.SimpleMaximum(5),
    /**
     * Poller behavior for activity tasks. Controls how many concurrent gRPC long-polls are issued
     * to the Temporal server for activity tasks.
     */
    val activityPollerBehavior: CorePollerBehavior = CorePollerBehavior.SimpleMaximum(5),
    /**
     * Maximum number of activities per second this worker will execute. Use to protect downstream
     * services from burst load. 0.0 means no limit.
     */
    val maxActivitiesPerSecond: Double = 0.0,
    /**
     * Server-enforced rate limit on activities per second across all workers on this task queue.
     * Takes precedence over per-worker limits when set lower. 0.0 means no limit.
     */
    val maxTaskQueueActivitiesPerSecond: Double = 0.0,
    /**
     * Maximum number of workflow executions to keep in the sticky cache.
     * Larger values improve replay performance at the cost of memory.
     */
    val maxCachedWorkflows: Int = 1000,
    /**
     * Worker identity string sent to the Temporal server, visible in the UI and history.
     * Null means the default is used: "pid@hostname".
     */
    val workerIdentity: String? = null,
    /**
     * Fraction of max workflow pollers dedicated to the nonsticky (global) task queue.
     * Only applies when using [CorePollerBehavior.SimpleMaximum].
     */
    val nonstickyToStickyPollRatio: Float = 0.2f,
    /**
     * How long (ms) a workflow task may sit on a worker's sticky queue before being moved
     * to the global nonsticky queue. Controls failover latency during worker failures.
     */
    val stickyQueueScheduleToStartTimeoutMs: Long = 10_000L,
    /**
     * When true, nondeterminism errors are reported as workflow failures rather than
     * task failures that retry.
     */
    val nondeterminismAsWorkflowFail: Boolean = false,
    /**
     * Workflow type names for which nondeterminism errors are reported as workflow failures.
     */
    val nondeterminismAsWorkflowFailForTypes: List<String> = emptyList(),
    /**
     * Resolved payload serializer for this task queue.
     * Resolved during build from the task queue's plugin pipeline (with parent fallback).
     */
    val serializer: PayloadSerializer? = null,
    /**
     * Resolved payload codec for this task queue.
     * Resolved during build from the task queue's plugin pipeline (with parent fallback).
     */
    val codec: PayloadCodec? = null,
)

/**
 * Registration info for a workflow.
 *
 * @property workflowType The workflow type name
 * @property workflowClass The workflow class to instantiate for each execution
 * @property instanceFactory Optional factory to create workflow instances. If null, a factory
 *   will be created that calls the no-arg constructor. For tests that need to inspect workflow
 *   state, this can provide a custom instance.
 */
@PublishedApi
internal data class WorkflowRegistration(
    val workflowType: String,
    val workflowClass: kotlin.reflect.KClass<*>,
    val instanceFactory: (() -> Any)? = null,
)

/**
 * Registration info for an activity.
 */
@InternalTemporalApi
sealed class ActivityRegistration {
    /**
     * Register all @Activity annotated methods from an instance.
     *
     * @property instance The activity instance containing @Activity annotated methods
     */
    data class InstanceRegistration(
        val instance: Any,
    ) : ActivityRegistration()

    /**
     * Register a specific activity function (bound method reference).
     *
     * The instance is captured in the bound method reference, so we only need the method.
     *
     * @property activityType The activity type name
     * @property method The bound method reference (e.g., `instance::method`)
     */
    data class FunctionRegistration(
        val activityType: String,
        val method: kotlin.reflect.KFunction<*>,
    ) : ActivityRegistration()

    /**
     * Register a dynamic activity handler as a fallback for unregistered activity types.
     *
     * @property handler The handler function to invoke for unregistered activity types
     */
    data class DynamicRegistration(
        val handler: DynamicActivityHandler,
    ) : ActivityRegistration()
}

/**
 * Attribute key for storing the OTel Meter in application attributes.
 *
 * When the OpenTelemetry plugin has `enableCoreMetrics = true`, this key stores
 * the Meter instance that is used to create the Core metrics bridge at startup.
 *
 * Uses `AttributeKey<Any>` to avoid coupling the core module to OTel API types.
 * The value is cast to `io.opentelemetry.api.metrics.Meter` at the bridge creation site.
 */
val CoreMetricsMeterKey = AttributeKey<Any>("CoreMetricsMeter")

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
 *     workflow<MyWorkflowImpl>()
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
