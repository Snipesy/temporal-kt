package com.surrealdev.temporal.compiler.test.services

import com.surrealdev.temporal.compiler.TemporalPluginConfigurationKeys
import com.surrealdev.temporal.compiler.fir.TemporalFirExtensionRegistrar
import com.surrealdev.temporal.compiler.ir.TemporalIrBodyFiller
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

class TemporalExtensionRegistrarConfigurator(
    testServices: TestServices,
) : EnvironmentConfigurator(testServices) {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        // testData for the taskqueue diagnostic relies on this set; other testData files do not
        // call `withTaskQueue` so they are unaffected.
        configuration.put(
            TemporalPluginConfigurationKeys.KNOWN_TASK_QUEUES,
            listOf("known-queue"),
        )
        FirExtensionRegistrarAdapter.registerExtension(TemporalFirExtensionRegistrar(configuration))
        IrGenerationExtension.registerExtension(TemporalIrBodyFiller())
    }
}
