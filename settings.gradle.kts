dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        // For com.jetbrains.intellij.platform:util — used by compiler-plugin tests to avoid
        // NioFiles.deleteRecursively NoSuchMethodError under kotlin-compiler-internal-test-framework.
        maven("https://www.jetbrains.com/intellij-repository/releases")
        // Kotlin EAP / IDE-prefixed compiler artifacts (e.g. `2.4.0-ij261-32`) — required to build
        // the compiler-plugin against the IDE's bundled Kotlin via `-Pkotlin.compiler=<ij-version>`.
        // See https://github.com/Mr3zee/kotlin-external-fir-support/blob/main/GUIDE.md
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
        // Kotlin master / dev builds (e.g. `2.4.0-dev-12345`).
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    }
}

include(":core-common")
include(":core-bridge")
include(":core")
include(":core-testing")
include(":plugins:di")
include(":plugins:opentelemetry")
include(":compiler-plugin")
include(":compiler-plugin-runtime")
include(":gradle-plugin")
include(":plugins:jib")
include(":plugins:health")

// Example modules
include(":examples:hello-world")
include(":examples:config-driven")
include(":examples:multi-worker")
include(":examples:otel-verify")

rootProject.name = "temporal-kt"
