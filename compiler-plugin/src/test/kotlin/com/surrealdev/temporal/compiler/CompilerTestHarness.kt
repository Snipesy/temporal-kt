package com.surrealdev.temporal.compiler

import com.surrealdev.temporal.compiler.ir.TemporalIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.config.moduleName
import java.io.File

/**
 * Test harness for embedded Kotlin compilation with IR generation extensions.
 *
 * This utility provides a reusable way to compile Kotlin source files with
 * custom compiler plugins in tests, capturing compilation results and errors.
 *
 * Usage:
 * ```kotlin
 * val harness = CompilerTestHarness()
 * val result = harness.compile(sourceFile) { project ->
 *     IrGenerationExtension.registerExtension(project, MyExtension())
 * }
 * assertTrue(result.success)
 * ```
 */
@OptIn(ExperimentalCompilerApi::class)
class CompilerTestHarness(
    private val jvmTarget: JvmTarget = JvmTarget.JVM_17,
    private val moduleName: String = "test-module",
) {
    /**
     * Result of a compilation attempt.
     */
    data class CompilationResult(
        /** Whether compilation succeeded without errors */
        val success: Boolean,
        /** All error messages collected during compilation */
        val errors: List<String>,
        /** All warning messages collected during compilation */
        val warnings: List<String>,
    ) {
        /** All errors joined as a single string */
        val allErrors: String get() = errors.joinToString("\n")

        /** All warnings joined as a single string */
        val allWarnings: String get() = warnings.joinToString("\n")
    }

    /**
     * Message collector that captures compilation messages.
     */
    class TestMessageCollector : MessageCollector {
        private val _errors = mutableListOf<String>()
        private val _warnings = mutableListOf<String>()
        private var hasErrors = false

        val errors: List<String> get() = _errors
        val warnings: List<String> get() = _warnings

        /** Add an error message (used internally for exception handling) */
        internal fun addError(message: String) {
            hasErrors = true
            _errors.add(message)
        }

        override fun clear() {
            _errors.clear()
            _warnings.clear()
            hasErrors = false
        }

        override fun hasErrors(): Boolean = hasErrors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            when (severity) {
                CompilerMessageSeverity.ERROR,
                CompilerMessageSeverity.EXCEPTION,
                -> {
                    hasErrors = true
                    _errors.add(message)
                }

                CompilerMessageSeverity.WARNING,
                CompilerMessageSeverity.STRONG_WARNING,
                -> {
                    _warnings.add(message)
                }

                else -> {} // Ignore info/logging
            }
        }
    }

    /**
     * Compiles the given source file with optional IR generation extensions.
     *
     * @param sourceFile The Kotlin source file to compile
     * @param registerExtensions Optional callback to register IR extensions on the project.
     *                           The callback receives the project instance for extension registration.
     * @return CompilationResult containing success status and collected messages
     */
    fun compile(
        sourceFile: File,
        registerExtensions: ((Project) -> Unit)? = null,
    ): CompilationResult = compile(listOf(sourceFile), registerExtensions)

    /**
     * Compiles multiple source files with optional IR generation extensions.
     *
     * @param sourceFiles The Kotlin source files to compile
     * @param registerExtensions Optional callback to register IR extensions on the project
     * @return CompilationResult containing success status and collected messages
     */
    fun compile(
        sourceFiles: List<File>,
        registerExtensions: ((Project) -> Unit)? = null,
    ): CompilationResult = compile(sourceFiles, TestMessageCollector(), registerExtensions)

    /**
     * Compiles multiple source files with a provided message collector and optional IR generation extensions.
     *
     * @param sourceFiles The Kotlin source files to compile
     * @param messageCollector The message collector to use for capturing compilation messages
     * @param registerExtensions Optional callback to register IR extensions on the project
     * @return CompilationResult containing success status and collected messages
     */
    fun compile(
        sourceFiles: List<File>,
        messageCollector: TestMessageCollector,
        registerExtensions: ((Project) -> Unit)? = null,
    ): CompilationResult {
        val configuration =
            CompilerConfiguration().apply {
                this.messageCollector = messageCollector
                this.moduleName = this@CompilerTestHarness.moduleName
                put(JVMConfigurationKeys.JVM_TARGET, jvmTarget)

                // Set JDK home for module-based Java (JDK 9+)
                put(JVMConfigurationKeys.JDK_HOME, File(System.getProperty("java.home")))

                // Add source files to compilation
                sourceFiles.forEach { file ->
                    addKotlinSourceRoot(file.absolutePath)
                }

                // Add classpath dependencies
                addJvmClasspathRoots(buildClasspath())
            }

        val disposable =
            org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
                .newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForProduction(
                    disposable,
                    configuration,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )

            // Register extensions if provided
            registerExtensions?.invoke(environment.project)

            val success =
                try {
                    KotlinToJVMBytecodeCompiler.analyzeAndGenerate(environment) != null
                } catch (e: Exception) {
                    // Plugin may throw errors as exceptions
                    messageCollector.addError(e.message ?: e.toString())
                    false
                }

            return CompilationResult(
                success = success && !messageCollector.hasErrors(),
                errors = messageCollector.errors,
                warnings = messageCollector.warnings,
            )
        } finally {
            org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
                .dispose(disposable)
        }
    }

    /**
     * Compiles a source file with the Temporal IR generation extension.
     *
     * This is a convenience method for testing the Temporal compiler plugin.
     *
     * @param sourceFile The Kotlin source file to compile
     * @param outputDir Optional output directory for generated metadata
     * @return CompilationResult containing success status and collected messages
     */
    fun compileWithTemporalPlugin(
        sourceFile: File,
        outputDir: String? = null,
    ): CompilationResult {
        val messageCollector = TestMessageCollector()
        return compile(listOf(sourceFile), messageCollector) { project ->
            IrGenerationExtension.registerExtension(
                project,
                TemporalIrGenerationExtension(outputDir, messageCollector),
            )
        }
    }

    companion object {
        /**
         * Builds the classpath for compilation from the current runtime classpath.
         *
         * This includes all JARs and directories from the test runtime,
         * which provides access to Kotlin stdlib, coroutines, and project dependencies.
         */
        fun buildClasspath(): List<File> {
            val classpath = mutableListOf<File>()

            // Add all JARs/dirs from the current classpath
            System.getProperty("java.class.path").split(File.pathSeparator).forEach { path ->
                val file = File(path)
                if (file.exists() && (file.isDirectory || file.extension == "jar")) {
                    classpath.add(file)
                }
            }

            return classpath.distinct()
        }
    }
}
