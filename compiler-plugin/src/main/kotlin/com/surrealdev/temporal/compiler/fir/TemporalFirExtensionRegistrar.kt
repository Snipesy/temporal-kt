// Two ktlint rules conflict on the unary-plus FIR extension DSL idiom (`+{ lambda }` vs
// `+ { lambda }`): `unary-op-spacing` forbids the space, `curly-spacing` requires it.
// Suppress curly-spacing at file level — the unary-plus form is the canonical Kotlin compiler
// plugin DSL pattern used throughout the official codebase.
@file:Suppress("ktlint:standard:curly-spacing")

package com.surrealdev.temporal.compiler.fir

import com.surrealdev.temporal.compiler.TemporalPluginConfigurationKeys
import com.surrealdev.temporal.compiler.fir.diagnostics.TemporalDiagnostics
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class TemporalFirExtensionRegistrar(
    private val configuration: CompilerConfiguration,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        val knownTaskQueues =
            configuration.get(TemporalPluginConfigurationKeys.KNOWN_TASK_QUEUES, emptyList()).toSet()
        +{ session: FirSession -> TemporalFirAdditionalCheckersExtension(session, knownTaskQueues) }
        +::TemporalFirCompanionGenerator
        registerDiagnosticContainers(TemporalDiagnostics)
    }
}
