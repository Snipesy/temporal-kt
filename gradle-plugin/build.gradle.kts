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

// Mirrors the composite version applied in core-bridge/build.gradle.kts and protos/build.gradle.kts.
val bridgeVersion: String by project
val bridgeSdkCoreVersion: String by project
val coreBridgeVersion = "$bridgeSdkCoreVersion-$bridgeVersion"

buildConfig {
    packageName("com.surrealdev.temporal.gradle")
    documentation.set("Build-time configuration for Temporal Gradle Plugin.")

    buildConfigField("VERSION", project.version.toString())
    buildConfigField("GROUP_ID", project.group.toString())
    buildConfigField("COMPILER_PLUGIN_ARTIFACT_ID", "compiler-plugin")
    buildConfigField("CORE_BRIDGE_ARTIFACT_ID", "core-bridge")

    // core-bridge and protos are versioned as `<sdkCoreVersion>-<bridgeVersion>`, so they do NOT share
    // `VERSION` with core. Resolving the bridge with the SDK version silently produced a
    // "not found" the moment the two diverged -- and, worse, could resolve the main jar and the
    // native classifier jar at different versions.
    buildConfigField("CORE_BRIDGE_VERSION", coreBridgeVersion)
    buildConfigField("PROTOS_VERSION", coreBridgeVersion)
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
