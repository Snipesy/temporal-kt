package com.surrealdev.temporal.application

import com.surrealdev.temporal.application.plugin.hooks.ApplicationSetup
import com.surrealdev.temporal.application.plugin.hooks.ApplicationShutdown
import com.surrealdev.temporal.application.plugin.hooks.ApplicationStartupFailed
import com.surrealdev.temporal.application.plugin.hooks.WorkerStarted
import com.surrealdev.temporal.core.TemporalCoreClient
import com.surrealdev.temporal.core.TemporalCoreException
import com.surrealdev.temporal.core.TemporalDevServer
import com.surrealdev.temporal.core.TemporalRuntime
import io.temporal.api.common.v1.WorkflowType
import io.temporal.api.taskqueue.v1.TaskQueue
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApplicationStartupFailureTest {
    @Test
    fun `failed and cancelled startup close workers and resources despite a failing hook`() =
        runBlocking {
            TemporalRuntime.create().use { serverRuntime ->
                TemporalDevServer.start(serverRuntime).use { server ->
                    TemporalCoreClient.connect(serverRuntime, server.targetUrl).use { client ->
                        for (cancelStartup in listOf(false, true)) {
                            val queue = "partial-start-${UUID.randomUUID()}"
                            // Native polling can take this task before the next queue finishes validation.
                            client.workflowServiceCall(
                                "StartWorkflowExecution",
                                StartWorkflowExecutionRequest
                                    .newBuilder()
                                    .setNamespace("default")
                                    .setWorkflowId(queue)
                                    .setWorkflowType(WorkflowType.newBuilder().setName("unregistered"))
                                    .setTaskQueue(TaskQueue.newBuilder().setName(queue))
                                    .setRequestId(UUID.randomUUID().toString())
                                    .build(),
                            ) { StartWorkflowExecutionResponse.parseFrom(it) }
                            val builder = TemporalApplicationBuilder(Dispatchers.Default)
                            builder.connection { target = server.targetUrl }
                            val app = builder.build()
                            app.taskQueue(queue) {}
                            app.taskQueue("second-$queue") {
                                namespace = if (cancelStartup) "default" else "namespace-does-not-exist"
                            }
                            lateinit var runtime: TemporalRuntime
                            lateinit var connection: TemporalCoreClient
                            var failure: Throwable? = null
                            var shutdownCalled = false
                            val startedQueues = mutableListOf<String>()
                            val hookFailure = IllegalStateException("startup failure hook")
                            val entered = CompletableDeferred<Unit>()
                            app.hookRegistry.register(ApplicationSetup) {
                                runtime = it.runtime
                                connection = it.coreClient
                            }
                            app.hookRegistry.register(WorkerStarted) {
                                startedQueues.add(it.taskQueue)
                                if (cancelStartup && it.taskQueue == "second-$queue") {
                                    entered.complete(Unit)
                                    awaitCancellation()
                                }
                            }
                            app.hookRegistry.register(ApplicationStartupFailed) {
                                failure = it.cause
                                throw hookFailure
                            }
                            app.hookRegistry.register(ApplicationShutdown) {
                                delay(1) // Cleanup must also work from a cancelled startup coroutine.
                                shutdownCalled = true
                            }
                            try {
                                withTimeout(10_000) {
                                    if (cancelStartup) {
                                        val startup = launch { app.start() }
                                        entered.await()
                                        startup.cancelAndJoin()
                                        assertIs<CancellationException>(failure)
                                    } else {
                                        val error = assertFailsWith<TemporalCoreException> { app.start() }
                                        assertSame(error, failure)
                                    }
                                }
                                assertTrue(assertNotNull(failure).suppressed.contains(hookFailure))
                                assertTrue(
                                    queue in startedQueues,
                                    "start each consumer before validating the next queue",
                                )
                                assertTrue(runtime.isClosed(), "failed startup must close its runtime")
                                assertTrue(connection.isClosed(), "failed startup must close its client")
                                assertTrue(shutdownCalled, "plugin cleanup must still run")
                                assertTrue(app.health().workers.isEmpty())
                                assertTrue(app.coroutineContext[Job]!!.isCompleted)
                            } finally {
                                // Keep the regression bounded even when run against the broken implementation.
                                runtime.close()
                                withTimeout(5_000) { app.close() }
                            }
                        }
                    }
                }
            }
        }
}
