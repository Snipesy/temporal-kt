package com.surrealdev.temporal.gradle.jib

import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.api.buildplan.Platform
import com.google.cloud.tools.jib.gradle.extension.GradleData
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger
import java.nio.file.Path
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals

class TemporalJibExtensionTest {
    @Test
    fun `Linux images keep both libc variants only for their target architecture`() {
        val mainJar = "core-bridge-0.8.0-0.1.11-SNAPSHOT.jar"
        val classifiers =
            listOf(
                "linux-x86_64-gnu",
                "linux-aarch64-gnu",
                "linux-x86_64-musl",
                "linux-aarch64-musl",
                "macos-aarch64",
                "windows-x86_64",
            )
        val nativeJars = classifiers.map { "core-bridge-0.8.0-0.1.11-SNAPSHOT-$it.jar" }
        val layer = FileEntriesLayer.builder()
        for (jar in nativeJars + mainJar) {
            layer.addEntry(Path.of(jar), AbsoluteUnixPath.get("/app/libs/$jar"))
        }

        for ((architecture, classifierArch) in listOf("amd64" to "x86_64", "arm64" to "aarch64")) {
            val plan =
                ContainerBuildPlan
                    .builder()
                    .setPlatforms(setOf(Platform(architecture, "linux")))
                    .addLayer(layer.build())
                    .build()
            val filtered =
                TemporalJibExtension().extendContainerBuildPlan(
                    plan,
                    emptyMap(),
                    Optional.empty(),
                    GradleData { error("No project access needed") },
                    ExtensionLogger { _, _ -> },
                )
            val retained =
                (filtered.layers.single() as FileEntriesLayer).entries.map { it.sourceFile.fileName.toString() }
            assertEquals(
                nativeJars.filter { "-linux-$classifierArch-" in it } + mainJar,
                retained,
            )
        }
    }
}
