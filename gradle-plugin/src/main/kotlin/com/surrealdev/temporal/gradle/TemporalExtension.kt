package com.surrealdev.temporal.gradle

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Extension for configuring the Temporal Gradle plugin.
 *
 * Example usage:
 * ```kotlin
 * temporal {
 *     enabled = true
 *     outputDir = layout.buildDirectory.dir("generated/temporal")
 * }
 * ```
 */
abstract class TemporalExtension
    @Inject
    constructor(
        project: Project,
    ) {
        /**
         * Whether the Temporal compiler plugin is enabled.
         * Defaults to true.
         */
        abstract val enabled: Property<Boolean>

        /**
         * Output directory for generated client stubs and metadata.
         * Defaults to `build/generated/temporal`.
         */
        abstract val outputDir: DirectoryProperty

        init {
            enabled.convention(true)
        }
    }
