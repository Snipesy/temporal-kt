import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    id("buildsrc.convention.maven-publish")
}

// A BOM pinning the exact set of temporal-kt artifacts that are known to work together.
//
// This exists because the artifacts no longer share a single version: core-bridge and protos
// carry a composite `<sdkCoreVersion>-<bridgeVersion>` coordinate, are published from a separate
// repository, and everything else tracks the SDK version. Gradle users who apply `com.surrealdev.temporal` get the right versions from the
// plugin's generated constants, but Maven users -- and Gradle users who wire dependencies by
// hand -- have no way to know which bridge a given core was built against. Importing this
// platform answers that in one line.
val bridgeVersion: String by project
val bridgeSdkCoreVersion: String by project
val coreBridgeVersion = "$bridgeSdkCoreVersion-$bridgeVersion"

dependencies {
    constraints {
        // Versioned with the SDK.
        api("${project.group}:core:${rootProject.version}")
        api("${project.group}:core-common:$bridgeVersion")
        api("${project.group}:testing:${rootProject.version}")
        api("${project.group}:di:${rootProject.version}")
        api("${project.group}:opentelemetry:${rootProject.version}")
        api("${project.group}:health:${rootProject.version}")
        api("${project.group}:compiler-plugin:${rootProject.version}")
        api("${project.group}:gradle-plugin:${rootProject.version}")
        api("${project.group}:jib-plugin:${rootProject.version}")

        // Composite-versioned: tied to a Temporal SDK-Core release. The native library ships as
        // classifier artifacts of core-bridge, which inherit this version, so pinning the module
        // here keeps the main jar and the native jar in step.
        api("${project.group}:core-bridge:$coreBridgeVersion")
        api("${project.group}:protos:$coreBridgeVersion")
    }
}

mavenPublishing {
    configure(JavaPlatform())

    coordinates(artifactId = "bom")

    pom {
        name.set("Temporal KT BOM")
        description.set("Bill of materials pinning a compatible set of temporal-kt artifacts")
    }
}
