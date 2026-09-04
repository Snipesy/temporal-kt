package com.surrealdev.temporal.core

/**
 * Build-time identity of the `core-bridge` artifact, readable by other modules.
 *
 * `core-bridge` and `core` are published on independent versions -- the bridge carries a
 * composite `<sdkCoreVersion>-<version>` coordinate because its content is tied to a Temporal
 * SDK-Core release. That means a consumer can pin a `core` and a `core-bridge` that were never
 * built together. [ABI_VERSION] exists so `core` can detect that at startup and say so, rather
 * than failing later with a `NoSuchMethodError` from somewhere deep in worker construction.
 *
 * This is diagnostic surface, not API: treat the values as opaque and do not branch on them.
 */
public object BridgeBuildInfo {
    /**
     * Compatibility number for the `com.surrealdev.temporal.core.*` seam that `core` consumes.
     *
     * Bumped only on a breaking change to that seam, independently of any version string.
     */
    public const val ABI_VERSION: Int = BuildConfig.ABI_VERSION

    /** This artifact's own version, `<sdkCoreVersion>-<temporal-kt version>`. */
    public const val BRIDGE_VERSION: String = BuildConfig.BRIDGE_VERSION

    /** The Temporal SDK-Core (Rust) release this bridge was built against. */
    public const val SDK_CORE_VERSION: String = BuildConfig.SDK_CORE_VERSION
}
