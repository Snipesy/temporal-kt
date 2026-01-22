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
}

gradlePlugin {
    plugins {
        create("temporalPlugin") {
            id = "com.surrealdev.temporal-kt"
            implementationClass = "com.surrealdev.temporal.gradle.TemporalGradlePlugin"
            displayName = "Temporal Kotlin Plugin"
            description = "Gradle plugin for Temporal workflow DSL compilation and client stub generation"
        }
    }
}

// Configure the publication created by java-gradle-plugin
afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("pluginMaven") {
                pom {
                    name.set("Temporal Kotlin Gradle Plugin")
                    description.set("Gradle plugin for Temporal workflow DSL compilation and client stub generation")
                }
            }
        }
    }
}
