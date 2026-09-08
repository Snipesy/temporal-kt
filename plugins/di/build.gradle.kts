import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    // Tests here execute native code, so they need the Core native library on the classpath.
    id("buildsrc.convention.temporal-native-test")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinReflect)

    testImplementation(kotlin("test"))
    testImplementation(project(":core-testing"))
    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.slf4jSimple)
}

mavenPublishing {
    coordinates(artifactId = "di")

    pom {
        name.set("Temporal KT Dependencies")
        description.set("Dependency Injection plugin for Temporal KT")
    }
}

tasks.named<KotlinCompilationTask<*>>("compileKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}

tasks.named<KotlinCompilationTask<*>>("compileTestKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}
