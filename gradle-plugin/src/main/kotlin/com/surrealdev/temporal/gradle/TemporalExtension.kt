package com.surrealdev.temporal.gradle

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
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
 *     native {
 *         enabled = true
 *         classifier = "macos-aarch64" // or omit for auto-detection
 *     }
 * }
 * ```
 */
abstract class TemporalExtension
    @Inject
    constructor(
        project: Project,
        objects: ObjectFactory,
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

        /**
         * Configuration for native library dependencies.
         */
        val native: NativeExtension = objects.newInstance(NativeExtension::class.java)

        /**
         * Configures native library dependencies.
         */
        fun native(action: Action<NativeExtension>) {
            action.execute(native)
        }

        init {
            enabled.convention(true)
        }
    }

/**
 * Extension for configuring native library dependencies.
 */
abstract class NativeExtension
    @Inject
    constructor() {
        /**
         * Whether to add native library dependency.
         * Defaults to true.
         */
        abstract val enabled: Property<Boolean>

        /**
         * Explicit platform classifier to use.
         * If not set, the platform is auto-detected based on OS and architecture.
         *
         * Valid classifiers:
         * - `macos-aarch64` - macOS Apple Silicon
         * - `macos-x86_64` - macOS Intel
         * - `linux-x86_64-gnu` - Linux x86_64 (glibc)
         * - `linux-aarch64-gnu` - Linux ARM64 (glibc)
         * - `windows-x86_64` - Windows x86_64
         */
        abstract val classifier: Property<String>

        init {
            enabled.convention(true)
        }
    }
