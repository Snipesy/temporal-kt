import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
}

// Coordinates of the published temporal-kt-bridge artifacts. Derived from gradle.properties so
// the composite version cannot drift from its parts; see the comment there.
val bridgeVersion: String by project
val bridgeSdkCoreVersion: String by project
val bridgeProtosSdkCoreVersion: String by project
val bridgeComposite = "$bridgeSdkCoreVersion-$bridgeVersion"
val protosComposite = "$bridgeProtosSdkCoreVersion-$bridgeVersion"

dependencies {
    api(project(":core"))
    implementation("com.surrealdev.temporal:core-bridge:$bridgeComposite")
    implementation(libs.kotlinxCoroutinesTest)
    implementation(libs.slf4jApi)
}

mavenPublishing {
    coordinates(artifactId = "testing")

    pom {
        name.set("Temporal KT Testing")
        description.set("Test utilities for Temporal KT SDK")
    }
}

tasks.named<KotlinCompilationTask<*>>("compileKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}
