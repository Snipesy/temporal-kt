import org.gradle.internal.os.OperatingSystem

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(libs.protobufJava)

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
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

// Output directory for native libraries (in build folder, not src)
val nativeLibsDir = layout.buildDirectory.dir("native-libs")

// Native build for current platform
val cargoBuild by tasks.registering(Exec::class) {
    description = "Build Rust native library for current platform ($nativePlatform)"
    group = "build"
    workingDir = file("rust")
    commandLine("cargo", "build", "--release")

    inputs.files(
        fileTree("rust") {
            include("**/*.rs", "Cargo.toml", "Cargo.lock", "build.rs")
        },
    )
    inputs.dir("src/main/proto")
    outputs.file("rust/target/release/${libPrefix}temporal_core_bridge.$libExtension")
}

val copyNativeLib by tasks.registering(Copy::class) {
    description = "Copy native library for current platform to build directory"
    group = "build"
    dependsOn(cargoBuild)

    from("rust/target/release/${libPrefix}temporal_core_bridge.$libExtension")
    into(nativeLibsDir.map { it.dir("native/$nativePlatform") })
}

// Cross-compilation for Linux x86_64 (requires cargo-zigbuild)
val cargoBuildLinuxx8664 by tasks.registering(Exec::class) {
    description = "Build Rust native library for linux-x86_64 (requires cargo-zigbuild)"
    group = "build"
    workingDir = file("rust")
    commandLine("cargo-zigbuild", "build", "--release", "--target", "x86_64-unknown-linux-gnu")

    inputs.files(
        fileTree("rust") {
            include("**/*.rs", "Cargo.toml", "Cargo.lock", "build.rs")
        },
    )
    inputs.dir("src/main/proto")
    outputs.file("rust/target/x86_64-unknown-linux-gnu/release/libtemporal_core_bridge.so")
}

val copyNativeLibLinuxx8664 by tasks.registering(Copy::class) {
    description = "Copy native library for linux-x86_64 to build directory"
    group = "build"
    dependsOn(cargoBuildLinuxx8664)

    from("rust/target/x86_64-unknown-linux-gnu/release/libtemporal_core_bridge.so")
    into(nativeLibsDir.map { it.dir("native/linux-x86_64") })
}

// Cross-compilation for Darwin x86_64 (requires cargo-zigbuild on ARM Mac)
val cargoBuildDarwinx8664 by tasks.registering(Exec::class) {
    description = "Build Rust native library for darwin-x86_64 (requires cargo-zigbuild on ARM)"
    group = "build"
    workingDir = file("rust")
    commandLine("cargo-zigbuild", "build", "--release", "--target", "x86_64-apple-darwin")

    inputs.files(
        fileTree("rust") {
            include("**/*.rs", "Cargo.toml", "Cargo.lock", "build.rs")
        },
    )
    inputs.dir("src/main/proto")
    outputs.file("rust/target/x86_64-apple-darwin/release/libtemporal_core_bridge.dylib")
}

val copyNativeLibDarwinx8664 by tasks.registering(Copy::class) {
    description = "Copy native library for darwin-x86_64 to build directory"
    group = "build"
    dependsOn(cargoBuildDarwinx8664)

    from("rust/target/x86_64-apple-darwin/release/libtemporal_core_bridge.dylib")
    into(nativeLibsDir.map { it.dir("native/darwin-x86_64") })
}

// Build all platforms task
val cargoBuildAll by tasks.registering {
    description = "Build Rust native library for all supported platforms"
    group = "build"
    dependsOn(cargoBuild, cargoBuildLinuxx8664, cargoBuildDarwinx8664)
}

val copyAllNativeLibs by tasks.registering {
    description = "Copy all native libraries to build directory"
    group = "build"
    dependsOn(copyNativeLib, copyNativeLibLinuxx8664, copyNativeLibDarwinx8664)
}

// Include native libs from build directory in resources
sourceSets {
    main {
        resources {
            srcDir(nativeLibsDir)
        }
    }
}

// Ensure native lib is built before processing resources
tasks.named("processResources") {
    dependsOn(copyNativeLib)
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
