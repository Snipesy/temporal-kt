dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        // Listed FIRST so it actually wins. Gradle picks the newest timestamped SNAPSHOT across
        // repositories, so with mavenLocal last a stale remote snapshot silently shadows the
        // build you just published locally -- which defeats the point of the flag.
        if (providers.gradleProperty("useMavenLocal").isPresent) {
            mavenLocal()
        }
        // For consuming SNAPSHOT builds of temporal-kt-bridge between its releases.
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
        mavenCentral()
    }

}

// core-bridge, core-common and protos live in SurrealDevelopment/temporal-kt-bridge and are
// normally consumed as published artifacts, so this build needs neither a Rust toolchain nor
// protoc. To work on both repositories at once, point this at a local checkout:
//
//     ./gradlew build -Ptemporal.bridgePath=../temporal-kt-bridge
//
// Coordinates match, so Gradle substitutes the projects automatically. Note that classifier
// dependencies are not substitutable in a composite build, so the native library still has to
// come from -Ptemporal.nativeLib (see the temporal-native-test convention plugin).
val bridgePath =
    providers.gradleProperty("temporal.bridgePath").orNull
        ?: providers.environmentVariable("TEMPORAL_BRIDGE_PATH").orNull
if (bridgePath != null) {
    includeBuild(bridgePath)
}

include(":bom")
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
