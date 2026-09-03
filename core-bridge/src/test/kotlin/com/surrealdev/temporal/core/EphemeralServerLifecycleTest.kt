package com.surrealdev.temporal.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Process-lifecycle guarantees for ephemeral servers: no server process may outlive the
 * code path that started it, and orphans left behind by a dead JVM are reaped exactly.
 *
 * These tests use the auto-downloaded Temporal CLI (cached after the first run).
 */
class EphemeralServerLifecycleTest {
    private fun isRunning(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private suspend fun awaitCondition(
        timeout: Duration = 20.seconds,
        message: String,
        condition: () -> Boolean,
    ) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (start.elapsedNow() > timeout) throw AssertionError("Timed out waiting for: $message")
            delay(100.milliseconds)
        }
    }

    @Test
    fun `pid identifies the server process, a child of this JVM, and is null after close`() =
        runBlocking {
            TemporalRuntime.create().use { runtime ->
                val server = TemporalDevServer.start(runtime)
                val pid = assertNotNull(server.pid)
                val process = ProcessHandle.of(pid).orElseThrow()
                assertEquals(ProcessHandle.current().pid(), process.parent().orElseThrow().pid())

                server.close()
                assertNull(server.pid)
                awaitCondition(message = "server process to exit") { !isRunning(pid) }
            }
        }

    @Test
    fun `cancelling start while the native start is in flight does not leak the server process`() =
        runBlocking {
            TemporalRuntime.create().use { runtime ->
                var pid: Long? = null
                // UNDISPATCHED runs the body synchronously up to its first suspension point: the
                // native start has been issued and is now in flight when we cancel.
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        // If start returns anyway, close what it returned so the only possible
                        // leak is the one under test.
                        TemporalDevServer.start(runtime).use { pid = it.pid }
                    }
                job.cancelAndJoin()

                assertTrue(EphemeralServers.liveServers().isEmpty(), "no server may remain registered")
                pid?.let { awaitCondition(message = "server process to exit") { !isRunning(it) } }
                // Whether or not start() got to return, no server may be a live child of this JVM.
                assertTrue(ProcessHandle.current().children().noneMatch { isEphemeralServer(it) })
            }
        }

    private fun isEphemeralServer(process: ProcessHandle): Boolean =
        // Test-only identification: our own direct children carrying the dev-server subcommand.
        process
            .info()
            .arguments()
            .map { it.contains("start-dev") }
            .orElse(false)

    @Test
    fun `closeAll closes servers that were never closed by their owner`() =
        runBlocking {
            TemporalRuntime.create().use { runtime ->
                val server = TemporalDevServer.start(runtime)
                val pid = assertNotNull(server.pid)
                assertTrue(server in EphemeralServers.liveServers(), "started server must be registered")

                EphemeralServers.closeAll()

                assertTrue(server.isClosed())
                assertTrue(EphemeralServers.liveServers().isEmpty())
                awaitCondition(message = "server process reaped") { !isRunning(pid) }
            }
        }

    @Test
    fun `a live server is recorded in this JVM's registry file and removed on close`() =
        runBlocking {
            TemporalRuntime.create().use { runtime ->
                val server = TemporalDevServer.start(runtime)
                val pid = assertNotNull(server.pid)
                val file = EphemeralServers.ownRecordFile()
                assertTrue(file.exists(), "registry file $file should exist while a server is live")
                val record = file.readLines().mapNotNull { EphemeralServers.ProcessRecord.parse(it) }.single()
                assertEquals(pid, record.pid)
                assertEquals(
                    ProcessHandle
                        .of(pid)
                        .orElseThrow()
                        .info()
                        .startInstant()
                        .get()
                        .toEpochMilli(),
                    record.startMillis,
                )

                server.close()
                assertFalse(file.exists(), "registry file should be removed once no server is live")
            }
        }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `reapOrphans kills servers recorded by a dead JVM and spares those of live JVMs`() =
        runBlocking {
            TemporalRuntime.create().use { runtime ->
                TemporalDevServer.start(runtime).use { live ->
                    val livePid = assertNotNull(live.pid)
                    val binary =
                        ProcessHandle
                            .of(livePid)
                            .orElseThrow()
                            .info()
                            .command()
                            .orElseThrow()
                    val port = ServerSocket(0).use { it.localPort }

                    // Spawn the same binary from a shell that exits immediately, leaving the server
                    // with no owner - what a killed test JVM leaves behind. The shell prints the pid.
                    val shell =
                        ProcessBuilder(
                            "sh",
                            "-c",
                            "nohup \"$0\" server start-dev --ip 127.0.0.1 --port $port --headless " +
                                "--log-level error >/dev/null 2>&1 & echo $!",
                            binary,
                        ).redirectErrorStream(true).start()
                    val orphanPid =
                        shell.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                            .toLong()
                    shell.waitFor()
                    awaitCondition(message = "orphan to be running") { isRunning(orphanPid) }

                    try {
                        // Record it under a JVM identity that does not exist
                        val deadOwner = EphemeralServers.OwnerKey(pid = deadPid(), startMillis = 1L)
                        val orphanStart =
                            ProcessHandle
                                .of(orphanPid)
                                .orElseThrow()
                                .info()
                                .startInstant()
                                .get()
                                .toEpochMilli()
                        Files.createDirectories(EphemeralServers.registryDir)
                        val deadFile = EphemeralServers.registryDir.resolve(deadOwner.fileName())
                        Files.write(
                            deadFile,
                            listOf(EphemeralServers.ProcessRecord(orphanPid, orphanStart).serialize()),
                        )

                        val killed = EphemeralServers.reapOrphans()

                        assertEquals(1, killed, "exactly the orphan should be reaped")
                        awaitCondition(message = "orphan to exit") { !isRunning(orphanPid) }
                        assertFalse(deadFile.exists(), "orphaned record file should be deleted")
                        assertTrue(isRunning(livePid), "server owned by this live JVM must survive")
                        assertTrue(EphemeralServers.ownRecordFile().exists(), "own record file must survive")
                        assertFalse(live.isClosed())
                    } finally {
                        ProcessHandle.of(orphanPid).ifPresent { it.destroyForcibly() }
                    }
                }
            }
        }

    @Test
    fun `reapOrphans does not kill a process whose pid was reused`() =
        runBlocking {
            TemporalRuntime.create().use { runtime ->
                TemporalDevServer.start(runtime).use { live ->
                    val livePid = assertNotNull(live.pid)
                    // A record naming the live server's pid but a different start time must be ignored
                    val deadOwner = EphemeralServers.OwnerKey(pid = deadPid(), startMillis = 1L)
                    Files.createDirectories(EphemeralServers.registryDir)
                    val deadFile = EphemeralServers.registryDir.resolve(deadOwner.fileName())
                    Files.write(deadFile, listOf(EphemeralServers.ProcessRecord(livePid, 12345L).serialize()))

                    val killed = EphemeralServers.reapOrphans()

                    assertEquals(0, killed)
                    assertTrue(isRunning(livePid), "a pid with a different start time is not our process")
                    assertFalse(deadFile.exists(), "stale record file is still cleaned up")
                }
            }
        }

    /** A pid that no running process has. */
    private fun deadPid(): Long = (99_000L downTo 2L).first { ProcessHandle.of(it).isEmpty }
}
