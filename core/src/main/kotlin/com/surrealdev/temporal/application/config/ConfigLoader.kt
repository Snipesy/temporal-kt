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
    @PublishedApi
    internal val DEFAULT_RESOURCE_PATHS =
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
    fun loadFromFile(filePath: String): TemporalConfig = loadFromFileAs(filePath)

    /**
     * Loads configuration as a custom type from a file system path.
     *
     * This allows loading configuration into a custom class that includes
     * additional fields beyond [TemporalConfig]. Your custom config class
     * should include a `temporal` property of type [TemporalRootConfig].
     *
     * Example:
     * ```kotlin
     * data class MyAppConfig(
     *     val temporal: TemporalRootConfig = TemporalRootConfig(),
     *     val myCustomSettings: MyCustomSettings = MyCustomSettings(),
     * )
     *
     * val config = TemporalConfigLoader.loadFromFileAs<MyAppConfig>("/path/to/config.yaml")
     * ```
     *
     * @param T The configuration type to load
     * @param filePath Path to the configuration file
     * @return The loaded configuration
     * @throws ConfigLoadException if the file cannot be loaded or parsed
     */
    inline fun <reified T : Any> loadFromFileAs(filePath: String): T {
        val file = File(filePath)
        if (!file.exists()) {
            throw ConfigLoadException("Config file not found: $filePath")
        }
        return try {
            ConfigLoaderBuilder
                .default()
                .addFileSource(file)
                .build()
                .loadConfigOrThrow<T>()
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to load config from file: $filePath", e)
        }
    }

    /**
     * Loads configuration as a custom type with the following precedence:
     * 1. Config file specified via `-config=<path>` argument
     * 2. Default resource paths (application.yaml, temporal.yaml)
     * 3. Throws an exception (no default for custom types)
     *
     * This allows loading configuration into a custom class that includes
     * additional fields beyond [TemporalConfig]. Your custom config class
     * should include a `temporal` property of type [TemporalRootConfig].
     *
     * Example:
     * ```kotlin
     * data class MyAppConfig(
     *     val temporal: TemporalRootConfig = TemporalRootConfig(),
     *     val database: DatabaseConfig = DatabaseConfig(),
     * )
     *
     * val config = TemporalConfigLoader.loadAs<MyAppConfig>()
     * ```
     *
     * @param T The configuration type to load
     * @param args Command-line arguments (optional)
     * @return The loaded configuration
     * @throws ConfigLoadException if no config file is found or loading fails
     */
    inline fun <reified T : Any> loadAs(args: Array<String> = emptyArray()): T {
        // Check for -config=<path> argument
        val configArg = args.find { it.startsWith("-config=") }
        if (configArg != null) {
            val path = configArg.substringAfter("-config=")
            return if (path.startsWith("/") || path.contains(":")) {
                loadFromFileAs(path)
            } else {
                loadFromResourceAs("/$path")
            }
        }

        // Try default resource paths
        for (resourcePath in DEFAULT_RESOURCE_PATHS) {
            val resource = TemporalConfigLoader::class.java.getResource(resourcePath)
            if (resource != null) {
                return loadFromResourceAs(resourcePath)
            }
        }

        throw ConfigLoadException(
            "No configuration file found. Searched: ${DEFAULT_RESOURCE_PATHS.joinToString()}",
        )
    }

    /**
     * Loads configuration as a custom type from a classpath resource.
     *
     * @param T The configuration type to load
     * @param resourcePath Path to the resource (e.g., "/application.yaml")
     * @return The loaded configuration
     * @throws ConfigLoadException if the resource cannot be loaded or parsed
     */
    inline fun <reified T : Any> loadFromResourceAs(resourcePath: String): T =
        try {
            ConfigLoaderBuilder
                .default()
                .addResourceSource(resourcePath)
                .build()
                .loadConfigOrThrow<T>()
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to load config from resource: $resourcePath", e)
        }
}

/**
 * Exception thrown when configuration loading fails.
 */
class ConfigLoadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
