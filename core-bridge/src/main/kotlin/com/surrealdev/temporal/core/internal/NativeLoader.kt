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
    private const val LIB_NAME = "temporal_core_bridge"

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
                    "Native library not found in JAR: $resourcePath. " +
                        "Make sure the library was built for platform: ${platform.resourceDir}",
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
