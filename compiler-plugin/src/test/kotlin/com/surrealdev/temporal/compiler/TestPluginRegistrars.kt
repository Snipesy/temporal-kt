@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.surrealdev.temporal.compiler

import com.surrealdev.temporal.compiler.ir.TemporalIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * IR-only plugin registrar for testing.
 */
class IrOnlyPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true
    override val pluginId: String = "temporal-ir-only-test"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector =
            configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
                ?: MessageCollector.NONE

        IrGenerationExtension.registerExtension(
            TemporalIrGenerationExtension(null, messageCollector),
        )
    }
}
