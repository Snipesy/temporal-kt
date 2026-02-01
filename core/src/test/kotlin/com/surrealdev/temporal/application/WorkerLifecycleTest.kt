package com.surrealdev.temporal.application

import com.surrealdev.temporal.core.TemporalDevServer
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.testing.runTemporalTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

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
    fun `application without task queues starts successfully`() =
        runTemporalTest {
            application {
                // No task queues - just verify connection works
            }
        }
}
