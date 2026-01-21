package com.surrealdev.temporal.gradle

import com.surrealdev.temporal.compiler.TemporalCommandLineProcessor
import org.gradle.api.Project
import org.gradle.api.provider.Provider
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
 *
 * Based on the official Kotlin compiler plugin template:
 * https://github.com/Kotlin/compiler-plugin-template
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("com.surrealdev.temporal-kt")
 * }
 *
 * temporal {
 *     enabled = true
 *     outputDir = layout.buildDirectory.dir("generated/temporal")
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

        // Configure default output directory
        extension.outputDir.convention(
            target.layout.buildDirectory.dir("generated/temporal"),
        )

    }

    /**
     * Determines if this plugin should apply to the given compilation.
     * We apply to all JVM and multiplatform compilations where enabled=true.
     */
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.target.project
        val extension = project.extensions.findByType(TemporalExtension::class.java)
        val applicable = extension?.enabled?.get() ?: true
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

        // Automatically add temporal-kt library dependency so compiler plugin can access annotations
        val temporalKtCoordinates = "${BuildConfig.GROUP_ID}:temporal-kt:${BuildConfig.VERSION}"
        kotlinCompilation.dependencies {
            implementation(temporalKtCoordinates)
        }

        return project.provider {
            buildList {
                // Pass enabled flag
                val enabled = extension?.enabled?.get() ?: true
                add(SubpluginOption(TemporalCommandLineProcessor.OPTION_ENABLED, enabled.toString()))

                // Pass output directory
                extension?.outputDir?.orNull?.let { dir ->
                    add(SubpluginOption(TemporalCommandLineProcessor.OPTION_OUTPUT_DIR, dir.asFile.absolutePath))
                }
            }
        }
    }

    companion object {
        const val EXTENSION_NAME = "temporal"
    }
}
