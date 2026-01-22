@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.surrealdev.temporal.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Test-only plugin registrar that delegates to dynamically provided registrars.
 *
 * This is discovered via service locator (META-INF/services) and then delegates
 * to the actual plugin registrars passed via thread-local.
 */
class TestPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId = "temporal-test"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val parameters = threadLocalParameters.get() ?: return

        parameters.registrars.forEach { registrar ->
            with(registrar) {
                registerExtensions(configuration)
            }
        }
    }

    companion object {
        val threadLocalParameters: ThreadLocal<ThreadLocalParameters> = ThreadLocal()
    }

    data class ThreadLocalParameters(
        val registrars: List<CompilerPluginRegistrar>,
    )
}
