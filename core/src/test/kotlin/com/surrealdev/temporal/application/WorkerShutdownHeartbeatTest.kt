package com.surrealdev.temporal.application

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.heartbeat
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskContext
import com.surrealdev.temporal.application.plugin.hooks.ActivityTaskStarted
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.exceptions.ActivityCancelledException
import com.surrealdev.temporal.core.TemporalDevServer
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.workflow.ActivityCancellationType
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Stopping a worker while an activity is still heartbeating must be a clean stop.
 *
 * Before the fix, the forced phase of shutdown cancelled the coroutine awaiting the activity
 * thread but left the thread itself running; its next heartbeat reached the Core bridge after
 * the native worker had been finalized, which panicked across the FFI boundary and aborted the
 * JVM (SIGABRT, Gradle exit 134).
 */
@Tag("integration")
class WorkerShutdownHeartbeatTest {
    /** Heartbeats forever, the way an activity that wants to observe cancellation does. */
    class LoopingActivity {
        @Activity("heartbeatForever")
        suspend fun ActivityContext.heartbeatForever(label: String): String {
            try {
                while (true) {
                    heartbeat(label)
                    firstHeartbeat.complete(Unit)
                    delay(200.milliseconds)
                }
            } catch (e: Throwable) {
                observed.complete(e)
                throw e
            }
        }

        companion object {
            val firstHeartbeat = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable>()
        }
    }

    @Workflow("HeartbeatForeverWorkflow")
    class HeartbeatForeverWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            startActivity<String>(
                activityType = "heartbeatForever",
                arg = "tick",
                options =
                    ActivityOptions(
                        startToCloseTimeout = 5.minutes,
                        // Long heartbeat timeout: Core throttles heartbeat RPCs to 0.8x this value,
                        // so a server-side cancel would take ~24s to reach the activity - far longer
                        // than the worker's grace period below.
                        heartbeatTimeout = 30.seconds,
                        cancellationType = ActivityCancellationType.TRY_CANCEL,
                    ),
            ).result()
    }

    /** Same shape, separate state, for the second scenario. */
    class HeldActivity {
        @Activity("heldActivity")
        suspend fun ActivityContext.heldActivity(label: String): String {
            heartbeat(label)
            return label
        }
    }

    @Workflow("HeldActivityWorkflow")
    class HeldActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            startActivity<String>(
                activityType = "heldActivity",
                arg = "tick",
                options = ActivityOptions(startToCloseTimeout = 5.minutes, heartbeatTimeout = 30.seconds),
            ).result()
    }

    /**
     * An activity task that Core has handed out but that has not reached its thread yet (held in
     * an ActivityTaskStarted hook here; a slow slot acquisition or codec in real life) is
     * invisible to the dispatcher's running-activity table. Forced shutdown must still report
     * its completion to Core, otherwise Core's activity stream never ends and stop() hangs.
     */
    @Test
    fun `stopping while an activity task is held before its thread starts still completes the stop`() =
        runBlocking<Unit> {
            val hookEntered = CompletableDeferred<Unit>()
            TemporalRuntime.create().use { runtime ->
                TemporalDevServer.start(runtime).use { devServer ->
                    val queue = "shutdown-held-${UUID.randomUUID()}"
                    val embedded =
                        embeddedTemporal(
                            configure = {
                                connection {
                                    target = "http://${devServer.targetUrl}"
                                    namespace = "default"
                                }
                            },
                            module = {
                                taskQueue(queue) {
                                    shutdownGracePeriodMs = 500
                                    workflow<HeldActivityWorkflow>()
                                    activity(HeldActivity())
                                    hookRegistry.register(ActivityTaskStarted) { _: ActivityTaskContext ->
                                        hookEntered.complete(Unit)
                                        awaitCancellation() // hold the task before its thread is created
                                    }
                                }
                            },
                        )
                    embedded.start(wait = false)

                    val client = embedded.application.client()
                    client.startWorkflow(workflowType = "HeldActivityWorkflow", taskQueue = queue)
                    withTimeout(30.seconds) { hookEntered.await() }

                    withTimeout(30.seconds) { embedded.stop() }
                }
            }
        }

    @Test
    fun `stopping the worker while an activity heartbeats does not crash and cancels the activity`() =
        runBlocking<Unit> {
            TemporalRuntime.create().use { runtime ->
                TemporalDevServer.start(runtime).use { devServer ->
                    val queue = "shutdown-heartbeat-${UUID.randomUUID()}"
                    val embedded =
                        embeddedTemporal(
                            configure = {
                                connection {
                                    target = "http://${devServer.targetUrl}"
                                    namespace = "default"
                                }
                            },
                            module = {
                                taskQueue(queue) {
                                    shutdownGracePeriodMs = 500
                                    workflow<HeartbeatForeverWorkflow>()
                                    activity(LoopingActivity())
                                }
                            },
                        )
                    embedded.start(wait = false)

                    val client = embedded.application.client()
                    client.startWorkflow(workflowType = "HeartbeatForeverWorkflow", taskQueue = queue)
                    withTimeout(30.seconds) { LoopingActivity.firstHeartbeat.await() }

                    // The grace period elapses with the activity still running: forced phase.
                    withTimeout(60.seconds) { embedded.stop() }

                    // The activity was told why it stopped, and a heartbeat after that point throws
                    // instead of reaching the finalized native worker.
                    val cause = withTimeout(10.seconds) { LoopingActivity.observed.await() }
                    assertIs<ActivityCancelledException.WorkerShutdown>(
                        cause,
                        "activity observed ${cause::class.qualifiedName}:\n${cause.stackTraceToString()}",
                    )
                }
            }
        }
}
