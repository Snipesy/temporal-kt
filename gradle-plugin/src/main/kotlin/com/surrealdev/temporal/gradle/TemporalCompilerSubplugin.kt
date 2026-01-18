package com.surrealdev.temporal.gradle

import com.surrealdev.temporal.compiler.TemporalCommandLineProcessor
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Kotlin compiler plugin support for Temporal DSL processing.
 *
 * This subplugin bridges the Gradle build system and the Temporal compiler plugin.
 * It is automatically discovered via META-INF/services when the Kotlin plugin is applied.
 *
 * Based on patterns from:
 * - kotlinx.serialization
 * - Jetpack Compose compiler
 * - Official Kotlin compiler plugin template
 */
class TemporalCompilerSubplugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        // Apply our main plugin if not already applied
        target.pluginManager.apply(TemporalGradlePlugin::class.java)
    }

    /**
     * Determines if this plugin should apply to the given compilation.
     * We apply to all JVM and multiplatform compilations.
     */
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.target.project
        val extension = project.extensions.findByType(TemporalExtension::class.java)
        return extension?.enabled?.get() ?: true
    }

    /**
     * Provides configuration options to the compiler plugin.
     */
    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val extension = project.extensions.findByType(TemporalExtension::class.java)

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

    /**
     * Returns the unique identifier for this compiler plugin.
     * Must match the pluginId in TemporalCommandLineProcessor.
     */
    override fun getCompilerPluginId(): String = TemporalCommandLineProcessor.PLUGIN_ID

    /**
     * Specifies the compiler plugin artifact coordinates.
     * This tells Gradle which JAR contains the actual compiler plugin.
     */
    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(
            groupId = "com.surrealdev.temporal",
            artifactId = "compiler-plugin",
            // Version will be resolved from the project's dependency configuration
            version = null,
        )
}
