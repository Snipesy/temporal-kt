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

rootProject.name = "temporal-kt"
