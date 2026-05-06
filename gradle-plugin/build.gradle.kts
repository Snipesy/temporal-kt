plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    `java-gradle-plugin`
    id("com.github.gmazzo.buildconfig")
}

dependencies {
    // Kotlin Gradle Plugin API for KotlinCompilerPluginSupportPlugin
    compileOnly(libs.kotlinGradlePluginApi)

    // Our compiler plugin artifact
    implementation(project(":compiler-plugin"))

    testImplementation(kotlin("test"))
}

buildConfig {
    packageName("com.surrealdev.temporal.gradle")
    documentation.set("Build-time configuration for Temporal Gradle Plugin.")

    buildConfigField("VERSION", project.version.toString())
    buildConfigField("GROUP_ID", project.group.toString())
    buildConfigField("COMPILER_PLUGIN_ARTIFACT_ID", "compiler-plugin")
    buildConfigField("COMPILER_PLUGIN_RUNTIME_ARTIFACT_ID", "compiler-plugin-runtime")
    buildConfigField("CORE_BRIDGE_ARTIFACT_ID", "core-bridge")

    // The compiler-plugin artifact is published with KEFS-compatible
    // `<kotlinCompilerVersion>-<libraryVersion>` versioning so the IDE's external-FIR-support
    // tool can match it to the IDE's bundled compiler. The gradle plugin must request that exact
    // version when wiring SubpluginArtifact.
    buildConfigField("COMPILER_PLUGIN_KOTLIN_VERSION", libs.versions.kotlin.get())
}

gradlePlugin {
    plugins {
        create("temporalPlugin") {
            id = "com.surrealdev.temporal"
            implementationClass = "com.surrealdev.temporal.gradle.TemporalGradlePlugin"
            displayName = "Temporal Kotlin Plugin"
            description = "Gradle plugin for Temporal workflow DSL compilation and client stub generation"
        }
    }
}

mavenPublishing {
    coordinates(artifactId = "gradle-plugin")

    pom {
        name.set("Temporal Kotlin Gradle Plugin")
        description.set("Gradle plugin for Temporal workflow DSL compilation and client stub generation")
    }
}
