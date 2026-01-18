package com.surrealdev.temporal.application

import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.client.TemporalClientConfig

/**
 * A Temporal application that manages workers and client connections.
 *
 * Usage:
 * ```kotlin
 * val app = TemporalApplication {
 *     connection {
 *         target = "localhost:7233"
 *         namespace = "default"
 *     }
 *
 *     install(KotlinxSerialization) {
 *         json = Json { prettyPrint = true }
 *     }
 *
 *     taskQueue("my-task-queue") {
 *         workflow(MyWorkflowImpl())
 *         activity(MyActivityImpl())
 *     }
 * }
 *
 * app.start()
 * ```
 */
class TemporalApplication internal constructor(
    private val config: TemporalApplicationConfig,
) {
    private val plugins = mutableListOf<TemporalPlugin>()

    /**
     * Starts all configured workers.
     *
     * This is a suspending function that will run until [stop] is called.
     */
    suspend fun start() {
        // TODO: Initialize core-bridge client
        // TODO: Start workers for each task queue
    }

    /**
     * Stops all workers gracefully.
     */
    suspend fun stop() {
        // TODO: Graceful shutdown
    }

    /**
     * Creates a client for interacting with the Temporal service.
     */
    suspend fun client(configure: TemporalClientConfig.() -> Unit = {}): TemporalClient {
        val clientConfig =
            TemporalClientConfig().apply {
                // Inherit connection settings from application
                target = config.connection.target
                namespace = config.connection.namespace
                configure()
            }
        return TemporalClient(clientConfig)
    }

    companion object {
        /**
         * Creates a new Temporal application with the given configuration.
         */
        operator fun invoke(configure: TemporalApplicationBuilder.() -> Unit): TemporalApplication {
            val builder = TemporalApplicationBuilder()
            builder.configure()
            return builder.build()
        }
    }
}

/**
 * Configuration for a Temporal application.
 */
internal data class TemporalApplicationConfig(
    val connection: ConnectionConfig,
    val taskQueues: List<TaskQueueConfig>,
    val plugins: List<TemporalPlugin>,
)

/**
 * Connection settings for the Temporal service.
 */
data class ConnectionConfig(
    /** Target address (e.g., "localhost:7233" or "my-namespace.tmprl.cloud:7233"). */
    var target: String = "localhost:7233",
    /** Namespace to use. */
    var namespace: String = "default",
    /** Whether to use TLS. */
    var useTls: Boolean = false,
    /** Path to TLS client certificate (for mTLS). */
    var tlsCertPath: String? = null,
    /** Path to TLS client key (for mTLS). */
    var tlsKeyPath: String? = null,
)

/**
 * Configuration for a task queue.
 */
internal data class TaskQueueConfig(
    val name: String,
    val workflows: List<WorkflowRegistration>,
    val activities: List<ActivityRegistration>,
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
