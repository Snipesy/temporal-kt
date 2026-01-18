dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

include(":core-bridge")
include(":temporal-kt")

rootProject.name = "temporal-kt"
