package com.surrealdev.temporal.internal

import com.surrealdev.temporal.core.BridgeBuildInfo
import com.surrealdev.temporal.core.TemporalCoreException
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BridgeCompatibilityTest {
    @Test
    fun `the runtime bridge is checked instead of inlined build-time constants`() {
        val directory = Files.createTempDirectory("bridge-compatibility").toFile()
        try {
            val source = directory.resolve("BridgeBuildInfo.java")
            source.writeText(
                """
                package com.surrealdev.temporal.core;
                public class BridgeBuildInfo {
                    public static final int ABI_VERSION = 99;
                    public static final String BRIDGE_VERSION = "runtime-bridge";
                }
                """.trimIndent(),
            )
            assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.path, source.path),
            )
            val guardName = BridgeCompatibility::class.java.name
            val infoName = BridgeBuildInfo::class.java.name
            val urls =
                arrayOf(directory.toURI().toURL(), BridgeCompatibility::class.java.protectionDomain.codeSource.location)
            val loader =
                object : URLClassLoader(urls, javaClass.classLoader) {
                    override fun loadClass(
                        name: String,
                        resolve: Boolean,
                    ): Class<*> {
                        if (name != guardName && name != infoName) return super.loadClass(name, resolve)
                        return (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                    }
                }
            loader.use {
                val guard = it.loadClass(guardName)
                val error =
                    assertFailsWith<InvocationTargetException> {
                        guard
                            .getMethod(
                                "check",
                            ).invoke(guard.getField("INSTANCE").get(null))
                    }
                val cause = assertIs<TemporalCoreException>(error.cause)
                assertContains(requireNotNull(cause.message), "provides ABI 99")
                assertContains(requireNotNull(cause.message), "runtime-bridge")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

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
