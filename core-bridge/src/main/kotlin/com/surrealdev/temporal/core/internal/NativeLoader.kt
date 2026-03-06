package com.surrealdev.temporal.core.internal

import java.io.FileOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import java.nio.file.Files
import java.nio.file.Path

/**
 * Platform-aware native library loader for the Temporal Core bridge.
 *
 * This loader uses Java's Foreign Function & Memory (FFM) API to:
 * 1. Detect the current OS and architecture
 * 2. Extract the appropriate native library from JAR resources
 * 3. Load the library via SymbolLookup for FFM access
 */
object NativeLoader {
    private const val LIB_NAME = "temporalio_sdk_core_c_bridge"

    /**
     * Global arena for the native library's lifetime.
     * Using global arena ensures the library stays loaded for the JVM's lifetime.
     */
    private val arena: Arena = Arena.global()

    @Volatile
    private var symbolLookup: SymbolLookup? = null

    @Volatile
    private var libraryPath: Path? = null

    private val platform: Platform by lazy { detectPlatform() }

    /**
     * Loads the native library and returns a SymbolLookup for accessing symbols.
     * Safe to call multiple times - returns cached lookup after first load.
     *
     * @return SymbolLookup for accessing native functions
     * @throws UnsatisfiedLinkError if the library cannot be loaded
     * @throws IllegalStateException if the platform is not supported
     */
    @Synchronized
    fun load(): SymbolLookup {
        symbolLookup?.let { return it }

        val libFileName = platform.libFileName(LIB_NAME)
        val resourcePath = "/native/${platform.resourceDir}/$libFileName"

        val resourceStream =
            NativeLoader::class.java.getResourceAsStream(resourcePath)
                ?: throw UnsatisfiedLinkError(
                    $$"""
                    Native library not found for platform: $${platform.resourceDir}

                    Add the platform-specific dependency:

                    Gradle (recommended - with osdetector):
                      plugins { id("com.google.osdetector") version "1.7.3" }
                      dependencies {
                        implementation("com.surrealdev.temporal:core-bridge:VERSION")
                        val nativeClassifier = if (osdetector.os == "linux") "${osdetector.classifier}-gnu" else osdetector.classifier
                        runtimeOnly("com.surrealdev.temporal:core-bridge:VERSION:$nativeClassifier")
                      }

                    Or specify the classifier directly:
                      runtimeOnly("com.surrealdev.temporal:core-bridge:VERSION:$${platform.mavenClassifier}")

                    Supported classifiers: linux-x86_64-gnu, linux-aarch64-gnu, macos-x86_64, macos-aarch64, windows-x86_64
                    """.trimIndent(),
                )

        val tempDir = Files.createTempDirectory("temporal-core-bridge")
        val tempLib = tempDir.resolve(libFileName)

        resourceStream.use { input ->
            FileOutputStream(tempLib.toFile()).use { output ->
                input.copyTo(output)
            }
        }

        // Register cleanup on JVM shutdown
        Runtime.getRuntime().addShutdownHook(
            Thread {
                tempLib.toFile().delete()
                tempDir.toFile().delete()
            },
        )

        libraryPath = tempLib

        // Load library ahead of time so FFM bindings work ok
        System.load(tempLib.toAbsolutePath().toString())

        val lookup = SymbolLookup.libraryLookup(tempLib, arena)
        symbolLookup = lookup
        return lookup
    }

    /**
     * Check if the native library has been loaded.
     */
    fun isLoaded(): Boolean = symbolLookup != null

    /**
     * Get the path to the loaded library, or null if not loaded.
     */
    fun getLibraryPath(): Path? = libraryPath

    private fun detectPlatform(): Platform {
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        // Detect Linux libc variant (glibc vs musl)
        // Default to glibc; musl is typically used in Alpine Linux
        val isMusl = osName.contains("linux") && detectMuslLibc()

        val os =
            when {
                osName.contains("mac") || osName.contains("darwin") -> {
                    OS.MACOS
                }

                osName.contains("linux") && isMusl -> {
                    throw IllegalStateException(
                        "Musl libc (Alpine Linux) is not currently supported. " +
                            "Please use a glibc-based Linux distribution (e.g., Debian, Ubuntu). " +
                            "For container deployments, use a glibc-based base image instead of Alpine.",
                    )
                }

                osName.contains("linux") -> {
                    OS.LINUXGNU
                }

                osName.contains("windows") -> {
                    OS.WINDOWS
                }

                else -> {
                    throw IllegalStateException("Unsupported operating system: $osName")
                }
            }

        val architecture =
            when (arch) {
                "aarch64", "arm64" -> Arch.AARCH64
                "amd64", "x86_64" -> Arch.X86_64
                else -> throw IllegalStateException("Unsupported architecture: $arch")
            }

        return Platform(os, architecture)
    }

    /**
     * Detect if the current Linux system uses musl libc (e.g., Alpine Linux).
     * This is a best-effort detection based on common indicators.
     */
    private fun detectMuslLibc(): Boolean {
        // Check for Alpine Linux indicator
        val alpineRelease = java.io.File("/etc/alpine-release")
        if (alpineRelease.exists()) {
            return true
        }

        // Check ldd version output for musl
        return try {
            val process =
                ProcessBuilder("ldd", "--version")
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lowercase().contains("musl")
        } catch (_: Exception) {
            false
        }
    }

    private enum class OS {
        MACOS,
        LINUXGNU,

        // LINUXMUSL,  // Future: Alpine Linux support
        WINDOWS,
    }

    private enum class Arch {
        X86_64,
        AARCH64,
    }

    private data class Platform(
        val os: OS,
        val arch: Arch,
    ) {
        /**
         * Internal resource directory path within the JAR.
         */
        val resourceDir: String
            get() =
                when (os) {
                    OS.MACOS -> "macos-${arch.name.lowercase()}"
                    OS.LINUXGNU -> "linux-${arch.name.lowercase()}-gnu"
                    OS.WINDOWS -> "windows-${arch.name.lowercase()}"
                }

        /**
         * Maven classifier for the platform-specific JAR artifact.
         * Use this when declaring the runtimeOnly dependency.
         */
        val mavenClassifier: String
            get() =
                when (os) {
                    OS.MACOS -> "macos-${arch.name.lowercase()}"
                    OS.LINUXGNU -> "linux-${arch.name.lowercase()}-gnu"
                    OS.WINDOWS -> "windows-${arch.name.lowercase()}"
                }

        fun libFileName(baseName: String): String =
            when (os) {
                OS.MACOS -> "lib$baseName.dylib"
                OS.LINUXGNU -> "lib$baseName.so"
                OS.WINDOWS -> "$baseName.dll"
            }
    }
}
