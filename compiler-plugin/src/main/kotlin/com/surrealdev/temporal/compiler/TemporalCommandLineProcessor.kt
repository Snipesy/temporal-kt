package com.surrealdev.temporal.compiler

import com.google.auto.service.AutoService
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
}

/**
 * Processes command line options for the Temporal compiler plugin.
 */
@OptIn(ExperimentalCompilerApi::class)
@AutoService(CommandLineProcessor::class)
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
        )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            OPTION_OUTPUT_DIR -> configuration.put(TemporalPluginConfigurationKeys.OUTPUT_DIR, value)
            OPTION_ENABLED -> configuration.put(TemporalPluginConfigurationKeys.ENABLED, value.toBoolean())
        }
    }

    companion object {
        const val PLUGIN_ID = "com.surrealdev.temporal.compiler"
        const val OPTION_OUTPUT_DIR = "outputDir"
        const val OPTION_ENABLED = "enabled"
    }
}
