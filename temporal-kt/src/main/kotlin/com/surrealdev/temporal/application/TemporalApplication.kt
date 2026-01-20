package com.surrealdev.temporal.application

import com.surrealdev.temporal.application.worker.ManagedWorker
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.client.TemporalClientConfig
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.core.TemporalWorker
import com.surrealdev.temporal.serialization.payloadSerializer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlin.coroutines.CoroutineContext

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
) : CoroutineScope {
    // Plugins can be added before start() via extension functions
    internal val plugins = mutableListOf<TemporalPlugin>()

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

        // Connect to the server
        val client =
            TemporalCoreClient.connect(
                runtime = rt,
                targetUrl = config.connection.target,
                namespace = config.connection.namespace,
            )
        coreClient = client

        // Create and start workers for each task queue
        for (taskQueueConfig in taskQueues) {
            val effectiveNamespace = taskQueueConfig.namespace ?: config.connection.namespace

            // Create the core bridge worker
            val coreWorker =
                TemporalWorker.create(
                    runtime = rt,
                    client = client,
                    taskQueue = taskQueueConfig.name,
                    namespace = effectiveNamespace,
                )

            // Wrap in ManagedWorker
            val managedWorker =
                ManagedWorker(
                    coreWorker = coreWorker,
                    config = taskQueueConfig,
                    parentContext = coroutineContext,
                    serializer = payloadSerializer(),
                    namespace = effectiveNamespace,
                )

            workers[taskQueueConfig.name] = managedWorker
            managedWorker.start()
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
     */
    suspend fun close() {
        // Stop all workers first
        for (worker in workers.values) {
            try {
                worker.stop()
            } catch (_: Exception) {
                // Ignore errors during shutdown
            }
        }
        workers.clear()

        // Close the client
        coreClient?.close()
        coreClient = null

        // Close the runtime
        runtime?.close()
        runtime = null

        // Cancel the application job
        applicationJob.cancelAndJoin()
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
         * @param parentCoroutineContext The parent coroutine context for the application.
         *                               Defaults to [Dispatchers.Default].
         * @param configure DSL configuration block.
         */
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
)

/**
 * Connection settings for the Temporal service.
 */
data class ConnectionConfig(
    /** Target address (e.g., "http://localhost:7233" or "https://my-namespace.tmprl.cloud:7233"). */
    val target: String = "http://localhost:7233",
    /** Namespace to use. */
    val namespace: String = "default",
    /** Whether to use TLS. */
    val useTls: Boolean = false,
    /** Path to TLS client certificate (for mTLS). */
    val tlsCertPath: String? = null,
    /** Path to TLS client key (for mTLS). */
    val tlsKeyPath: String? = null,
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
)

/**
 * Registration info for a workflow.
 */
@PublishedApi
internal data class WorkflowRegistration(
    val workflowType: String,
    val implementation: Any,
)

/**
 * Registration info for an activity.
 */
@PublishedApi
internal data class ActivityRegistration(
    val activityType: String,
    val implementation: Any,
)

/**
 * Base interface for Temporal plugins.
 */
interface TemporalPlugin {
    val key: PluginKey<*>
}

/**
 * Key for identifying plugins.
 */
open class PluginKey<T : TemporalPlugin>(
    val name: String,
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
    val builder = TaskQueueBuilder(name)
    builder.block()
    taskQueues.add(builder.build())
}

/**
 * Installs a plugin into the application.
 *
 * This extension function allows installing plugins on a [TemporalApplication]
 * instance before calling [TemporalApplication.start].
 *
 * Usage:
 * ```kotlin
 * val app = TemporalApplication { connection { ... } }
 * app.install(KotlinxSerialization) {
 *     json = Json { prettyPrint = true }
 * }
 * app.start()
 * ```
 */
fun <TConfig : Any, TPlugin : TemporalPlugin> TemporalApplication.install(
    plugin: TemporalPluginFactory<TConfig, TPlugin>,
    configure: TConfig.() -> Unit = {},
) {
    plugins.add(plugin.create(configure))
}
