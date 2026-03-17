package com.surrealdev.temporal.client

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.history.TemporalEventType
import com.surrealdev.temporal.client.history.TemporalHistoryEvent
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for event history wrappers,
 * verifying typed events and payload encode/decode round-trips.
 */
@Tag("integration")
class EventHistoryIntegrationTest {
    class HistoryActivities {
        @Activity("historyEcho")
        suspend fun ActivityContext.echo(input: String): String = "echoed:$input"
    }

    @Workflow("HistoryWorkflow")
    class HistoryWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String {
            val activityResult =
                startActivity<String>(
                    activityType = "historyEcho",
                    arg = input,
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result<String>()
            return "wf:$activityResult"
        }
    }

    @Test
    fun `event history has typed events with decoded payloads`() =
        runTemporalTest {
            val taskQueue = "test-history-events-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<HistoryWorkflow>()
                    activity(HistoryActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "HistoryWorkflow",
                    taskQueue = taskQueue,
                    arg = "hello",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:echoed:hello", result)

            val history = handle.getHistory()
            val serializer = client.serializer

            // -- Workflow started event --
            val started = history.workflowExecutionStartedEvent
            assertIs<TemporalHistoryEvent.WorkflowExecutionStarted>(started)
            assertEquals("HistoryWorkflow", started.workflowType)
            assertEquals(taskQueue, started.taskQueue)
            assertEquals(1, started.attempt)
            assertNotNull(started.input)
            val startedInput: String = serializer.deserialize(started.input!![0])
            assertEquals("hello", startedInput)

            // -- Workflow completed event --
            assertTrue(history.isCompleted)
            val completed = history.completedEvent!!
            assertIs<TemporalHistoryEvent.WorkflowExecutionCompleted>(completed)
            assertNotNull(completed.result)
            val completedResult: String = serializer.deserialize(completed.result!![0])
            assertEquals("wf:echoed:hello", completedResult)

            // -- Activity scheduled event --
            val scheduled = history.activityScheduledEvents()
            assertEquals(1, scheduled.size)
            val actScheduled = scheduled.first()
            assertEquals("historyEcho", actScheduled.activityType)
            assertNotNull(actScheduled.input)
            val actInput: String = serializer.deserialize(actScheduled.input!![0])
            assertEquals("hello", actInput)

            // -- Activity completed event --
            val actCompleted = history.activityCompletedEvents()
            assertEquals(1, actCompleted.size)
            assertNotNull(actCompleted.first().result)
            val actResult: String =
                serializer.deserialize(actCompleted.first().result!![0])
            assertEquals("echoed:hello", actResult)

            // -- Basic event type checks --
            assertTrue(history.events.any { it.eventType == TemporalEventType.WORKFLOW_TASK_SCHEDULED })
            assertTrue(history.events.any { it.eventType == TemporalEventType.WORKFLOW_TASK_COMPLETED })
            assertTrue(history.events.any { it.eventType == TemporalEventType.ACTIVITY_TASK_STARTED })

            // -- All events have valid IDs and timestamps --
            for (event in history.events) {
                assertTrue(event.eventId > 0, "Event ID should be positive: ${event.eventType}")
                assertTrue(event.timestamp > 0, "Timestamp should be positive: ${event.eventType}")
            }
        }
}
