import org.gradle.internal.os.OperatingSystem

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    alias(libs.plugins.protobuf)
    id("com.github.gmazzo.buildconfig")
}

dependencies {
    implementation(libs.protobufJava)
    implementation(libs.protobufKotlin)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.slf4jApi)

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("kotlin")
            }
        }
    }
}

// Detect current platform
val os: OperatingSystem = OperatingSystem.current()
val arch: String = System.getProperty("os.arch")

val nativePlatform: String =
    when {
        os.isMacOsX && arch == "aarch64" -> "darwin-aarch64"
        os.isMacOsX -> "darwin-x86_64"
        os.isLinux && arch == "aarch64" -> "linux-aarch64"
        os.isLinux -> "linux-x86_64"
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

// Native build for current platform - builds the official sdk-core-c-bridge
val cargoBuild by tasks.registering(Exec::class) {
    description = "Build Temporal SDK Core C bridge for current platform ($nativePlatform)"
    group = "build"
    workingDir = file("rust/sdk-core/crates/sdk-core-c-bridge")
    commandLine("cargo", "build", "--release")

    inputs.files(
        fileTree("rust/sdk-core") {
            include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock")
        },
    )
    outputs.file("rust/sdk-core/target/release/${libPrefix}$nativeLibName.$libExtension")
}

val copyNativeLib by tasks.registering(Copy::class) {
    description = "Copy native library for current platform to build directory"
    group = "build"
    dependsOn(cargoBuild)

    from("rust/sdk-core/target/release/${libPrefix}$nativeLibName.$libExtension")
    into(nativeLibsDir.map { it.dir("native/$nativePlatform") })
}

// Cross-compilation for Linux x86_64 (requires cargo-zigbuild)
val cargoBuildLinuxx8664 by tasks.registering(Exec::class) {
    description = "Build native library for linux-x86_64 (requires cargo-zigbuild)"
    group = "build"
    workingDir = file("rust/sdk-core/crates/sdk-core-c-bridge")
    commandLine("cargo-zigbuild", "build", "--release", "--target", "x86_64-unknown-linux-gnu")

    inputs.files(
        fileTree("rust/sdk-core") {
            include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock")
        },
    )
    outputs.file("rust/sdk-core/target/x86_64-unknown-linux-gnu/release/lib$nativeLibName.so")
}

val copyNativeLibLinuxx8664 by tasks.registering(Copy::class) {
    description = "Copy native library for linux-x86_64 to build directory"
    group = "build"
    dependsOn(cargoBuildLinuxx8664)

    from("rust/sdk-core/target/x86_64-unknown-linux-gnu/release/lib$nativeLibName.so")
    into(nativeLibsDir.map { it.dir("native/linux-x86_64") })
}

// Cross-compilation for Linux aarch64 (requires cargo-zigbuild)
val cargoBuildLinuxAarch64 by tasks.registering(Exec::class) {
    description = "Build native library for linux-aarch64 (requires cargo-zigbuild)"
    group = "build"
    workingDir = file("rust/sdk-core/crates/sdk-core-c-bridge")
    commandLine("cargo-zigbuild", "build", "--release", "--target", "aarch64-unknown-linux-gnu")

    inputs.files(
        fileTree("rust/sdk-core") {
            include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock")
        },
    )
    outputs.file("rust/sdk-core/target/aarch64-unknown-linux-gnu/release/lib$nativeLibName.so")
}

val copyNativeLibLinuxAarch64 by tasks.registering(Copy::class) {
    description = "Copy native library for linux-aarch64 to build directory"
    group = "build"
    dependsOn(cargoBuildLinuxAarch64)

    from("rust/sdk-core/target/aarch64-unknown-linux-gnu/release/lib$nativeLibName.so")
    into(nativeLibsDir.map { it.dir("native/linux-aarch64") })
}

// Windows x86_64 build (native MSVC on Windows runner)
val cargoBuildWindowsx8664 by tasks.registering(Exec::class) {
    description = "Build native library for windows-x86_64 (native MSVC)"
    group = "build"
    workingDir = file("rust/sdk-core/crates/sdk-core-c-bridge")
    commandLine("cargo", "build", "--release", "--target", "x86_64-pc-windows-msvc")

    inputs.files(
        fileTree("rust/sdk-core") {
            include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock")
        },
    )
    outputs.file("rust/sdk-core/target/x86_64-pc-windows-msvc/release/$nativeLibName.dll")
}

val copyNativeLibWindowsx8664 by tasks.registering(Copy::class) {
    description = "Copy native library for windows-x86_64 to build directory"
    group = "build"
    dependsOn(cargoBuildWindowsx8664)

    from("rust/sdk-core/target/x86_64-pc-windows-msvc/release/$nativeLibName.dll")
    into(nativeLibsDir.map { it.dir("native/windows-x86_64") })
}

// macOS aarch64 (Apple Silicon) build - native on ARM Mac runner
val cargoBuildDarwinAarch64 by tasks.registering(Exec::class) {
    description = "Build native library for darwin-aarch64 (native on ARM Mac)"
    group = "build"
    workingDir = file("rust/sdk-core/crates/sdk-core-c-bridge")
    commandLine("cargo", "build", "--release", "--target", "aarch64-apple-darwin")

    inputs.files(
        fileTree("rust/sdk-core") {
            include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock")
        },
    )
    outputs.file("rust/sdk-core/target/aarch64-apple-darwin/release/lib$nativeLibName.dylib")
}

val copyNativeLibDarwinAarch64 by tasks.registering(Copy::class) {
    description = "Copy native library for darwin-aarch64 to build directory"
    group = "build"
    dependsOn(cargoBuildDarwinAarch64)

    from("rust/sdk-core/target/aarch64-apple-darwin/release/lib$nativeLibName.dylib")
    into(nativeLibsDir.map { it.dir("native/darwin-aarch64") })
}

// macOS x86_64 (Intel) build - native on Intel Mac runner
val cargoBuildDarwinx8664 by tasks.registering(Exec::class) {
    description = "Build native library for darwin-x86_64 (native on Intel Mac)"
    group = "build"
    workingDir = file("rust/sdk-core/crates/sdk-core-c-bridge")
    commandLine("cargo", "build", "--release", "--target", "x86_64-apple-darwin")

    inputs.files(
        fileTree("rust/sdk-core") {
            include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock")
        },
    )
    outputs.file("rust/sdk-core/target/x86_64-apple-darwin/release/lib$nativeLibName.dylib")
}

val copyNativeLibDarwinx8664 by tasks.registering(Copy::class) {
    description = "Copy native library for darwin-x86_64 to build directory"
    group = "build"
    dependsOn(cargoBuildDarwinx8664)

    from("rust/sdk-core/target/x86_64-apple-darwin/release/lib$nativeLibName.dylib")
    into(nativeLibsDir.map { it.dir("native/darwin-x86_64") })
}

// Build all platforms task
val cargoBuildAll by tasks.registering {
    description = "Build Rust native library for all supported platforms"
    group = "build"
    dependsOn(
        cargoBuildLinuxx8664,
        cargoBuildLinuxAarch64,
        cargoBuildWindowsx8664,
        cargoBuildDarwinAarch64,
        cargoBuildDarwinx8664,
    )
}

val copyAllNativeLibs by tasks.registering {
    description = "Copy all native libraries to build directory"
    group = "build"
    dependsOn(
        copyNativeLibLinuxx8664,
        copyNativeLibLinuxAarch64,
        copyNativeLibWindowsx8664,
        copyNativeLibDarwinAarch64,
        copyNativeLibDarwinx8664,
    )
}

// Platform-specific aggregator tasks for CI matrix builds
val copyLinuxNativeLibs by tasks.registering {
    description = "Copy Linux native libraries (for Linux CI runner)"
    group = "build"
    dependsOn(copyNativeLibLinuxx8664, copyNativeLibLinuxAarch64)
}

val copyDarwinAarch64NativeLib by tasks.registering {
    description = "Copy Darwin ARM64 native library (for ARM Mac CI runner)"
    group = "build"
    dependsOn(copyNativeLibDarwinAarch64)
}

val copyDarwinx8664NativeLib by tasks.registering {
    description = "Copy Darwin x86_64 native library (for Intel Mac CI runner)"
    group = "build"
    dependsOn(copyNativeLibDarwinx8664)
}

val copyWindowsNativeLib by tasks.registering {
    description = "Copy Windows native library (for Windows CI runner)"
    group = "build"
    dependsOn(copyNativeLibWindowsx8664)
}

// Include native libs from build directory in resources and sdk-core protos
sourceSets {
    main {
        resources {
            srcDir(nativeLibsDir)
        }
        proto {
            srcDir("rust/sdk-core/crates/common/protos/local")
            srcDir("rust/sdk-core/crates/common/protos/api_upstream")
            srcDir("rust/sdk-core/crates/common/protos/testsrv_upstream")
            // Exclude google protobuf well-known types - use runtime versions instead
            // This prevents version conflicts between generated code and protobuf-java runtime
            exclude("**/google/protobuf/**")
        }
    }
}

// Ensure native lib is built before processing resources (unless pre-built for CI)
// Set -PskipNativeBuild=true to skip native library building (used in CI publish job)
val skipNativeBuild = project.findProperty("skipNativeBuild")?.toString()?.toBoolean() ?: false

tasks.named("processResources") {
    if (!skipNativeBuild) {
        dependsOn(copyNativeLib)
    }
}

// Configure sourcesJar to exclude native libraries (they're resources, not sources)
tasks.matching { it.name == "sourcesJar" }.configureEach {
    this as Jar
    exclude("native/**")
}

// Clean task for Rust artifacts
tasks.register<Delete>("cargoClean") {
    description = "Clean Rust build artifacts"
    group = "build"
    delete("rust/sdk-core/target")
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
}

// Configure Dokka to exclude generated code to prevent OOM
dokka {
    dokkaSourceSets.configureEach {
        // Exclude generated protobuf code from documentation
        suppressedFiles.from(
            fileTree("${layout.buildDirectory.get()}/generated/source/proto"),
        )
    }
}

mavenPublishing {
    coordinates(artifactId = "core-bridge")

    pom {
        name.set("Temporal KT Core Bridge")
        description.set("Kotlin FFM Bridge to Temporal Core SDK")
    }
}
