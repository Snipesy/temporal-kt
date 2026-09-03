package com.surrealdev.temporal.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * Process-lifecycle safety net for [EphemeralServer] instances.
 *
 * Ephemeral servers are real child processes spawned by the Core SDK. Core kills the child
 * when [EphemeralServer.close] runs, but nothing ties the child's lifetime to this JVM: a JVM
 * that dies without closing a server (force-stopped test run, `kill -9`, hard crash) leaves
 * the server running forever. Such an orphan also holds the inherited stdout/stderr pipes of
 * the dead JVM, which makes a Gradle test worker's parent wait for EOF indefinitely.
 *
 * Defenses, all exact (no process-table guessing):
 * 1. Every started server is registered here and unregistered on close. A JVM shutdown hook
 *    closes whatever is still registered, so normal and `System.exit` exits never leave a child.
 * 2. `start` functions are non-cancellable at the native boundary and close the server they
 *    produced if their caller was cancelled meanwhile (see [TemporalDevServer.start]).
 * 3. Each registered server's process is recorded in a per-JVM file under [registryDir] as
 *    `<pid> <processStartMillis>`, keyed by this JVM's own pid and start time. [reapOrphans]
 *    reads the records of JVMs that no longer exist and kills exactly those processes, after
 *    checking the pid still refers to the recorded process (start time match, so a reused pid
 *    is never killed). The test fixture calls it once per JVM.
 */
object EphemeralServers {
    private val logger = LoggerFactory.getLogger(EphemeralServers::class.java)

    private val live: MutableSet<EphemeralServer> = ConcurrentHashMap.newKeySet()
    private val shutdownHookInstalled = AtomicBoolean(false)

    /** Records written by this JVM: server -> (pid, processStartMillis). */
    private val records = LinkedHashMap<EphemeralServer, ProcessRecord>()
    private val recordsLock = Any()

    /**
     * Directory holding one record file per JVM that has started ephemeral servers.
     * Defaults to `<java.io.tmpdir>/temporal-kt/ephemeral-servers`.
     */
    val registryDir: Path =
        Path.of(System.getProperty("java.io.tmpdir"), "temporal-kt", "ephemeral-servers")

    /** Servers started in this JVM that have not been closed yet. */
    fun liveServers(): Set<EphemeralServer> = live.toSet()

    /**
     * Closes every server that is still open. Safe to call repeatedly; errors from individual
     * servers are logged and do not prevent the others from closing.
     */
    fun closeAll() {
        for (server in live.toList()) {
            try {
                server.close()
            } catch (e: Exception) {
                logger.warn("Failed closing ephemeral server {}", server.targetUrl, e)
            } finally {
                unregister(server)
            }
        }
    }

    /**
     * Kills server processes recorded by JVMs that no longer exist.
     *
     * A record file is considered orphaned when the JVM it names is not running, or is a
     * different process that happens to reuse the pid (start time mismatch). Each recorded
     * server process is killed only if it is still the recorded process; records whose
     * identity cannot be verified are left alone. Orphaned record files are deleted.
     *
     * @return the number of processes that were killed
     */
    fun reapOrphans(): Int {
        if (!registryDir.exists()) return 0
        val ownFile = ownRecordFile()
        var killed = 0
        for (file in registryDir.listDirectoryEntries("*$RECORD_SUFFIX")) {
            if (file == ownFile) continue
            val owner = OwnerKey.parse(file.name) ?: continue
            if (owner.isAlive()) continue

            val lines =
                try {
                    file.readLines()
                } catch (_: IOException) {
                    continue
                }
            for (line in lines) {
                val record = ProcessRecord.parse(line) ?: continue
                val process = record.liveProcess() ?: continue
                logger.warn(
                    "Reaping orphaned ephemeral Temporal server pid={} left by dead JVM pid={} ({})",
                    record.pid,
                    owner.pid,
                    process.info().command().orElse("?"),
                )
                if (process.destroyForcibly()) killed++
            }
            try {
                Files.deleteIfExists(file)
            } catch (_: IOException) {
                // Another JVM may be reaping concurrently
            }
        }
        return killed
    }

