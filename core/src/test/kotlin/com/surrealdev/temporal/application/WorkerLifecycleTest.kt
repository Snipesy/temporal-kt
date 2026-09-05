package com.surrealdev.temporal.application

import com.surrealdev.temporal.application.worker.WorkerStatus
import com.surrealdev.temporal.core.TemporalCoreException
import com.surrealdev.temporal.core.TemporalDevServer
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.testing.runTemporalTest
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Integration tests for worker lifecycle management.
 *
 * These tests use the dev server for end-to-end testing.
 */
class WorkerLifecycleTest {
    @Test
    fun `can start and stop worker with dev server`() =
        runTemporalTest {
            application {
                taskQueue("test-queue") {
                    // Empty task queue for now - just testing lifecycle
                }
            }
        }

    @Test
    fun `can start application with multiple task queues`() =
        runTemporalTest {
            application {
                taskQueue("queue-1") {
                }
                taskQueue("queue-2") {}
                taskQueue("queue-3") {}
            }
        }

    @Test
    fun `can start application with namespace override`() =
        runTemporalTest {
            application {
                taskQueue("queue-with-override") {
                    namespace = "default" // Override with same namespace for now
                }
            }
        }

    @Test
    fun `embeddedTemporal starts and stops workers`() =
        runBlocking {
            TemporalRuntime.create().use { runtime ->
                TemporalDevServer.start(runtime).use { devServer ->
                    val embedded =
                        embeddedTemporal(
                            configure = {
                                connection {
                                    target = "http://${devServer.targetUrl}"
                                    namespace = "default"
                                }
                            },
                            module = {
                                taskQueue("embedded-queue") {
                                    // Empty task queue
                                }
                            },
                        )
                    embedded.start(wait = false)
                    delay(100)
                    embedded.stop()
                }
            }
        }

    @Test
    fun `terminal workflow and activity poll errors fail the worker`() =
        runBlocking {
            TemporalRuntime.create().use { runtime ->
                TemporalDevServer.start(runtime).use { devServer ->
                    val failures = Channel<Throwable>(Channel.UNLIMITED)
                    val handler = CoroutineExceptionHandler { _, failure -> failures.trySend(failure) }
                    val builder = TemporalApplicationBuilder(Dispatchers.Default + handler)
                    builder.connection { target = "http://${devServer.targetUrl}" }
                    val app = builder.build()
                    val streams =
                        mapOf("workflow-failure" to "workflowActivations", "activity-failure" to "activityTasks")
                    streams.keys.forEach { queue -> app.taskQueue(queue) {} }
                    try {
                        app.start()
                        val workers = field(app, "workers") as Map<*, *>
                        for ((queue, name) in streams) {
                            val worker = requireNotNull(workers[queue])
                            val stream = field(field(field(worker, "coreWorker"), "kt"), name)
                            val failure = TemporalCoreException("terminal $queue poll failure", statusCode = -7)
                            // Inject malformed protobuf followed by terminal closure at the actual receive boundary.
                            // Parsing errors must remain task errors; only the terminal Core failure fails the worker.
                            stream.javaClass.getMethod("send", ByteArray::class.java).invoke(stream, byteArrayOf(-128))
                            stream.javaClass.getMethod("close", Throwable::class.java).invoke(stream, failure)
                            withTimeout(5_000) {
                                assertSame(failure, failures.receive())
                                while (app.workerHealth(queue)?.status != WorkerStatus.FAILED) delay(10)
                            }
                        }
                        assertTrue(failures.tryReceive().isFailure)
                    } finally {
                        app.close()
                        failures.close()
                    }
                }
            }
        }

    private fun field(
        target: Any,
        name: String,
    ): Any =
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(target)

    @Test
    fun `application without task queues starts successfully`() =
        runTemporalTest {
            application {
                // No task queues - just verify connection works
            }
        }
}
