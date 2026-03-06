package com.surrealdev.temporal.gradle.jib

import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.api.buildplan.LayerObject
import com.google.cloud.tools.jib.gradle.extension.GradleData
import com.google.cloud.tools.jib.gradle.extension.JibGradlePluginExtension
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger.LogLevel
import java.util.Optional

/**
 * Jib extension that filters Temporal native classifier JARs based on the target container platform.
 *
 * When building multi-arch container images, all native classifier JARs are on the classpath.
 * This extension removes the ones that don't match the target platform, keeping images lean.
 *
 * For example, when building for linux/arm64, the `core-bridge-*-linux-x86_64-gnu.jar`,
 * `core-bridge-*-macos-*.jar`, and `core-bridge-*-windows-*.jar` entries are removed.
 */
class TemporalJibExtension : JibGradlePluginExtension<Void> {
    override fun getExtraConfigType(): Optional<Class<Void>> = Optional.empty()

    override fun extendContainerBuildPlan(
        buildPlan: ContainerBuildPlan,
        properties: Map<String, String>,
        extraConfig: Optional<Void>,
        gradleData: GradleData,
        logger: ExtensionLogger,
    ): ContainerBuildPlan {
        val platforms = buildPlan.platforms
        if (platforms.isEmpty()) return buildPlan

        // Determine which classifiers are valid for the target platform(s)
        val allowedClassifiers =
            platforms.flatMapTo(mutableSetOf()) { platform ->
                classifiersForPlatform(platform.architecture, platform.os)
            }

        if (allowedClassifiers.isEmpty()) {
            logger.log(
                LogLevel.WARN,
                "Temporal: no known classifiers for platforms $platforms, skipping filtering",
            )
            return buildPlan
        }

        logger.log(
            LogLevel.LIFECYCLE,
            "Temporal: filtering native JARs, keeping classifiers: $allowedClassifiers",
        )

        val newLayers =
            buildPlan.layers.map { layer ->
                filterLayer(layer, allowedClassifiers)
            }

        return buildPlan
            .toBuilder()
            .setLayers(newLayers)
            .build()
    }

    companion object {
        /** All known native classifier suffixes that appear in core-bridge JAR filenames. */
        private val ALL_CLASSIFIERS =
            setOf(
                "linux-x86_64-gnu",
                "linux-aarch64-gnu",
                "macos-x86_64",
                "macos-aarch64",
                "windows-x86_64",
            )

        /** Maps Jib platform (arch, os) to the matching Temporal classifier(s). */
        private fun classifiersForPlatform(
            architecture: String,
            os: String,
        ): Set<String> =
            when (os) {
                "linux" if architecture == "amd64" -> setOf("linux-x86_64-gnu")
                "linux" if architecture == "arm64" -> setOf("linux-aarch64-gnu")
                "darwin" if architecture == "arm64" -> setOf("macos-aarch64")
                "darwin" if architecture == "amd64" -> setOf("macos-x86_64")
                "windows" if architecture == "amd64" -> setOf("windows-x86_64")
                else -> emptySet()
            }

        /** Returns true if the filename looks like a core-bridge classifier JAR. */
        private fun isCoreBridgeClassifierJar(fileName: String): Boolean =
            fileName.startsWith("core-bridge-") && ALL_CLASSIFIERS.any { fileName.contains("-$it") }

        /** Returns true if the core-bridge classifier JAR matches one of the allowed classifiers. */
        private fun isAllowedClassifierJar(
            fileName: String,
            allowedClassifiers: Set<String>,
        ): Boolean = allowedClassifiers.any { fileName.contains("-$it") }

        private fun filterLayer(
            layer: LayerObject,
            allowedClassifiers: Set<String>,
        ): LayerObject {
            if (layer.type != LayerObject.Type.FILE_ENTRIES) return layer

            val fileLayer = layer as FileEntriesLayer
            val filtered =
                fileLayer.entries.filter { entry ->
                    val fileName = entry.sourceFile.fileName.toString()
                    // Keep everything that isn't a core-bridge classifier JAR,
                    // and keep core-bridge classifier JARs that match the target platform.
                    !isCoreBridgeClassifierJar(fileName) ||
                        isAllowedClassifierJar(fileName, allowedClassifiers)
                }

            return fileLayer
                .toBuilder()
                .setEntries(filtered)
                .build()
        }
    }
}
