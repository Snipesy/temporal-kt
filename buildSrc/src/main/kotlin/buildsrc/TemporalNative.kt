package buildsrc

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem

/**
 * Resolution of the Temporal Core native library for builds inside this repository.
 *
 * The library is published from SurrealDevelopment/temporal-kt-bridge as one classifier artifact
 * per platform, so this build needs no Rust toolchain. `NativeLoader` locates it with
 * `getResourceAsStream("/native/<platform>/<lib>")`, which means a JAR on the classpath resolves
 * exactly as it does for a downstream consumer.
 */
object TemporalNative {
    /**
     * A locally built library to use instead of the published artifact.
     *
     * This is the escape hatch for working on the bridge itself. It is also *required* alongside
     * `-Ptemporal.bridgePath`, because classifier dependencies cannot be substituted by a
     * composite build.
     */
    fun overridePath(project: Project): String? =
        (
            project.providers.gradleProperty("temporal.nativeLib").orNull
                ?: project.providers.environmentVariable("TEMPORAL_KT_NATIVE_LIB").orNull
        )?.let { project.file(it).absolutePath }

    /** The classifier for the machine running the build. */
    fun hostClassifier(): String {
        val os = OperatingSystem.current()
        val arch = System.getProperty("os.arch")
        return when {
            os.isMacOsX && arch == "aarch64" -> "macos-aarch64"
            os.isLinux && arch == "aarch64" -> "linux-aarch64-gnu"
            os.isLinux -> "linux-x86_64-gnu"
            os.isWindows -> "windows-x86_64"
            else -> throw GradleException(
                "No Temporal native library for ${os.name} / $arch. Supported: macos-aarch64, " +
                    "linux-x86_64-gnu, linux-aarch64-gnu, windows-x86_64.",
            )
        }
    }

    /**
     * The classifier artifact for this host. Mirrors settings.gradle.kts: core-bridge is versioned
     * `<bridgeSdkCoreVersion>-<bridgeVersion>`.
     */
    fun coordinate(project: Project): String {
        val bridgeVersion = project.providers.gradleProperty("bridgeVersion").get()
        val sdkCore = project.providers.gradleProperty("bridgeSdkCoreVersion").get()
        return "com.surrealdev.temporal:core-bridge:$sdkCore-$bridgeVersion:${hostClassifier()}"
    }
}
