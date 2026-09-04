package com.surrealdev.temporal.internal

import com.surrealdev.temporal.core.BridgeBuildInfo
import com.surrealdev.temporal.core.TemporalCoreException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class BridgeCompatibilityTest {
    @Test
    fun `the core-bridge actually on the classpath is compatible with this core`() {
        // Guards the real pairing, so a bridgeAbi bump that forgets one side fails here.
        BridgeCompatibility.check()
    }

    @Test
    fun `a bridge reporting a different ABI is rejected`() {
        val error =
            assertFailsWith<TemporalCoreException> {
                BridgeCompatibility.verify(
                    actualAbi = 99,
                    requiredAbi = 1,
                    bridgeVersion = "0.9.0-1.2.3",
                    coreVersion = "1.2.3",
                )
            }
        // The message has to name both sides and the versions, or it is no better than the
        // NoSuchMethodError it replaces.
        val message = requireNotNull(error.message)
        assertContains(message, "requires bridge ABI 1")
        assertContains(message, "provides ABI 99")
        assertContains(message, "0.9.0-1.2.3")
        assertContains(message, "bom")
    }

    @Test
    fun `matching ABIs are accepted`() {
        BridgeCompatibility.verify(
            actualAbi = BridgeBuildInfo.ABI_VERSION,
            requiredAbi = BridgeBuildInfo.ABI_VERSION,
            bridgeVersion = BridgeBuildInfo.BRIDGE_VERSION,
            coreVersion = "irrelevant",
        )
    }
}
