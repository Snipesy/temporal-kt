package com.surrealdev.temporal.compiler

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Registers the Temporal compiler plugin extensions.
 *
 * This plugin analyzes DSL blocks like `workflow<T>("name") { ... }` and `activity<T>("name") { ... }`
 * to extract workflow/activity metadata for client stub generation.
 */
@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
class TemporalCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override val pluginId: String = TemporalCommandLineProcessor.PLUGIN_ID

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val outputDir = configuration.get(TemporalPluginConfigurationKeys.OUTPUT_DIR)
        val enabled = configuration.get(TemporalPluginConfigurationKeys.ENABLED, true)
        val messageCollector =
            configuration.get(org.jetbrains.kotlin.config.CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
                ?: org.jetbrains.kotlin.cli.common.messages.MessageCollector.NONE

        if (!enabled) return

        IrGenerationExtension.registerExtension(
            TemporalIrGenerationExtension(outputDir, messageCollector),
        )
    }
}
