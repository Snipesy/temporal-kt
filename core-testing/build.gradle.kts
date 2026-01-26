import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinxCoroutinesTest)
    implementation(libs.protobufJava)
    implementation(libs.protobufKotlin)
    implementation(libs.slf4jApi)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "testing"
            from(components["java"])

            pom {
                name.set("Temporal KT Testing")
                description.set("Test utilities for Temporal KT SDK")
            }
        }
    }
}

tasks.named<KotlinCompilationTask<*>>("compileKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}
