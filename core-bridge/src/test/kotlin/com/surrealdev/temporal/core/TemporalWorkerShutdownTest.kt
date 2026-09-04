package com.surrealdev.temporal.core

import com.google.protobuf.ByteString
import coresdk.CoreInterface
import coresdk.activity_task.ActivityTaskOuterClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bridge calls made after the native worker has been finalized must fail with an error, not
 * abort the process. Before the fix, `temporal_core_worker_record_activity_heartbeat` unwrapped
 * the already-taken worker and panicked across the FFI boundary (SIGABRT of the whole JVM).
 */
class TemporalWorkerShutdownTest {
    private fun heartbeat(): CoreInterface.ActivityHeartbeat =
        CoreInterface.ActivityHeartbeat
            .newBuilder()
            .setTaskToken(ByteString.copyFromUtf8("stale-token"))
            .build()

    @Test
    fun `heartbeat after awaitShutdown throws instead of aborting the JVM`() =
        runBlocking<Unit> {
            TemporalRuntime.create().use { runtime ->
                TemporalDevServer.start(runtime).use { server ->
                    TemporalCoreClient.connect(runtime, server.targetUrl, "default").use { client ->
                        val worker = TemporalWorker.create(runtime, client, "shutdown-test-queue", "default")

                        // Core only shuts down cleanly once polling has started
                        val poller =
                            launch {
                                worker.pollActivityTask { ActivityTaskOuterClass.ActivityTask.parseFrom(it) }
                            }
                        delay(300.milliseconds)

                        worker.recordActivityHeartbeat(heartbeat()) // live worker: fine (unknown token is ignored)

                        worker.initiateShutdown()
                        poller.join()
                        worker.awaitShutdown()
                        assertTrue(worker.isShutdownFinalized())

                        // The native worker is gone now. This used to be a process abort.
                        assertFailsWith<TemporalCoreException> { worker.recordActivityHeartbeat(heartbeat()) }

                        worker.close()
                        assertFailsWith<IllegalStateException> { worker.recordActivityHeartbeat(heartbeat()) }
                    }
                }
            }
        }

    /**
     * A zombie activity thread may still be heartbeating while the worker is closed and its
     * native handle freed. Every such heartbeat must fail with an exception; none may reach a
     * freed handle (which would be a use-after-free, i.e. a process crash).
     */
    @Test
    fun `heartbeats racing close never touch the freed native handle`() =
        runBlocking<Unit> {
            TemporalRuntime.create().use { runtime ->
                TemporalDevServer.start(runtime).use { server ->
                    TemporalCoreClient.connect(runtime, server.targetUrl, "default").use { client ->
                        val worker = TemporalWorker.create(runtime, client, "shutdown-race-queue", "default")
                        val poller =
                            launch { worker.pollActivityTask { ActivityTaskOuterClass.ActivityTask.parseFrom(it) } }
                        delay(300.milliseconds)
                        worker.initiateShutdown()
                        poller.join()
                        worker.awaitShutdown()

                        val stop = AtomicBoolean(false)
                        val unexpected = ConcurrentHashMap.newKeySet<String>()
                        val hammers =
                            List(4) {
                                thread {
                                    while (!stop.get()) {
                                        try {
                                            worker.recordActivityHeartbeat(heartbeat())
                                        } catch (_: TemporalCoreException) {
                                        } catch (_: IllegalStateException) {
                                        } catch (t: Throwable) {
                                            unexpected.add(t.toString())
                                        }
                                    }
                                }
                            }
                        delay(100.milliseconds)
                        worker.close() // frees the native handle while the hammers run
                        delay(200.milliseconds)
                        stop.set(true)
                        hammers.forEach { it.join(5_000) }

                        assertTrue(unexpected.isEmpty(), "unexpected exceptions: $unexpected")
                        assertTrue(worker.isClosed())
                    }
                }
            }
        }
}
