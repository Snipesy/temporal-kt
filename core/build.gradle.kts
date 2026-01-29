import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    api(project(":core-bridge"))
    api(libs.bundles.kotlinxEcosystem)
    implementation(libs.protobufJava)
    implementation(libs.protobufJavaUtil)
    implementation(libs.protobufKotlin)
    implementation(libs.bundles.hoplite)
    implementation(libs.kotlinReflect)
    api(libs.slf4jApi)
    implementation(libs.kotlinCoroutinesSl4j)

    testImplementation(kotlin("test"))
    testImplementation(libs.slf4jSimple)
    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(project(":core-testing"))
}

mavenPublishing {
    coordinates(artifactId = "core")

    pom {
        name.set("Temporal KT")
        description.set("Kotlin-first SDK for Temporal")
    }
}

tasks.named<KotlinCompilationTask<*>>("compileKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}

tasks.named<KotlinCompilationTask<*>>("compileTestKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}
