package com.surrealdev.temporal.compiler.test.services

import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

private const val RUNTIME_CLASSPATH_PROPERTY = "temporal.test.runtime.classpath"

private val runtimeClasspathFiles: List<File> by lazy {
    System
        .getProperty(RUNTIME_CLASSPATH_PROPERTY)
        ?.split(File.pathSeparator)
        ?.map(::File)
        ?: error(
            "System property '$RUNTIME_CLASSPATH_PROPERTY' is not set; ensure compiler-plugin/build.gradle.kts wires the testDataClasspath",
        )
}

class TemporalCompileClasspathProvider(
    testServices: TestServices,
) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(
        configuration: CompilerConfiguration,
        module: TestModule,
    ) {
        runtimeClasspathFiles.forEach { configuration.addJvmClasspathRoot(it) }
    }
}

class TemporalRuntimeClasspathProvider(
    testServices: TestServices,
) : RuntimeClasspathProvider(testServices) {
    override fun runtimeClassPaths(module: TestModule): List<File> = runtimeClasspathFiles
}
