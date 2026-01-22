package com.surrealdev.temporal.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.Services
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/**
 * Test harness for Kotlin compilation with compiler plugins.
 *
 * Uses K2JVMCompiler directly for proper K2/FIR and IR extension support.
 * Plugins are discovered via service locator (META-INF/services) using TestPluginRegistrar.
 *
 * Usage:
 * ```kotlin
 * val harness = CompilerTestHarness()
 * val result = harness.compile(sourceFile, listOf(MyPluginRegistrar()))
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
     * Compiles a single source file with the given plugin registrars.
     *
     * @param sourceFile The Kotlin source file to compile
     * @param pluginRegistrars Plugin registrars to use for compilation
     * @return CompilationResult containing success status and collected messages
     */
    fun compile(
        sourceFile: File,
        pluginRegistrars: List<CompilerPluginRegistrar>,
    ): CompilationResult = compile(listOf(sourceFile), pluginRegistrars)

    /**
     * Compiles multiple source files with the given plugin registrars.
     *
     * Uses K2JVMCompiler directly for proper FIR and IR extension support.
     * Plugins are passed via thread-local to TestPluginRegistrar which is
     * discovered by the compiler via service locator mechanism.
     *
     * @param sourceFiles The Kotlin source files to compile
     * @param pluginRegistrars Plugin registrars to use for compilation
     * @return CompilationResult containing success status and collected messages
     */
    fun compile(
        sourceFiles: List<File>,
        pluginRegistrars: List<CompilerPluginRegistrar>,
    ): CompilationResult {
        val messageCollector = TestMessageCollector()
        val outputDir = Files.createTempDirectory("k2-compile-output").toFile()

        try {
            // Set up thread-local parameters for TestPluginRegistrar
            TestPluginRegistrar.threadLocalParameters.set(
                TestPluginRegistrar.ThreadLocalParameters(pluginRegistrars),
            )

            // Build compiler arguments
            val args =
                K2JVMCompilerArguments().apply {
                    freeArgs = sourceFiles.map { it.absolutePath }
                    destination = outputDir.absolutePath
                    classpath = buildClasspath().joinToString(File.pathSeparator) { it.absolutePath }
                    jdkHome = System.getProperty("java.home")
                    jvmTarget = this@CompilerTestHarness.jvmTarget.description
                    noStdlib = true
                    noReflect = true
                    moduleName = this@CompilerTestHarness.moduleName
                    // Add test resources to plugin classpath for TestPluginRegistrar discovery
                    pluginClasspaths = findTestPluginClasspath()
                }

            // Capture compiler output
            val outputStream = ByteArrayOutputStream()
            val printStream = PrintStream(outputStream)

            val printingCollector =
                PrintingMessageCollector(
                    printStream,
                    MessageRenderer.PLAIN_RELATIVE_PATHS,
                    false,
                )

            // Create a delegating collector that captures messages
            val delegatingCollector =
                object : MessageCollector {
                    override fun clear() {
                        messageCollector.clear()
                        printingCollector.clear()
                    }

                    override fun hasErrors(): Boolean = messageCollector.hasErrors() || printingCollector.hasErrors()

                    override fun report(
                        severity: CompilerMessageSeverity,
                        message: String,
                        location: CompilerMessageSourceLocation?,
                    ) {
                        messageCollector.report(severity, message, location)
                        printingCollector.report(severity, message, location)
                    }
                }

            // Run compiler
            val exitCode = K2JVMCompiler().exec(delegatingCollector, Services.EMPTY, args)

            return CompilationResult(
                success = exitCode == ExitCode.OK && !messageCollector.hasErrors(),
                errors = messageCollector.errors,
                warnings = messageCollector.warnings,
            )
        } finally {
            TestPluginRegistrar.threadLocalParameters.remove()
            outputDir.deleteRecursively()
        }
    }

    /**
     * Finds the classpath entry containing our test resources (META-INF/services).
     */
    private fun findTestPluginClasspath(): Array<String> {
        val resourceName = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        val classpathEntries = mutableListOf<String>()

        // Look for the test resources directory or JAR containing our TestPluginRegistrar
        val urls = this::class.java.classLoader.getResources(resourceName)
        for (url in urls.asSequence()) {
            val path =
                when (url.protocol) {
                    "file" -> {
                        // Remove the resource path suffix to get the root
                        val fullPath = url.path
                        fullPath.removeSuffix("/$resourceName")
                    }
                    "jar" -> {
                        // Extract JAR path from jar:file:/path/to/file.jar!/META-INF/...
                        val jarPath = url.path.substringBefore("!")
                        if (jarPath.startsWith("file:")) jarPath.removePrefix("file:") else jarPath
                    }
                    else -> null
                }
            if (path != null && File(path).exists()) {
                // Check if this contains our TestPluginRegistrar
                val content = url.readText()
                if (content.contains("TestPluginRegistrar")) {
                    classpathEntries.add(path)
                }
            }
        }

        return classpathEntries.toTypedArray()
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
