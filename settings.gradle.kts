dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

include(":bom")
include(":core-common")
include(":protos")
include(":core-bridge")
include(":core")
include(":core-testing")
include(":plugins:di")
include(":plugins:opentelemetry")
include(":compiler-plugin")
include(":gradle-plugin")
include(":plugins:jib")
include(":plugins:health")

// Example modules
include(":examples:hello-world")
include(":examples:config-driven")
include(":examples:multi-worker")
include(":examples:otel-verify")

rootProject.name = "temporal-kt"
