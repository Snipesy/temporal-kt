package com.surrealdev.temporal.core.internal

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolves the default `pid@hostname` identity reported to the Temporal server, once per JVM,
 * without ever throwing. This is the client-level default, applied at connect time; workers with
 * no identity of their own inherit it from the client, as in sdk-python.
 *
 * `InetAddress.getLocalHost().hostName` is not safe to call unguarded. It takes the name the OS
 * reports for this machine (`gethostname(2)`) and then *resolves* it through the name service, so
 * it throws [UnknownHostException] whenever that name is not resolvable - a container with a bare
 * `/etc/hosts`, a Kubernetes pod, a VPN or DNS outage, a machine renamed while running. Identity
 * is mainly a debugging and traceability label - the server records it in history and shows it in
 * the UI and in `DescribeTaskQueue` poller listings, and it is explicitly not required to be
 * unique (`worker_instance_key` is the unique key). It is not purely cosmetic, though: Core
 * refuses to build a worker on an empty client identity, and derives the sticky task queue name
 * from it as `{identity}-{uuid}`. Either way a lookup failure must never stop a worker starting.
 *
 * Every other Temporal SDK derives this from the *unresolved* `gethostname(2)` value and so cannot
 * fail this way: Core's Rust worker uses the `gethostname` crate, Python uses
 * `socket.gethostname()`, Go uses `os.Hostname()`. The JVM exposes no unresolved equivalent, but
 * it does not have to: when resolution fails, the JDK rethrows as `"<hostname>: <resolver error>"`
 * (see `InetAddress.getLocalHost`), so the OS-reported name survives in the message and can be
 * recovered. That keeps the identity *correct* rather than degraded in exactly the case that
 * currently fails.
 *
 * Sources are tried in order and the first usable one wins:
 *
 *  1. `InetAddress.getLocalHost().hostName`. The returned address already carries the
 *     OS-reported name, so no reverse lookup happens and this agrees with the other SDKs.
 *  2. The hostname recovered from a thrown [UnknownHostException]'s message, as above.
 *  3. The `HOSTNAME` / `COMPUTERNAME` environment variables, set by Docker, Kubernetes and Windows.
 *  4. The literal [UNKNOWN_HOST], so identity is always a well-formed `pid@something`.
 *
 * `RuntimeMXBean.name` is deliberately not used as a fallback: OpenJDK implements it as
 * `pid@InetAddress.getLocalHost().getHostName()` with the literal `"localhost"` substituted on
 * failure, so it can only ever return a value already tried above or a misleading one.
 *
 * Note that step 1 performs a name service lookup and can block for the resolver timeout when DNS
 * is unreachable. It runs at most once per JVM, on first access; pass an explicit
 * `workerIdentity` to skip it entirely.
 */
public object DefaultIdentity {
    /** Host component used when nothing else could be determined. */
    public const val UNKNOWN_HOST: String = "unknown-host"

    /** Upper bound on the resolved host component, since candidates include arbitrary env values. */
    private const val MAX_HOST_LENGTH = 255

    /**
     * The default worker identity, in `pid@hostname` form. Computed on first access and cached for
     * the lifetime of the JVM. Never throws.
     */
    public val value: String by lazy { "${ProcessHandle.current().pid()}@$hostname" }

    /**
     * The best available hostname for this machine. Computed on first access and cached for the
     * lifetime of the JVM. Falls back to [UNKNOWN_HOST] rather than throwing.
     */
    public val hostname: String by lazy {
        resolveHostname(
            localHost = { InetAddress.getLocalHost().hostName },
            env = System::getenv,
        )
    }

    /**
     * Hostname resolution with its sources injected, so each fallback step is testable without a
     * hostile DNS setup. Both sources are allowed to throw; a throwing source is treated as having
     * produced nothing, apart from [UnknownHostException] which is mined for the hostname first.
     */
    internal fun resolveHostname(
        localHost: () -> String,
        env: (String) -> String?,
    ): String =
        fromLocalHost(localHost)
            ?: fromEnv(env, "HOSTNAME")
            ?: fromEnv(env, "COMPUTERNAME")
            ?: UNKNOWN_HOST

    private fun fromLocalHost(localHost: () -> String): String? =
        runCatching { localHost() }
            .recoverCatching { failure ->
                // The JDK rethrows an unresolvable local hostname as "<hostname>: <resolver
                // error>", so the OS-reported name is still in there even though DNS refused it.
                (failure as? UnknownHostException)?.message?.substringBefore(':') ?: throw failure
            }.getOrNull()
            ?.let(::sanitize)

    private fun fromEnv(
        env: (String) -> String?,
        name: String,
    ): String? = runCatching { env(name) }.getOrNull()?.let(::sanitize)

    /**
     * Returns [raw] as a usable host component, or null if it cannot be one. Whitespace and
     * control characters are rejected rather than stripped: a real hostname contains neither, so
     * their presence means the candidate is not a hostname at all - most likely a resolver error
     * message that survived step 2 because it carried no colon.
     */
    private fun sanitize(raw: String): String? {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.any { it.isWhitespace() || it.isISOControl() } -> null
            else -> trimmed.take(MAX_HOST_LENGTH)
        }
    }
}
