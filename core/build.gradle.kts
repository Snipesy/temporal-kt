import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    // Tests here execute native code, so they need the Core native library on the classpath.
    id("buildsrc.convention.temporal-native-test")
    alias(libs.plugins.kotlinPluginSerialization)
    id("com.github.gmazzo.buildconfig")
}

// The core-bridge seam this module was compiled against. core-bridge is separately published on
// its own version, so a consumer can pin a bridge that does not fit this core; BridgeCompatibility
// compares this against BridgeBuildInfo.ABI_VERSION at startup. See gradle.properties.
val bridgeAbi: String by project

buildConfig {
    packageName("com.surrealdev.temporal.internal")
    documentation.set("Build-time configuration constants for temporal-kt core.")

    buildConfigField("REQUIRED_BRIDGE_ABI", bridgeAbi.toInt())
    buildConfigField("SDK_VERSION", project.version.toString())
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }
}

dependencies {
    api(project(":core-common"))
    implementation(project(":core-bridge"))
    // Proto types are pervasive in core's public ABI (io.temporal.api.* and coresdk.* appear
    // throughout core/api/core.api), so consumers need them on their compile classpath.
    // protobuf-java and protobuf-kotlin arrive transitively from :protos.
    api(project(":protos"))
    api(libs.bundles.kotlinxEcosystem)
    // Still needed directly: JsonFormat and Durations from protobuf-java-util.
    implementation(libs.protobufJavaUtil)
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
