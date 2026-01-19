package com.surrealdev.temporal.application.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addFileSource
import com.sksamuel.hoplite.addResourceSource
import java.io.File

/**
 * Loads Temporal configuration from YAML files using Hoplite.
 *
 * Supports loading from:
 * - Classpath resources (e.g., "/application.yaml")
 * - File system paths
 * - Command-line arguments specifying config paths
 *
 * Example usage:
 * ```kotlin
 * // Load from default locations
 * val config = TemporalConfigLoader.load()
 *
 * // Load from specific resource
 * val config = TemporalConfigLoader.loadFromResource("/my-config.yaml")
 *
 * // Load from file path
 * val config = TemporalConfigLoader.loadFromFile("/etc/temporal/config.yaml")
 * ```
 */
object TemporalConfigLoader {
    private val DEFAULT_RESOURCE_PATHS =
        listOf(
            "/application.yaml",
            "/application.yml",
            "/temporal.yaml",
            "/temporal.yml",
        )

    /**
     * Loads configuration with the following precedence:
     * 1. Config file specified via `-config=<path>` argument
     * 2. Default resource paths (application.yaml, temporal.yaml)
     * 3. Empty default configuration
     *
     * @param args Command-line arguments (optional)
     * @return The loaded [TemporalConfig]
     */
    fun load(args: Array<String> = emptyArray()): TemporalConfig {
        // Check for -config=<path> argument
        val configArg = args.find { it.startsWith("-config=") }
        if (configArg != null) {
            val path = configArg.substringAfter("-config=")
            return if (path.startsWith("/") || path.contains(":")) {
                loadFromFile(path)
            } else {
                loadFromResource("/$path")
            }
        }

        // Try default resource paths
        for (resourcePath in DEFAULT_RESOURCE_PATHS) {
            val resource = TemporalConfigLoader::class.java.getResource(resourcePath)
            if (resource != null) {
                return loadFromResource(resourcePath)
            }
        }

        // Return default config if no files found
        return TemporalConfig()
    }

    /**
     * Loads configuration from a classpath resource.
     *
     * @param resourcePath Path to the resource (e.g., "/application.yaml")
     * @return The loaded [TemporalConfig]
     * @throws ConfigLoadException if the resource cannot be loaded or parsed
     */
    fun loadFromResource(resourcePath: String): TemporalConfig =
        try {
            ConfigLoaderBuilder
                .default()
                .addResourceSource(resourcePath)
                .build()
                .loadConfigOrThrow<TemporalConfig>()
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to load config from resource: $resourcePath", e)
        }

    /**
     * Loads configuration from a file system path.
     *
     * @param filePath Path to the configuration file
     * @return The loaded [TemporalConfig]
     * @throws ConfigLoadException if the file cannot be loaded or parsed
     */
    fun loadFromFile(filePath: String): TemporalConfig {
        val file = File(filePath)
        if (!file.exists()) {
            throw ConfigLoadException("Config file not found: $filePath")
        }
        return try {
            ConfigLoaderBuilder
                .default()
                .addFileSource(file)
                .build()
                .loadConfigOrThrow<TemporalConfig>()
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to load config from file: $filePath", e)
        }
    }
}

/**
 * Exception thrown when configuration loading fails.
 */
class ConfigLoadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
