package com.surrealdev.temporal.core.internal

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Platform-aware native library loader for the Temporal Core bridge.
 *
 * This loader:
 * 1. Detects the current OS and architecture
 * 2. Extracts the appropriate native library from JAR resources
 * 3. Loads the library via System.load()
 */
object NativeLoader {
    private const val LIB_NAME = "temporal_core_bridge"

    @Volatile
    private var loaded = false

    private val platform: Platform by lazy { detectPlatform() }

    /**
     * Loads the native library. Safe to call multiple times.
     *
     * @throws UnsatisfiedLinkError if the library cannot be loaded
     * @throws IllegalStateException if the platform is not supported
     */
    @Synchronized
    fun load() {
        if (loaded) return

        val libFileName = platform.libFileName(LIB_NAME)
        val resourcePath = "/native/${platform.resourceDir}/$libFileName"

        val resourceStream =
            NativeLoader::class.java.getResourceAsStream(resourcePath)
                ?: throw UnsatisfiedLinkError(
                    "Native library not found in JAR: $resourcePath. " +
                        "Make sure the library was built for platform: ${platform.resourceDir}",
                )

        val tempDir = Files.createTempDirectory("temporal-core-bridge").toFile()
        tempDir.deleteOnExit()

        val tempLib = File(tempDir, libFileName)
        tempLib.deleteOnExit()

        resourceStream.use { input ->
            FileOutputStream(tempLib).use { output ->
                input.copyTo(output)
            }
        }

        System.load(tempLib.absolutePath)
        loaded = true
    }

    /**
     * Check if the native library has been loaded.
     */
    fun isLoaded(): Boolean = loaded

    private fun detectPlatform(): Platform {
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        val os =
            when {
                osName.contains("mac") || osName.contains("darwin") -> OS.DARWIN
                osName.contains("linux") -> OS.LINUX
                osName.contains("windows") -> OS.WINDOWS
                else -> throw IllegalStateException("Unsupported operating system: $osName")
            }

        val architecture =
            when (arch) {
                "aarch64", "arm64" -> Arch.AARCH64
                "amd64", "x86_64" -> Arch.X86_64
                else -> throw IllegalStateException("Unsupported architecture: $arch")
            }

        return Platform(os, architecture)
    }

    private enum class OS {
        DARWIN,
        LINUX,
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
        val resourceDir: String
            get() =
                when (os) {
                    OS.DARWIN -> "darwin-${arch.name.lowercase()}"
                    OS.LINUX -> "linux-${arch.name.lowercase()}"
                    OS.WINDOWS -> "windows-${arch.name.lowercase()}"
                }

        fun libFileName(baseName: String): String =
            when (os) {
                OS.DARWIN -> "lib$baseName.dylib"
                OS.LINUX -> "lib$baseName.so"
                OS.WINDOWS -> "$baseName.dll"
            }
    }
}
