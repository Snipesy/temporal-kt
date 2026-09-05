package com.surrealdev.temporal.client.internal

import com.surrealdev.temporal.core.TlsConfig

/** Resolve the SDK's TLS settings before the bridge derives TLS from the URL scheme. */
internal fun connectionTarget(
    target: String,
    tls: TlsConfig?,
    apiKey: String?,
    tlsDisabled: Boolean,
): String {
    val https = target.startsWith("https://", ignoreCase = true)
    val address = if (https || target.startsWith("http://", ignoreCase = true)) target.substringAfter("://") else target
    val useTls = !tlsDisabled && (https || tls != null || apiKey != null)
    return "${if (useTls) "https" else "http"}://$address"
}
