import org.gradle.internal.os.OperatingSystem

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    id("com.github.gmazzo.buildconfig")
}

// Versioned as `<sdkCoreVersion>-<temporal-kt version>` (e.g. 0.6.0-0.1.11): this artifact's
// content is determined by a Temporal SDK-Core release as much as by temporal-kt, so the
// coordinate says which Core it speaks to. See gradle.properties.
val sdkCoreVersion: String by project
version = "$sdkCoreVersion-${rootProject.version}"

// Platform classifier to internal resource directory mapping
data class NativePlatform(
    val classifier: String,
    val resourceDir: String,
)

val nativePlatforms =
    listOf(
        NativePlatform("linux-x86_64-gnu", "linux-x86_64-gnu"),
        NativePlatform("linux-aarch64-gnu", "linux-aarch64-gnu"),
        // Future: NativePlatform("linux-x86_64-musl", "linux-x86_64-musl"),
        // Future: NativePlatform("linux-aarch64-musl", "linux-aarch64-musl"),
        NativePlatform("macos-aarch64", "macos-aarch64"),
        NativePlatform("windows-x86_64", "windows-x86_64"),
    )

dependencies {
    api(project(":core-common"))
    // Proto types appear in this module's own signatures (worker poll/complete traffic in
    // `coresdk.*`, TemporalTestServer returns io.temporal.api.testservice.v1.*), so this is api.
    api(project(":protos"))
    implementation(libs.kotlinxCoroutines)
    implementation(libs.slf4jApi)
    compileOnly(libs.opentelemetryApi)

    testImplementation(kotlin("test"))
}

// Detect current platform
val os: OperatingSystem = OperatingSystem.current()
val arch: String = System.getProperty("os.arch")

val nativePlatform: String =
    when {
        os.isMacOsX && arch == "aarch64" -> "macos-aarch64"
        os.isLinux && arch == "aarch64" -> "linux-aarch64-gnu"
        os.isLinux -> "linux-x86_64-gnu"
        os.isWindows -> "windows-x86_64"
        else -> throw GradleException("Unsupported platform: ${os.name} / $arch")
    }

val libPrefix: String = if (os.isWindows) "" else "lib"
val libExtension: String =
    when {
        os.isMacOsX -> "dylib"
        os.isLinux -> "so"
        os.isWindows -> "dll"
        else -> throw GradleException("Unsupported platform")
    }

// Library name from C bridge
val nativeLibName = "temporalio_sdk_core_c_bridge"

// Output directory for native libraries (in build folder, not src)
val nativeLibsDir = layout.buildDirectory.dir("native-libs")

// Native build for current platform - builds from parent workspace with locked dependencies
val cargoBuild by tasks.registering(Exec::class) {
    description = "Build Temporal SDK Core C bridge for current platform ($nativePlatform)"
    group = "build"
    workingDir = file("rust")
    commandLine("cargo", "build", "--release", "--locked", "-p", "temporalio-sdk-core-c-bridge")

    inputs.files(
        fileTree("rust") {
            include("Cargo.toml", "Cargo.lock")
        },
        fileTree("rust/sdk-core") {
            // rust-toolchain.toml pins the compiler version, so a change to it changes the
            // produced library and must invalidate this task.
            include("**/*.rs", "**/Cargo.toml", "rust-toolchain.toml")
        },
    )
    outputs.file("rust/target/release/${libPrefix}$nativeLibName.$libExtension")
}

val copyNativeLib by tasks.registering(Copy::class) {
    description = "Copy native library for current platform to build directory"
    group = "build"
    dependsOn(cargoBuild)

    from("rust/target/release/${libPrefix}$nativeLibName.$libExtension")
    into(nativeLibsDir.map { it.dir("native/$nativePlatform") })
}

// Native build for Linux x86_64 (runs on x86_64 Linux runner)
val cargoBuildLinuxx8664 by tasks.registering(Exec::class) {
    description = "Build native library for linux-x86_64-gnu"
    group = "build"
    workingDir = file("rust")
    commandLine(
        "cargo",
        "build",
        "--release",
        "--locked",
        "-p",
        "temporalio-sdk-core-c-bridge",
        "--target",
        "x86_64-unknown-linux-gnu",
    )

    inputs.files(
        fileTree("rust") {
            include("Cargo.toml", "Cargo.lock")
        },
        fileTree("rust/sdk-core") {
            // rust-toolchain.toml pins the compiler version, so a change to it changes the
            // produced library and must invalidate this task.
            include("**/*.rs", "**/Cargo.toml", "rust-toolchain.toml")
        },
    )
    outputs.file("rust/target/x86_64-unknown-linux-gnu/release/lib$nativeLibName.so")
}

