package com.surrealdev.temporal.compiler.test.runners

import com.surrealdev.temporal.compiler.test.services.TemporalCompileClasspathProvider
import com.surrealdev.temporal.compiler.test.services.TemporalExtensionRegistrarConfigurator
import com.surrealdev.temporal.compiler.test.services.TemporalRuntimeClasspathProvider
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.DUMP_IR
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.IGNORE_DEXING
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives.WITH_STDLIB
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.runners.codegen.AbstractFirBlackBoxCodegenTestBase
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractTemporalBoxTest : AbstractFirBlackBoxCodegenTestBase(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider =
        EnvironmentBasedStandardLibrariesPathProvider

    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)
        with(builder) {
            defaultDirectives {
                +DUMP_IR
                +WITH_STDLIB
                +FULL_JDK
                +IGNORE_DEXING
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