    internal fun register(server: EphemeralServer) {
        live.add(server)
        if (shutdownHookInstalled.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread(::closeAll, "temporal-ephemeral-server-cleanup"))
        }
        val record = server.pid?.let { ProcessRecord.of(it) }
        if (record == null) {
            logger.debug("Ephemeral server {} has no observable pid; not recorded for orphan reaping", server.targetUrl)
            return
        }
        synchronized(recordsLock) {
            records[server] = record
            writeOwnRecords()
        }
    }

    internal fun unregister(server: EphemeralServer) {
        live.remove(server)
        synchronized(recordsLock) {
            if (records.remove(server) != null) writeOwnRecords()
        }
    }

    /** The record file for this JVM (may not exist yet). */
    internal fun ownRecordFile(): Path = registryDir.resolve(OwnerKey.current().fileName())

    /** Writes (or removes, when empty) this JVM's record file. Caller holds [recordsLock]. */
    private fun writeOwnRecords() {
        val file = ownRecordFile()
        try {
            if (records.isEmpty()) {
                Files.deleteIfExists(file)
                return
            }
            Files.createDirectories(registryDir)
            val tmp = file.resolveSibling(file.name + ".tmp")
            Files.write(tmp, records.values.map { it.serialize() })
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            logger.warn("Failed writing ephemeral server registry {}", file, e)
        }
    }

    /** Identity of a JVM: pid plus start time, so a reused pid is not mistaken for the owner. */
    internal data class OwnerKey(
        val pid: Long,
        val startMillis: Long,
    ) {
        fun fileName(): String = "$pid-$startMillis$RECORD_SUFFIX"

        fun isAlive(): Boolean {
            val process = ProcessHandle.of(pid).orElse(null) ?: return false
            if (!process.isAlive) return false
            val actualStart = process.startMillis()
            // Unknown start time on either side: trust the pid rather than risk killing a live JVM's servers
            return actualStart == null || startMillis == 0L || actualStart == startMillis
        }

        companion object {
            fun current(): OwnerKey {
                val self = ProcessHandle.current()
                return OwnerKey(self.pid(), self.startMillis() ?: 0L)
            }

            fun parse(fileName: String): OwnerKey? {
                val base = fileName.removeSuffix(RECORD_SUFFIX)
                val pid = base.substringBefore('-').toLongOrNull() ?: return null
                val start = base.substringAfter('-', "").toLongOrNull() ?: return null
                return OwnerKey(pid, start)
            }
        }
    }

    /** Identity of a server process: pid plus start time. */
    internal data class ProcessRecord(
        val pid: Long,
        val startMillis: Long,
    ) {
        fun serialize(): String = "$pid $startMillis"

        /**
         * The live process this record refers to, or null if it is gone or the pid now belongs
         * to a different process (or identity cannot be verified).
         */
        fun liveProcess(): ProcessHandle? {
            val process = ProcessHandle.of(pid).orElse(null) ?: return null
            if (!process.isAlive) return null
            val actualStart = process.startMillis() ?: return null
            if (startMillis == 0L || actualStart != startMillis) return null
            return process
        }

        companion object {
            fun of(pid: Long): ProcessRecord? {
                val process = ProcessHandle.of(pid).orElse(null) ?: return null
                return ProcessRecord(pid, process.startMillis() ?: 0L)
            }

            fun parse(line: String): ProcessRecord? {
                val parts = line.trim().split(' ')
                if (parts.size != 2) return null
                val pid = parts[0].toLongOrNull() ?: return null
                val start = parts[1].toLongOrNull() ?: return null
                return ProcessRecord(pid, start)
            }
        }
    }

    private fun ProcessHandle.startMillis(): Long? = info().startInstant().map { it.toEpochMilli() }.orElse(null)

    internal const val RECORD_SUFFIX = ".pids"
}

/**
 * Completes a non-cancellable server start: if the calling coroutine was cancelled while the
 * native start was in flight, closes the freshly started server (nobody else will) and
 * rethrows the cancellation; otherwise returns the server.
 */
internal suspend fun <T : EphemeralServer> T.closeIfCancelled(): T {
    try {
        currentCoroutineContext().ensureActive()
    } catch (e: CancellationException) {
        close()
        throw e
    }
    return this
}