val copyNativeLibLinuxx8664 by tasks.registering(Copy::class) {
    description = "Copy native library for linux-x86_64-gnu to build directory"
    group = "build"
    dependsOn(cargoBuildLinuxx8664)

    from("rust/target/x86_64-unknown-linux-gnu/release/lib$nativeLibName.so")
    into(nativeLibsDir.map { it.dir("native/linux-x86_64-gnu") })
}

// Native build for Linux aarch64 (runs on aarch64 Linux runner)
val cargoBuildLinuxAarch64 by tasks.registering(Exec::class) {
    description = "Build native library for linux-aarch64-gnu"
    group = "build"
    workingDir = file("rust")
    commandLine(
        "cargo",
        "build",
        "--release",
        "--locked",
        "-p",
        "temporalio-sdk-core-c-bridge",
        "--target",
        "aarch64-unknown-linux-gnu",
    )

    inputs.files(
        fileTree("rust") {
            include("Cargo.toml", "Cargo.lock")
        },
        fileTree("rust/sdk-core") {
            // rust-toolchain.toml pins the compiler version, so a change to it changes the
            // produced library and must invalidate this task.
            include("**/*.rs", "**/Cargo.toml", "rust-toolchain.toml")
        },
    )
    outputs.file("rust/target/aarch64-unknown-linux-gnu/release/lib$nativeLibName.so")
}

val copyNativeLibLinuxAarch64 by tasks.registering(Copy::class) {
    description = "Copy native library for linux-aarch64-gnu to build directory"
    group = "build"
    dependsOn(cargoBuildLinuxAarch64)

    from("rust/target/aarch64-unknown-linux-gnu/release/lib$nativeLibName.so")
    into(nativeLibsDir.map { it.dir("native/linux-aarch64-gnu") })
}

// Windows x86_64 build (native MSVC on Windows runner)
val cargoBuildWindowsx8664 by tasks.registering(Exec::class) {
    description = "Build native library for windows-x86_64 (native MSVC)"
    group = "build"
    workingDir = file("rust")
    commandLine(
        "cargo",
        "build",
        "--release",
        "--locked",
        "-p",
        "temporalio-sdk-core-c-bridge",
        "--target",
        "x86_64-pc-windows-msvc",
    )

    inputs.files(
        fileTree("rust") {
            include("Cargo.toml", "Cargo.lock")
        },
        fileTree("rust/sdk-core") {
            // rust-toolchain.toml pins the compiler version, so a change to it changes the
            // produced library and must invalidate this task.
            include("**/*.rs", "**/Cargo.toml", "rust-toolchain.toml")
        },
    )
    outputs.file("rust/target/x86_64-pc-windows-msvc/release/$nativeLibName.dll")
}

val copyNativeLibWindowsx8664 by tasks.registering(Copy::class) {
    description = "Copy native library for windows-x86_64 to build directory"
    group = "build"
    dependsOn(cargoBuildWindowsx8664)

    from("rust/target/x86_64-pc-windows-msvc/release/$nativeLibName.dll")
    into(nativeLibsDir.map { it.dir("native/windows-x86_64") })
}

// macOS aarch64 (Apple Silicon) build - native on ARM Mac runner
val cargoBuildMacosAarch64 by tasks.registering(Exec::class) {
    description = "Build native library for macos-aarch64 (native on ARM Mac)"
    group = "build"
    workingDir = file("rust")
    commandLine(
        "cargo",
        "build",
        "--release",
        "--locked",
        "-p",
        "temporalio-sdk-core-c-bridge",
        "--target",
        "aarch64-apple-darwin",
    )

    inputs.files(
        fileTree("rust") {
            include("Cargo.toml", "Cargo.lock")
        },
        fileTree("rust/sdk-core") {
            // rust-toolchain.toml pins the compiler version, so a change to it changes the
            // produced library and must invalidate this task.
            include("**/*.rs", "**/Cargo.toml", "rust-toolchain.toml")
        },
    )
    outputs.file("rust/target/aarch64-apple-darwin/release/lib$nativeLibName.dylib")
}

val copyNativeLibMacosAarch64 by tasks.registering(Copy::class) {
    description = "Copy native library for macos-aarch64 to build directory"
    group = "build"
    dependsOn(cargoBuildMacosAarch64)

    from("rust/target/aarch64-apple-darwin/release/lib$nativeLibName.dylib")
    into(nativeLibsDir.map { it.dir("native/macos-aarch64") })
}

// Build all platforms task
val cargoBuildAll by tasks.registering {
    description = "Build Rust native library for all supported platforms"
    group = "build"
    dependsOn(
        cargoBuildLinuxx8664,
        cargoBuildLinuxAarch64,
        cargoBuildWindowsx8664,
        cargoBuildMacosAarch64,
    )
}

val copyAllNativeLibs by tasks.registering {
    description = "Copy all native libraries to build directory"
    group = "build"
    dependsOn(
        copyNativeLibLinuxx8664,
        copyNativeLibLinuxAarch64,
        copyNativeLibWindowsx8664,
        copyNativeLibMacosAarch64,
    )
}

