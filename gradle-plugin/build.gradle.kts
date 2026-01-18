plugins {
    id("buildsrc.convention.kotlin-jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = property("GROUP") as String
version = property("VERSION") as String

dependencies {
    // Kotlin Gradle Plugin API for KotlinCompilerPluginSupportPlugin
    compileOnly(libs.kotlinGradlePluginApi)

    // Our compiler plugin artifact
    implementation(project(":compiler-plugin"))

    testImplementation(kotlin("test"))
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
