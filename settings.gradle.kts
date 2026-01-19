dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

include(":core-bridge")
include(":temporal-kt")
include(":compiler-plugin")
include(":gradle-plugin")

// Example modules
include(":examples:hello-world")
include(":examples:config-driven")
include(":examples:multi-worker")

rootProject.name = "temporal-kt"