// Platform-specific aggregator tasks for CI matrix builds
val copyLinuxNativeLibs by tasks.registering {
    description = "Copy Linux native libraries (for Linux CI runner)"
    group = "build"
    dependsOn(copyNativeLibLinuxx8664, copyNativeLibLinuxAarch64)
}

val copyMacosAarch64NativeLib by tasks.registering {
    description = "Copy macOS ARM64 native library (for ARM Mac CI runner)"
    group = "build"
    dependsOn(copyNativeLibMacosAarch64)
}

val copyWindowsNativeLib by tasks.registering {
    description = "Copy Windows native library (for Windows CI runner)"
    group = "build"
    dependsOn(copyNativeLibWindowsx8664)
}

// Set -PskipNativeBuild=true to skip native library building (used in CI publish job)
val skipNativeBuild = project.findProperty("skipNativeBuild")?.toString()?.toBoolean() ?: false

// Create platform-specific classifier JARs containing only the native library
nativePlatforms.forEach { platform ->
    val taskName = "${platform.classifier.replace("-", "").replace("_", "")}NativeJar"
    tasks.register<Jar>(taskName) {
        description = "Create classifier JAR with native library for ${platform.classifier}"
        group = "build"
        archiveClassifier.set(platform.classifier)
        from(nativeLibsDir.map { it.dir("native/${platform.resourceDir}") }) {
            into("native/${platform.resourceDir}")
        }
    }
}

// Host-platform native library, exposed to other modules in this build.
//
// Consumers declare `testRuntimeOnly(project(path = ":core-bridge", configuration = "nativeRuntime"))`
// (see the `temporal-native-test` convention plugin) so the native arrives as a single JAR on the
// classpath -- the same shape it has when resolved from Maven as a classifier artifact. This
// deliberately replaces the old arrangement, where every module in the build added
// `core-bridge/build/native-libs` as a resources srcDir and made its own `processTestResources`
// depend on `:core-bridge:copyNativeLib`. That fan-out put a full Rust build behind modules that
// never touch native code, and copied the ~37 MB library into each module's build/resources tree.
//
// When -PskipNativeBuild=true the JAR is packaged from whatever is already in build/native-libs
// (CI downloads prebuilt libraries there), so cargo is not invoked.
val hostNativeJar by tasks.registering(Jar::class) {
    description = "JAR containing the host platform's native library, for consumers inside this build"
    group = "build"
    archiveClassifier.set("native-host")
    if (!skipNativeBuild) {
        dependsOn(copyNativeLib)
    }
    from(nativeLibsDir.map { it.dir("native/$nativePlatform") }) {
        into("native/$nativePlatform")
    }
}

val nativeRuntime =
    configurations.consumable("nativeRuntime") {
        description = "Host-platform native library, as a JAR, for other modules in this build"
    }

artifacts.add(nativeRuntime.name, hostNativeJar)

// For local development/testing, include current platform's native lib in test resources
tasks.named<ProcessResources>("processTestResources") {
    if (!skipNativeBuild) {
        dependsOn(copyNativeLib)
    }
    from(nativeLibsDir) {
        into("")
    }
}

// Clean task for Rust artifacts
tasks.register<Delete>("cargoClean") {
    description = "Clean Rust build artifacts"
    group = "build"
    delete("rust/target")
}

tasks.named("clean") {
    dependsOn("cargoClean")
}

// Enable native access for FFM API to suppress warnings
tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Generate BuildConfig with version constants
val temporalCliVersion: String by project

buildConfig {
    packageName("com.surrealdev.temporal.core")
    documentation.set("Build-time configuration constants.")

    buildConfigField("TEMPORAL_CLI_VERSION", temporalCliVersion)

    // The temporal-kt SDK version, NOT this module's composite version. This is reported to the
    // Temporal server as the client version (see TemporalCoreClient.clientVersion), where
    // "0.6.0-0.1.11" would wrongly read as an SDK 0.6.0. rootProject.version is the SDK version.
    buildConfigField("SDK_VERSION", rootProject.version.toString())

    // This module's own coordinate, `<sdkCoreVersion>-<version>`, for diagnostics.
    buildConfigField("BRIDGE_VERSION", project.version.toString())
    buildConfigField("SDK_CORE_VERSION", sdkCoreVersion)
}

mavenPublishing {
    coordinates(artifactId = "core-bridge")

    pom {
        name.set("Temporal KT Core Bridge")
        description.set("Kotlin FFM Bridge to Temporal Core SDK")
    }
}

// Configure publishing to include platform-specific classifier JARs
afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("maven") {
                nativePlatforms.forEach { platform ->
                    val taskName = "${platform.classifier.replace("-", "").replace("_", "")}NativeJar"
                    artifact(tasks.named(taskName)) {
                        classifier = platform.classifier
                    }
                }
            }
        }
    }
}
