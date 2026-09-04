package com.surrealdev.temporal.internal

import com.surrealdev.temporal.core.BridgeBuildInfo
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
        verify(
            actualAbi = BridgeBuildInfo.ABI_VERSION,
            requiredAbi = BuildConfig.REQUIRED_BRIDGE_ABI,
            bridgeVersion = BridgeBuildInfo.BRIDGE_VERSION,
            coreVersion = BuildConfig.SDK_VERSION,
        )
        checked = true
    }

    /**
     * The comparison itself, taking its inputs explicitly.
     *
     * [check] reads them from compile-time constants, which are inlined and so cannot be
     * substituted; keeping the logic here is what makes the mismatch path testable rather than
     * only reachable by actually mis-pinning two published artifacts.
     */
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
