plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    kotlin("kapt")
    alias(libs.plugins.kotlinPluginSerialization)
}

mavenPublishing {
    coordinates(artifactId = "compiler-plugin")

    pom {
        name.set("Temporal Kotlin Compiler Plugin")
        description.set("Kotlin compiler plugin for Temporal workflow determinism validation and code generation")
    }
}

dependencies {
    compileOnly(libs.kotlinCompilerEmbeddable)
    implementation(project(":core"))
    implementation(libs.kotlinxSerialization)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinCompilerEmbeddable)
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(project(":core"))
}
