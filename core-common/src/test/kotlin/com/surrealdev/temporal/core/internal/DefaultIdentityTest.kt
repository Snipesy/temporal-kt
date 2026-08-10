package com.surrealdev.temporal.core.internal

import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The point of [DefaultIdentity] is that it cannot fail and still yields the same hostname the
 * other Temporal SDKs would report, so these tests cover the fallback chain that holds when the
 * name service refuses to resolve this machine's own name.
 */
class DefaultIdentityTest {
    private val noEnv: (String) -> String? = { null }

    @Test
    fun `real identity is pid at hostname`() {
        val identity = DefaultIdentity.value
        val pid = ProcessHandle.current().pid()

        assertEquals("$pid@${DefaultIdentity.hostname}", identity)
        assertTrue(identity.startsWith("$pid@"), "identity should lead with the current pid: $identity")
        assertFalse(DefaultIdentity.hostname.isEmpty(), "hostname should never resolve to empty")
    }

    @Test
    fun `prefers the resolved local hostname`() {
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { "from-lookup" },
                env = { "from-env" },
            )

        assertEquals("from-lookup", host)
    }

    @Test
    fun `recovers the real hostname from an unresolvable lookup`() {
        // Exactly the message shape InetAddress.getLocalHost() rethrows: "<hostname>: <error>".
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { throw UnknownHostException("my-pod-7f4b: Name or service not known") },
                env = { "from-env" },
            )

        assertEquals("my-pod-7f4b", host)
    }

    @Test
    fun `recovers a bare hostname from a colonless message`() {
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { throw UnknownHostException("my-pod-7f4b") },
                env = { "from-env" },
            )

        assertEquals("my-pod-7f4b", host)
    }

    @Test
    fun `falls back to env when the recovered message is not a hostname`() {
        // A resolver message with no colon is not a hostname; whitespace gives it away.
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { throw UnknownHostException("Name or service not known") },
                env = { name -> if (name == "HOSTNAME") "from-env" else null },
            )

        assertEquals("from-env", host)
    }

    @Test
    fun `falls back to COMPUTERNAME when HOSTNAME is unset`() {
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { throw UnknownHostException() },
                env = { name -> if (name == "COMPUTERNAME") "from-windows" else null },
            )

        assertEquals("from-windows", host)
    }

    @Test
    fun `a lookup failure other than unknown host is skipped rather than propagated`() {
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { error("network stack unavailable") },
                env = { name -> if (name == "HOSTNAME") "from-env" else null },
            )

        assertEquals("from-env", host)
    }

    @Test
    fun `resolves to unknown host when every source fails`() {
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { throw UnknownHostException("Name or service not known") },
                env = { error("env unavailable") },
            )

        assertEquals(DefaultIdentity.UNKNOWN_HOST, host)
    }

    @Test
    fun `rejects blank and control character hosts`() {
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { "   " },
                env = { name -> if (name == "HOSTNAME") "bad\nhost" else "good-host" },
            )

        assertEquals("good-host", host)
    }

    @Test
    fun `trims and caps absurdly long hosts`() {
        val host =
            DefaultIdentity.resolveHostname(
                localHost = { "  " + "h".repeat(1000) + "  " },
                env = noEnv,
            )

        assertEquals("h".repeat(255), host)
    }
}
