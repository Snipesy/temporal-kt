package com.surrealdev.temporal.gradle

import com.surrealdev.temporal.compiler.TemporalCommandLineProcessor
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Main Gradle plugin for Temporal Kotlin SDK.
 *
 * This plugin:
 * 1. Registers the Temporal extension for configuration
 * 2. Provides Kotlin compiler plugin support for determinism validation
 * 3. Adds native library dependencies for the Rust Core SDK
 *
 * Based on the official Kotlin compiler plugin template:
 * https://github.com/Kotlin/compiler-plugin-template
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("com.surrealdev.temporal")
 * }
 *
 * temporal {
 *     compiler {
 *         enabled = true  // Enable/disable compiler plugin (default: false)
 *         outputDir = layout.buildDirectory.dir("generated/temporal")
 *     }
 *     native {
 *         enabled = true  // Enable/disable native library dependency (default: true)
 *         classifier = "macos-aarch64"  // Optional: override auto-detected platform
 *     }
 * }
 * ```
 */
class TemporalGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        // Register the temporal extension for user configuration
        val extension =
            target.extensions.create(
                EXTENSION_NAME,
                TemporalExtension::class.java,
                target,
            )

        // Create configurations with lazy default dependencies.
        // defaultDependencies is only evaluated during resolution, avoiding afterEvaluate.
        configureDependencies(target, extension)
    }

    private fun configureDependencies(
        project: Project,
        extension: TemporalExtension,
    ) {
        // Create a configuration for the core library
        val temporalApi =
            project.configurations.create(CONFIGURATION_API) { config ->
                config.isCanBeConsumed = false
                config.isCanBeResolved = false
                config.defaultDependencies { deps ->
                    val coordinates = "${BuildConfig.GROUP_ID}:core:${BuildConfig.VERSION}"
                    deps.add(project.dependencies.create(coordinates))
                }
            }

        // Create a configuration for the native library
        val temporalNative =
            project.configurations.create(CONFIGURATION_NATIVE) { config ->
                config.isCanBeConsumed = false
                config.isCanBeResolved = false
                config.defaultDependencies { deps ->
                    // Read extension values lazily during resolution
                    val nativeEnabled = extension.native.enabled.get()
                    if (nativeEnabled) {
                        val classifier = extension.native.classifier.orNull ?: detectPlatformClassifier()
                        val coordinates =
                            "${BuildConfig.GROUP_ID}:${BuildConfig.CORE_BRIDGE_ARTIFACT_ID}:${BuildConfig.VERSION}:$classifier"
                        deps.add(project.dependencies.create(coordinates))
                    }
                }
            }

        // Wire up configurations when Kotlin plugin is applied
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.configurations.getByName("implementation").extendsFrom(temporalApi)
            project.configurations.getByName("runtimeOnly").extendsFrom(temporalNative)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.configurations.getByName("implementation").extendsFrom(temporalApi)
            project.configurations.getByName("runtimeOnly").extendsFrom(temporalNative)
        }
    }

    /**
     * Determines if this plugin should apply to the given compilation.
     * We apply to all JVM and multiplatform compilations where compiler.enabled=true.
     * Defaults to false to avoid errors across Kotlin version changes.
     */
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.target.project
        val extension = project.extensions.findByType(TemporalExtension::class.java)
        val applicable = extension?.compiler?.enabled?.get() ?: false
        return applicable
    }

    /**
     * Returns the unique identifier for this compiler plugin.
     * Must match the pluginId in TemporalCommandLineProcessor.
     */
    override fun getCompilerPluginId(): String = TemporalCommandLineProcessor.PLUGIN_ID

    /**
     * Specifies the compiler plugin artifact coordinates.
     * This tells Gradle which JAR contains the actual compiler plugin.
     */
    override fun getPluginArtifact(): SubpluginArtifact {
        val artifact =
            SubpluginArtifact(
                groupId = BuildConfig.GROUP_ID,
                artifactId = BuildConfig.COMPILER_PLUGIN_ARTIFACT_ID,
                version = BuildConfig.VERSION,
            )
        return artifact
    }

    /**
     * Provides configuration options to the compiler plugin.
     */
    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val extension = project.extensions.findByType(TemporalExtension::class.java)

        return project.provider {
            buildList {
                // Pass enabled flag from compiler block
                val enabled = extension?.compiler?.enabled?.get() ?: false
                add(SubpluginOption(TemporalCommandLineProcessor.OPTION_ENABLED, enabled.toString()))

                // Pass output directory from compiler block
                extension?.compiler?.outputDir?.orNull?.let { dir ->
                    add(SubpluginOption(TemporalCommandLineProcessor.OPTION_OUTPUT_DIR, dir.asFile.absolutePath))
                }
            }
        }
    }

    companion object {
        const val EXTENSION_NAME = "temporal"
        const val CONFIGURATION_API = "temporalApi"
        const val CONFIGURATION_NATIVE = "temporalNative"

        /**
         * Detects the native library classifier based on current OS and architecture.
         */
        fun detectPlatformClassifier(): String {
            val os = OperatingSystem.current()
            val arch = System.getProperty("os.arch").lowercase()

            return when {
                os.isMacOsX && (arch == "aarch64" || arch == "arm64") -> "macos-aarch64"
                os.isMacOsX -> "macos-x86_64"
                os.isLinux && (arch == "aarch64" || arch == "arm64") -> "linux-aarch64-gnu"
                os.isLinux -> "linux-x86_64-gnu"
                os.isWindows -> "windows-x86_64"
                else -> throw GradleException("Unsupported platform: ${os.name} / $arch")
            }
        }
    }
}
