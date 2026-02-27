dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

include(":core-common")
include(":core-bridge")
include(":core")
include(":core-testing")
include(":plugins:di")
include(":plugins:opentelemetry")
include(":compiler-plugin")
include(":gradle-plugin")
include(":plugins:jib")

// Example modules
include(":examples:hello-world")
include(":examples:config-driven")
include(":examples:multi-worker")
include(":examples:otel-verify")

rootProject.name = "temporal-kt"
