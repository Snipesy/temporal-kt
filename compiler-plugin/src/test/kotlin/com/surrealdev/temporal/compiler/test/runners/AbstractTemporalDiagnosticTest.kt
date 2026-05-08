package com.surrealdev.temporal.compiler.test.runners

import com.surrealdev.temporal.compiler.test.services.TemporalCompileClasspathProvider
import com.surrealdev.temporal.compiler.test.services.TemporalExtensionRegistrarConfigurator
import com.surrealdev.temporal.compiler.test.services.TemporalRuntimeClasspathProvider
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.AbstractFirPhasedDiagnosticTest
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractTemporalDiagnosticTest : AbstractFirPhasedDiagnosticTest(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider =
        EnvironmentBasedStandardLibrariesPathProvider

    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)
        with(builder) {
            defaultDirectives {
                +FirDiagnosticsDirectives.ENABLE_PLUGIN_PHASES
                +FirDiagnosticsDirectives.FIR_DUMP
                +FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
                +JvmEnvironmentConfigurationDirectives.FULL_JDK
                +CodegenTestDirectives.IGNORE_DEXING
            }

            useConfigurators(
                ::TemporalCompileClasspathProvider,
                ::TemporalExtensionRegistrarConfigurator,
            )

            useCustomRuntimeClasspathProviders(
                ::TemporalRuntimeClasspathProvider,
            )
        }
    }
}
