import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
}

dependencies {
    api(project(":core"))

    // OpenTelemetry API
    api(libs.opentelemetryApi)
    api(libs.opentelemetryExtensionKotlin)

    // Context propagation
    implementation(libs.opentelemetryContext)

    // SLF4J for MDC
    implementation(libs.slf4jApi)

    // Protobuf for accessing task fields (via core-bridge)
    implementation(libs.protobufJava)

    testImplementation(kotlin("test"))
    testImplementation(project(":core-testing"))
    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.opentelemetrySdk)
    testImplementation(libs.opentelemetrySdkTesting)
    testImplementation(libs.logbackClassic)
}

mavenPublishing {
    coordinates(artifactId = "opentelemetry")

    pom {
        name.set("Temporal KT OpenTelemetry")
        description.set("OpenTelemetry tracing and metrics plugin for Temporal KT")
    }
}

tasks.named<KotlinCompilationTask<*>>("compileKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}

tasks.named<KotlinCompilationTask<*>>("compileTestKotlin").configure {
    compilerOptions.optIn.add("com.surrealdev.temporal.annotation.InternalTemporalApi")
}
