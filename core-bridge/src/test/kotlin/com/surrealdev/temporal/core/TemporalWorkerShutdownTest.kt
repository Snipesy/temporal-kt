package com.surrealdev.temporal.core

import com.google.protobuf.ByteString
import coresdk.CoreInterface
import coresdk.activity_task.ActivityTaskOuterClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
}
