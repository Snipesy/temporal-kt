package com.surrealdev.temporal.internal

import com.surrealdev.temporal.core.TemporalCoreException

/**
 * Verifies that the `core-bridge` on the classpath matches the one this `core` was built against.
 *
 * `core` and `core-bridge` are separate artifacts on independent versions -- the bridge is
 * published as `<sdkCoreVersion>-<temporal-kt version>` because its content tracks a Temporal
 * SDK-Core release. Nothing stops a consumer from pinning the two at incompatible versions, and
 * without this check the symptom is a `NoSuchMethodError` or `NoClassDefFoundError` raised
 * somewhere inside runtime or worker construction, which reads as an SDK bug rather than as a
 * dependency problem.
 */
internal object BridgeCompatibility {
    @Volatile
    private var checked = false

    /**
     * Throws [TemporalCoreException] if the bridge on the classpath reports a different ABI than
     * the one this module was compiled against. Cheap and idempotent; safe to call on every
     * application start.
     */
    fun check() {
        if (checked) return
        try {
            // The bridge exposes const vals. Read the loaded fields reflectively so Kotlin
            // cannot inline the build-time bridge's identity into this check.
            val info = Class.forName("com.surrealdev.temporal.core.BridgeBuildInfo", true, javaClass.classLoader)
            verify(
                actualAbi = info.getField("ABI_VERSION").getInt(null),
                requiredAbi = BuildConfig.REQUIRED_BRIDGE_ABI,
                bridgeVersion = info.getField("BRIDGE_VERSION").get(null) as String,
                coreVersion = BuildConfig.SDK_VERSION,
            )
        } catch (e: ReflectiveOperationException) {
            throw TemporalCoreException(
                "Cannot read the loaded core-bridge ABI. core ${BuildConfig.SDK_VERSION} requires " +
                    "bridge ABI ${BuildConfig.REQUIRED_BRIDGE_ABI}. Import com.surrealdev.temporal:bom " +
                    "to select compatible artifacts.",
                cause = e,
            )
        }
        checked = true
    }

    /** Compares the runtime bridge's identity with this module's build-time requirement. */
    fun verify(
        actualAbi: Int,
        requiredAbi: Int,
        bridgeVersion: String,
        coreVersion: String,
    ) {
        if (actualAbi == requiredAbi) return
        throw TemporalCoreException(
            "Incompatible com.surrealdev.temporal:core-bridge on the classpath.\n" +
                "  core $coreVersion requires bridge ABI $requiredAbi\n" +
                "  core-bridge $bridgeVersion provides ABI $actualAbi\n" +
                "\n" +
                "core-bridge is versioned as <sdk-core version>-<temporal-kt version> and does " +
                "not share core's version. Import com.surrealdev.temporal:bom and omit the " +
                "versions, or apply the com.surrealdev.temporal Gradle plugin, to get a " +
                "consistent set.",
        )
    }
}
