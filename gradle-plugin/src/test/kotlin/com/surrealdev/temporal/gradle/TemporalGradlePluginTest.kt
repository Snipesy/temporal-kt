package com.surrealdev.temporal.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class TemporalGradlePluginTest {
    @Test
    fun `bridge constraint works with either Kotlin plugin order`() {
        for (kotlinFirst in listOf(false, true)) {
            val project = ProjectBuilder.builder().build()
            if (kotlinFirst) project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            project.pluginManager.apply(TemporalGradlePlugin::class.java)
            if (!kotlinFirst) project.pluginManager.apply("org.jetbrains.kotlin.jvm")

            val constraint =
                project.configurations
                    .getByName("runtimeClasspath")
                    .allDependencyConstraints
                    .single { it.name == "core-bridge" }
            assertEquals(BuildConfig.CORE_BRIDGE_VERSION, constraint.versionConstraint.strictVersion)
        }
    }
}
