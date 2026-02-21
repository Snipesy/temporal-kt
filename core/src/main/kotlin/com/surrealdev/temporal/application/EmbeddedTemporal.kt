package com.surrealdev.temporal.application

import com.surrealdev.temporal.application.config.TemporalConfig
import com.surrealdev.temporal.application.config.TemporalConfigLoader
import com.surrealdev.temporal.application.module.ModuleLoader
import com.surrealdev.temporal.application.module.TemporalModule
import com.surrealdev.temporal.core.VersioningBehavior
import com.surrealdev.temporal.core.WorkerDeploymentVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/**
 * An embedded Temporal worker container similar to Ktor's embedded server.
 *
 * This class wraps a [TemporalApplication] and provides lifecycle management methods.
 *
 * Example usage:
 * ```kotlin
 * // Config-driven (loads modules from application.yaml)
 * fun main() {
 *     embeddedTemporal(configPath = "/application.yaml").start(wait = true)
 * }
 *
 * // Programmatic with module
 * fun main() {
 *     embeddedTemporal {
 *         taskQueue("my-queue") {
 *             workflow<MyWorkflow>()
 *         }
 *     }.start(wait = true)
 * }
 * ```
 */
class EmbeddedTemporal internal constructor(
    /**
     * The underlying [TemporalApplication] instance.
     */
    val application: TemporalApplication,
) {
    /**
     * Starts the Temporal application.
     *
     * This connects to the Temporal server and starts all registered workers.
     *
     * @param wait If true, blocks the current thread until the application is stopped.
     *             If false, returns immediately allowing background execution.
     * @return This [EmbeddedTemporal] instance for chaining.
     */
    fun start(wait: Boolean = false): EmbeddedTemporal {
        runBlocking {
            application.start()
        }
        if (wait) {
            runBlocking {
                application.awaitTermination()
            }
        }
        return this
    }

    /**
     * Stops the Temporal application gracefully.
     */
    suspend fun stop() {
        application.close()
    }
}

/**
 * Creates an embedded Temporal application with configuration and module loading support.
 *
 * This function mimics Ktor's `embeddedServer()` pattern, providing a convenient
 * entry point for Temporal applications.
 *
 * @param configPath Optional path to a YAML configuration file (resource path starting with "/")
 * @param parentCoroutineContext The parent coroutine context for the application
 * @param configure Additional DSL configuration to apply
 * @param module A module to install (extension function on TemporalApplication)
 * @return A configured [EmbeddedTemporal] instance ready to start
 *
 * Example:
 * ```kotlin
 * // With inline module
 * embeddedTemporal {
 *     taskQueue("orders") {
 *         workflow<OrderWorkflow>()
 *     }
 * }.start(wait = true)
 *
 * // With config file and additional module
 * embeddedTemporal(configPath = "/application.yaml") {
 *     // Additional configuration
 * }.start(wait = true)
 *
 * // With separate module function
 * embeddedTemporal(module = Application::ordersModule).start(wait = true)
 * ```
 */
fun embeddedTemporal(
    configPath: String? = null,
    parentCoroutineContext: CoroutineContext = Dispatchers.Default,
    configure: TemporalApplicationBuilder.() -> Unit = {},
    module: TemporalModule = {},
): EmbeddedTemporal {
    val config = loadConfig(configPath)
    val application = buildApplication(config, parentCoroutineContext, configure)
    // Apply the module on the built application
    application.module()
    return EmbeddedTemporal(application)
}

/**
 * Main entry point for config-driven Temporal applications.
 * Similar to Ktor's EngineMain, this loads configuration and modules automatically.
 *
 * Usage:
 * ```kotlin
 * fun main(args: Array<String>) {
 *     TemporalMain.main(args)
 * }
 * ```
 *
 * Or in application.yaml, specify modules:
 * ```yaml
 * temporal:
 *   modules:
 *     - com.example.ModuleKt.myModule
 * ```
 */
object TemporalMain {
    /**
     * Main function for starting a Temporal application from configuration.
     *
     * @param args Command-line arguments. Supports `-config=<path>` to specify config file.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val server = createServer(args)
        server.start(wait = true)
    }

    /**
     * Creates an [EmbeddedTemporal] instance without starting it.
     *
     * @param args Command-line arguments for configuring the server.
     * @return An [EmbeddedTemporal] instance ready to start.
     */
    fun createServer(args: Array<String> = emptyArray()): EmbeddedTemporal {
        val config = TemporalConfigLoader.load(args)
        val application = buildApplication(config, Dispatchers.Default)
        return EmbeddedTemporal(application)
    }
}

/**
 * Convenience function for config-driven main.
 * @see TemporalMain.main
 */
fun temporalMain(args: Array<String> = emptyArray()) {
    TemporalMain.main(args)
}

// ---- Internal helpers ----

private fun loadConfig(configPath: String?): TemporalConfig =
    if (configPath != null) {
        TemporalConfigLoader.loadFromResource(configPath)
    } else {
        TemporalConfigLoader.load()
    }

private fun buildApplication(
    config: TemporalConfig,
    parentCoroutineContext: CoroutineContext,
    configure: TemporalApplicationBuilder.() -> Unit = {},
): TemporalApplication {
    val builder = TemporalApplicationBuilder(parentCoroutineContext)

    // Apply connection settings from config (converts YAML paths to loaded certs)
    builder.connection(config.temporal.connection.toConnectionConfig())

    // Apply deployment settings from config
    config.temporal.deployment?.let { deploymentConfig ->
        if (deploymentConfig.deploymentName.isNotBlank() && deploymentConfig.buildId.isNotBlank()) {
            // Parse versioning behavior from YAML string
            val versioningBehavior =
                try {
                    VersioningBehavior.valueOf(deploymentConfig.defaultVersioningBehavior.uppercase())
                } catch (_: IllegalArgumentException) {
                    VersioningBehavior.UNSPECIFIED
                }

            builder.deployment(
                WorkerDeploymentVersion(deploymentConfig.deploymentName, deploymentConfig.buildId),
                deploymentConfig.useWorkerVersioning,
                versioningBehavior,
            )
        }
    }

    // Apply additional DSL configuration (can override YAML)
    builder.configure()

    val application = builder.build()

    // Load and apply modules declared in config
    val configModules = ModuleLoader.loadModules(config.temporal.modules)
    configModules.forEach { module ->
        application.module()
    }

    return application
}
