package com.surrealdev.temporal.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Main Gradle plugin for Temporal Kotlin SDK.
 *
 * This plugin:
 * 1. Registers the Temporal extension for configuration
 * 2. Applies the compiler subplugin to process workflow/activity DSL
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("com.surrealdev.temporal")
 * }
 *
 * temporal {
 *     enabled = true
 *     outputDir = layout.buildDirectory.dir("generated/temporal")
 * }
 * ```
 */
class TemporalGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Register the temporal extension for user configuration
        val extension =
            project.extensions.create(
                EXTENSION_NAME,
                TemporalExtension::class.java,
                project,
            )

        // Configure default output directory
        extension.outputDir.convention(
            project.layout.buildDirectory.dir("generated/temporal"),
        )

        // The subplugin (TemporalCompilerSubplugin) is auto-registered via
        // META-INF/services and will be picked up by the Kotlin Gradle Plugin
    }

    companion object {
        const val EXTENSION_NAME = "temporal"
    }
}
