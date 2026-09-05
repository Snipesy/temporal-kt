package com.surrealdev.temporal.benchmark

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.core.SlotSupplier
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import com.surrealdev.temporal.application.taskQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

/**
 * Measures the cost of the JVM <-> Core boundary, not of the server.
 *
 * Each sequential activity is four boundary crossings (activity poll and completion, then the
 * workflow activation that observes the result and its completion), with essentially no work in
 * between: the activity returns its input. `ParallelPerformanceTest` cannot see the bridge because
 * its cases are dominated by one-second sleeps.
 *
 * Off by default. Run with `TEMPORAL_BENCHMARK=true ./gradlew :core:test --tests '*BridgeThroughputBenchmark*' -i`
 * and compare the printed activities/second between bridge versions on the same machine.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "TEMPORAL_BENCHMARK", matches = "true")
class BridgeThroughputBenchmark {
    companion object {
        const val WORKFLOWS = 8
        const val ACTIVITIES_PER_WORKFLOW = 250
    }

    class EchoActivity {
        @Activity("echo")
        fun ActivityContext.echo(input: Int): Int = input
    }

    @Workflow("SequentialEchoWorkflow")
    class SequentialEchoWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(count: Int): Int {
            var acc = 0
            repeat(count) { i ->
                acc +=
                    startActivity(
                        activityType = "echo",
                        arg = i,
                        options = ActivityOptions(startToCloseTimeout = 1.minutes),
                    ).result<Int>()
            }
            return acc
        }
    }

    @Test
    fun `sequential echo activities, activities per second`() =
        runTemporalTest(timeSkipping = false, parentCoroutineContext = Dispatchers.Default) {
            val taskQueue = "bridge-throughput-${UUID.randomUUID()}"
            application {
                taskQueue(taskQueue) {
                    workflowSlotSupplier = SlotSupplier.FixedSize(WORKFLOWS * 2)
                    activitySlotSupplier = SlotSupplier.FixedSize(WORKFLOWS * 2)
                    workflow<SequentialEchoWorkflow>()
                    activity(EchoActivity())
                }
            }
            val client = client()

            // Warm-up: JIT, class loading, first connections. Not measured.
            client
                .startWorkflow<Int>(
                    workflowType = "SequentialEchoWorkflow",
                    taskQueue = taskQueue,
                    arg = 20,
                ).result<Int>(timeout = 2.minutes)

            val total = WORKFLOWS * ACTIVITIES_PER_WORKFLOW
            val elapsed =
                measureTime {
                    val handles =
                        (1..WORKFLOWS).map {
                            client.startWorkflow<Int>(
                                workflowType = "SequentialEchoWorkflow",
                                taskQueue = taskQueue,
                                arg = ACTIVITIES_PER_WORKFLOW,
                            )
                        }
                    handles.forEach { it.result<Int>(timeout = 10.minutes) }
                }
            val perSecond = total / elapsed.inWholeMilliseconds.toDouble() * 1000
            println(
                "BENCHMARK sequential-echo: $total activities across $WORKFLOWS workflows in $elapsed " +
                    "= ${"%.0f".format(perSecond)} activities/s, ${"%.2f".format(elapsed.inWholeMicroseconds / total / 1000.0)} ms per activity round trip",
            )
        }
}
