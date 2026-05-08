package com.surrealdev.temporal.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * Configuration keys for the Temporal compiler plugin.
 */
object TemporalPluginConfigurationKeys {
    val OUTPUT_DIR: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("output directory for generated stubs")

    val ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("whether the plugin is enabled")

    /**
     * Comma-separated list of valid task queue names. Empty (default) disables the
     * `withTaskQueue("undefined")` check — no false positives for users who haven't enabled
     * task-queue discovery yet.
     */
    val KNOWN_TASK_QUEUES: CompilerConfigurationKey<List<String>> =
        CompilerConfigurationKey.create("known task queue names")
}

/**
 * Processes command line options for the Temporal compiler plugin.
 */
@OptIn(ExperimentalCompilerApi::class)
class TemporalCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> =
        listOf(
            CliOption(
                optionName = OPTION_OUTPUT_DIR,
                valueDescription = "<path>",
                description = "Output directory for generated client stubs",
                required = false,
            ),
            CliOption(
                optionName = OPTION_ENABLED,
                valueDescription = "<true|false>",
                description = "Whether the plugin is enabled",
                required = false,
            ),
            CliOption(
                optionName = OPTION_KNOWN_TASK_QUEUES,
                valueDescription = "<comma-separated names>",
                description = "Known task queue names; enables withTaskQueue diagnostic when non-empty",
                required = false,
            ),
        )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            OPTION_OUTPUT_DIR -> {
                configuration.put(TemporalPluginConfigurationKeys.OUTPUT_DIR, value)
            }

            OPTION_ENABLED -> {
                configuration.put(TemporalPluginConfigurationKeys.ENABLED, value.toBoolean())
            }

            OPTION_KNOWN_TASK_QUEUES -> {
                configuration.put(
                    TemporalPluginConfigurationKeys.KNOWN_TASK_QUEUES,
                    value.split(',').map(String::trim).filter(String::isNotEmpty),
                )
            }
        }
    }

    companion object {
        const val PLUGIN_ID = "com.surrealdev.temporal.compiler"
        const val OPTION_OUTPUT_DIR = "outputDir"
        const val OPTION_ENABLED = "enabled"
        const val OPTION_KNOWN_TASK_QUEUES = "knownTaskQueues"
    }
}
