package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.testing.runWorkflowUnitTest
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.SimpleAttributeScope
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.RetryPolicy
import com.surrealdev.temporal.workflow.WorkflowInfo
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Comprehensive tests for WorkflowContextImpl.startActivity() method.
 *
 * Tests are organized by validation category:
 * - Activity Type Validation
 * - Timeout Validation (Required, Positive Values, Relationships, Warnings)
 * - Priority Validation
 * - RetryPolicy Validation
 * - Activity ID Generation
 * - Happy Path
 */
class WorkflowContextImplStartActivityTest {
    private val serializer = KotlinxJsonSerializer()

    private fun createContext(): WorkflowContextImpl {
        val state = WorkflowState("test-run-id")
        state.isReadOnly = false
        val info =
            WorkflowInfo(
                workflowId = "test-workflow",
                runId = "test-run-id",
                workflowType = "TestWorkflow",
                taskQueue = "test-queue",
                namespace = "default",
                attempt = 1,
                startTime = Instant.fromEpochMilliseconds(0),
            )
        val dispatcher = WorkflowCoroutineDispatcher()
        // Create a simple scope hierarchy for testing (taskQueue -> application)
        val applicationScope = SimpleAttributeScope(Attributes(concurrent = false))
        val taskQueueScope = SimpleAttributeScope(Attributes(concurrent = false), applicationScope)
        return WorkflowContextImpl(
            state = state,
            info = info,
            serializer = serializer,
            codec = com.surrealdev.temporal.serialization.NoOpCodec,
            workflowDispatcher = dispatcher,
            parentJob = Job(),
            handlerJob = Job(),
            parentScope = taskQueueScope,
        )
    }

    /**
     * Helper to access private WorkflowState field for command inspection.
     */
    private fun getState(context: WorkflowContextImpl): WorkflowState {
        val field = WorkflowContextImpl::class.java.getDeclaredField("state")
        field.isAccessible = true
        return field.get(context) as WorkflowState
    }

    /**
     * Helper to access private commands list from WorkflowState via reflection.
     */
    @Suppress("UNCHECKED_CAST")
    private fun getCommands(state: WorkflowState): List<coresdk.workflow_commands.WorkflowCommands.WorkflowCommand> {
        val field = WorkflowState::class.java.getDeclaredField("commands")
        field.isAccessible = true
        return field.get(state) as List<coresdk.workflow_commands.WorkflowCommands.WorkflowCommand>
    }

    // ========== Category 1: Activity Type Validation ==========

    @Test
    fun `startActivity throws ReadOnlyContextException during query processing`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            state.isReadOnly = true // Simulate query processing
            val options = ActivityOptions(startToCloseTimeout = 30.seconds)

            val exception =
                assertFailsWith<ReadOnlyContextException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(
                exception.message!!.contains("read-only") ||
                    exception.message!!.contains("query"),
            )
        }

    @Test
    fun `startActivity validates activityType is non-blank`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options = ActivityOptions(startToCloseTimeout = 30.seconds)

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("", options)
                }

            assertTrue(exception.message!!.contains("activityType"))
        }

    // ========== Category 2: Timeout Validation - Required ==========

    @Test
    fun `startActivity requires at least one timeout`() =
        runWorkflowUnitTest {
            // Validation now happens in ActivityOptions constructor
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    ActivityOptions() // No timeouts set
                }

            assertTrue(
                exception.message!!.contains("startToCloseTimeout") ||
                    exception.message!!.contains("scheduleToCloseTimeout"),
            )
        }

    // ========== Category 3: Timeout Validation - Positive Values ==========

    @Test
    fun `startActivity validates startToCloseTimeout is positive`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options = ActivityOptions(startToCloseTimeout = (-1).seconds)

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("startToCloseTimeout"))
            assertTrue(exception.message!!.contains("positive"))
        }

    @Test
    fun `startActivity validates scheduleToCloseTimeout is positive`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options = ActivityOptions(scheduleToCloseTimeout = (-1).seconds)

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("scheduleToCloseTimeout"))
            assertTrue(exception.message!!.contains("positive"))
        }

    @Test
    fun `startActivity validates scheduleToStartTimeout is positive`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    scheduleToStartTimeout = (-1).seconds,
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("scheduleToStartTimeout"))
            assertTrue(exception.message!!.contains("positive"))
        }

    @Test
    fun `startActivity validates heartbeatTimeout is positive`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    heartbeatTimeout = (-1).seconds,
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("heartbeatTimeout"))
            assertTrue(exception.message!!.contains("positive"))
        }

    // ========== Category 4: Timeout Validation - Relationships ==========

    @Test
    fun `startActivity validates scheduleToClose greater than or equal to startToClose`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 60.seconds,
                    scheduleToCloseTimeout = 30.seconds, // Less than startToClose - invalid
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("scheduleToCloseTimeout"))
            assertTrue(exception.message!!.contains("startToCloseTimeout"))
        }

    @Test
    fun `startActivity validates scheduleToStart less than scheduleToClose`() =
        runWorkflowUnitTest {
            // Validation now happens in ActivityOptions constructor
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    ActivityOptions(
                        scheduleToCloseTimeout = 30.seconds,
                        scheduleToStartTimeout = 40.seconds, // Greater than scheduleToClose - invalid
                    )
                }

            assertTrue(exception.message!!.contains("scheduleToStartTimeout"))
            assertTrue(exception.message!!.contains("scheduleToCloseTimeout"))
        }

    @Test
    fun `startActivity rejects scheduleToStart equal to scheduleToClose`() =
        runWorkflowUnitTest {
            // Validation now happens in ActivityOptions constructor
            // Equal is NOT valid - must be strictly less than
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    ActivityOptions(
                        scheduleToCloseTimeout = 30.seconds,
                        scheduleToStartTimeout = 30.seconds, // Equal - invalid (must be less than)
                    )
                }

            assertTrue(exception.message!!.contains("scheduleToStartTimeout"))
        }

    @Test
    fun `startActivity validates three-timeout relationship`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    scheduleToStartTimeout = 30.seconds,
                    startToCloseTimeout = 60.seconds,
                    scheduleToCloseTimeout = 70.seconds, // 30+60=90 > 70 - invalid
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("scheduleToStartTimeout"))
            assertTrue(exception.message!!.contains("startToCloseTimeout"))
            assertTrue(exception.message!!.contains("scheduleToCloseTimeout"))
        }

    @Test
    fun `startActivity accepts valid three-timeout relationship`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    scheduleToStartTimeout = 30.seconds,
                    startToCloseTimeout = 60.seconds,
                    scheduleToCloseTimeout = 90.seconds, // 30+60=90 == 90 - valid
                )

            // Should not throw
            val handle = context.startActivity("TestActivity", options)
            assertNotNull(handle)
        }

    // ========== Category 5: Timeout Validation - Warnings ==========

    @Test
    fun `startActivity warns when heartbeat greater than or equal to startToClose`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    heartbeatTimeout = 35.seconds, // Greater than startToClose - warning
                )

            // Should not throw, but will log a warning
            val handle = context.startActivity("TestActivity", options)
            assertNotNull(handle)
        }

    // ========== Category 6: Priority Validation ==========

    @Test
    fun `startActivity validates priority minimum is 0`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    priority = -1,
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("priority"))
            assertTrue(exception.message!!.contains("0-100"))
        }

    @Test
    fun `startActivity validates priority maximum is 100`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    priority = 101,
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("priority"))
            assertTrue(exception.message!!.contains("0-100"))
        }

    @Test
    fun `startActivity accepts valid priority range`() =
        runWorkflowUnitTest {
            val context = createContext()

            // Test boundary values
            val options0 =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    priority = 0,
                )
            val handle0 = context.startActivity("TestActivity0", options0)
            assertNotNull(handle0)

            val options100 =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    priority = 100,
                )
            val handle100 = context.startActivity("TestActivity100", options100)
            assertNotNull(handle100)

            // Test middle value
            val options50 =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    priority = 50,
                )
            val handle50 = context.startActivity("TestActivity50", options50)
            assertNotNull(handle50)
        }

    // ========== Category 7: RetryPolicy Validation ==========

    @Test
    fun `startActivity validates RetryPolicy backoffCoefficient greater than 1`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    retryPolicy = RetryPolicy(backoffCoefficient = 0.5), // Invalid
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("backoffCoefficient"))
            assertTrue(exception.message!!.contains("> 1.0"))
        }

    @Test
    fun `startActivity validates RetryPolicy maximumAttempts positive`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    retryPolicy = RetryPolicy(maximumAttempts = -1), // Invalid
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("maximumAttempts"))
        }

    @Test
    fun `startActivity validates RetryPolicy maximumInterval greater than or equal to initialInterval`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    retryPolicy =
                        RetryPolicy(
                            initialInterval = 10.seconds,
                            maximumInterval = 5.seconds, // Less than initial - invalid
                        ),
                )

            val exception =
                assertFailsWith<IllegalArgumentException> {
                    context.startActivity("TestActivity", options)
                }

            assertTrue(exception.message!!.contains("maximumInterval"))
            assertTrue(exception.message!!.contains("initialInterval"))
        }

    // ========== Category 8: Activity ID Generation ==========

    @Test
    fun `startActivity generates correct activity ID from seq`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options = ActivityOptions(startToCloseTimeout = 30.seconds)

            val handle = context.startActivity("TestActivity", options)

            // First activity should have ID "1" (seq starts at 1)
            assertEquals("1", handle.activityId)
        }

    @Test
    fun `startActivity uses custom activity ID when provided`() =
        runWorkflowUnitTest {
            val context = createContext()
            val customId = "custom-activity-id-123"
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    activityId = customId,
                )

            val handle = context.startActivity("TestActivity", options)

            assertEquals(customId, handle.activityId)
        }

    // ========== Category 9: Happy Path ==========

    @Test
    fun `startActivity returns handle with correct properties`() =
        runWorkflowUnitTest {
            val context = createContext()
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    scheduleToCloseTimeout = 1.minutes,
                    activityId = "test-activity-id",
                )

            val handle = context.startActivity("TestActivity", options)

            assertNotNull(handle)
            assertEquals("test-activity-id", handle.activityId)
            assertEquals(false, handle.isDone)
            assertEquals(false, handle.isCancellationRequested)
        }

    @Test
    fun `startActivity registers handle in workflow state`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state =
                (context as WorkflowContextImpl).let { ctx ->
                    // Access private state field via reflection for testing
                    ctx.javaClass
                        .getDeclaredField("state")
                        .apply { isAccessible = true }
                        .get(ctx) as WorkflowState
                }
            val options = ActivityOptions(startToCloseTimeout = 30.seconds)

            val handle = context.startActivity("TestActivity", options)

            // Verify handle is registered in state
            val seq = 1 // First seq
            val registeredHandle = state.getActivity(seq)
            assertNotNull(registeredHandle)
            assertEquals(handle.activityId, registeredHandle.activityId)
        }

    // ========== Category 10: Command Generation Tests ==========

    @Test
    fun `startActivity generates ScheduleActivity command`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            val options = ActivityOptions(startToCloseTimeout = 30.seconds)

            context.startActivity("TestActivity", options)

            val commands = getCommands(state)
            assertEquals(1, commands.size)
            assertTrue(commands[0].hasScheduleActivity())
        }

    @Test
    fun `ScheduleActivity command has correct seq`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            val options = ActivityOptions(startToCloseTimeout = 30.seconds)

            context.startActivity("TestActivity", options)

            val command = getCommands(state)[0].scheduleActivity
            assertEquals(1, command.seq) // First command should have seq 1
        }

    @Test
    fun `ScheduleActivity command has correct activityId and activityType`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    activityId = "custom-id",
                )

            context.startActivity("MyActivity", options)

            val command = getCommands(state)[0].scheduleActivity
            assertEquals("custom-id", command.activityId)
            assertEquals("MyActivity", command.activityType)
        }

    @Test
    fun `ScheduleActivity command uses workflow taskQueue by default`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            val options = ActivityOptions(startToCloseTimeout = 30.seconds)

            context.startActivity("TestActivity", options)

            val command = getCommands(state)[0].scheduleActivity
            assertEquals("test-queue", command.taskQueue) // From WorkflowInfo
        }

    @Test
    fun `ScheduleActivity command uses custom taskQueue when provided`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    taskQueue = "custom-task-queue",
                )

            context.startActivity("TestActivity", options)

            val command = getCommands(state)[0].scheduleActivity
            assertEquals("custom-task-queue", command.taskQueue)
        }

    @Test
    fun `ScheduleActivity command sets all timeout fields`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    scheduleToCloseTimeout = 60.seconds,
                    scheduleToStartTimeout = 10.seconds,
                    heartbeatTimeout = 5.seconds,
                )

            context.startActivity("TestActivity", options)

            val command = getCommands(state)[0].scheduleActivity
            assertTrue(command.hasStartToCloseTimeout())
            assertTrue(command.hasScheduleToCloseTimeout())
            assertTrue(command.hasScheduleToStartTimeout())
            assertTrue(command.hasHeartbeatTimeout())

            assertEquals(30, command.startToCloseTimeout.seconds)
            assertEquals(60, command.scheduleToCloseTimeout.seconds)
            assertEquals(10, command.scheduleToStartTimeout.seconds)
            assertEquals(5, command.heartbeatTimeout.seconds)
        }

    @Test
    fun `ScheduleActivity command sets retry policy correctly`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    retryPolicy =
                        RetryPolicy(
                            initialInterval = 1.seconds,
                            backoffCoefficient = 2.0,
                            maximumAttempts = 5,
                            maximumInterval = 10.seconds,
                            nonRetryableErrorTypes = listOf("FatalError", "BadRequest"),
                        ),
                )

            context.startActivity("TestActivity", options)

            val command = getCommands(state)[0].scheduleActivity
            assertTrue(command.hasRetryPolicy())

            val retryPolicy = command.retryPolicy
            assertEquals(1, retryPolicy.initialInterval.seconds)
            assertEquals(2.0, retryPolicy.backoffCoefficient)
            assertEquals(5, retryPolicy.maximumAttempts)
            assertEquals(10, retryPolicy.maximumInterval.seconds)
            assertEquals(listOf("FatalError", "BadRequest"), retryPolicy.nonRetryableErrorTypesList)
        }

    @Test
    fun `ScheduleActivity command sets cancellation type`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)

            // Test all three cancellation types
            val testCases =
                listOf(
                    com.surrealdev.temporal.workflow.ActivityCancellationType.TRY_CANCEL to
                        coresdk.workflow_commands.WorkflowCommands.ActivityCancellationType.TRY_CANCEL,
                    com.surrealdev.temporal.workflow.ActivityCancellationType.WAIT_CANCELLATION_COMPLETED to
                        coresdk.workflow_commands.WorkflowCommands.ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
                    com.surrealdev.temporal.workflow.ActivityCancellationType.ABANDON to
                        coresdk.workflow_commands.WorkflowCommands.ActivityCancellationType.ABANDON,
                )

            for ((domainType, protoType) in testCases) {
                val options =
                    ActivityOptions(
                        startToCloseTimeout = 30.seconds,
                        cancellationType = domainType,
                    )

                context.startActivity("TestActivity-${domainType.name}", options)

                val commands = getCommands(state)
                val command = commands.last().scheduleActivity
                assertEquals(protoType, command.cancellationType)
            }
        }

    @Test
    fun `ScheduleActivity command sets versioning intent`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)

            // Test all three versioning intents
            val testCases =
                listOf(
                    com.surrealdev.temporal.workflow.VersioningIntent.UNSPECIFIED to
                        coresdk.common.Common.VersioningIntent.UNSPECIFIED,
                    com.surrealdev.temporal.workflow.VersioningIntent.DEFAULT to
                        coresdk.common.Common.VersioningIntent.DEFAULT,
                    com.surrealdev.temporal.workflow.VersioningIntent.COMPATIBLE to
                        coresdk.common.Common.VersioningIntent.COMPATIBLE,
                )

            for ((domainIntent, protoIntent) in testCases) {
                val options =
                    ActivityOptions(
                        startToCloseTimeout = 30.seconds,
                        versioningIntent = domainIntent,
                    )

                context.startActivity("TestActivity-${domainIntent.name}", options)

                val commands = getCommands(state)
                val command = commands.last().scheduleActivity
                assertEquals(protoIntent, command.versioningIntent)
            }
        }

    @Test
    fun `ScheduleActivity command sets headers when provided`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)
            val headers =
                mapOf(
                    "key1" to
                        io.temporal.api.common.v1.Payload
                            .newBuilder()
                            .setData(
                                com.google.protobuf.ByteString
                                    .copyFromUtf8("value1"),
                            ).build(),
                    "key2" to
                        io.temporal.api.common.v1.Payload
                            .newBuilder()
                            .setData(
                                com.google.protobuf.ByteString
                                    .copyFromUtf8("value2"),
                            ).build(),
                )
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    headers = headers,
                )

            context.startActivity("TestActivity", options)

            val command = getCommands(state)[0].scheduleActivity
            assertEquals(2, command.headersMap.size)
            assertTrue(command.headersMap.containsKey("key1"))
            assertTrue(command.headersMap.containsKey("key2"))
        }

    @Test
    fun `ScheduleActivity command sets disableEagerExecution flag`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)

            // Test with disableEagerExecution = true
            val optionsTrue =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    disableEagerExecution = true,
                )
            context.startActivity("TestActivity1", optionsTrue)

            val commandTrue = getCommands(state)[0].scheduleActivity
            assertTrue(commandTrue.doNotEagerlyExecute)

            // Test with disableEagerExecution = false
            val optionsFalse =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    disableEagerExecution = false,
                )
            context.startActivity("TestActivity2", optionsFalse)

            val commandFalse = getCommands(state)[1].scheduleActivity
            assertEquals(false, commandFalse.doNotEagerlyExecute)
        }

    @Test
    fun `ScheduleActivity command sets priority field`() =
        runWorkflowUnitTest {
            val context = createContext()
            val state = getState(context)

            // Test with priority = 50
            val options =
                ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    priority = 50,
                )

            context.startActivity("TestActivity", options)

            val command = getCommands(state)[0].scheduleActivity
            assertTrue(command.hasPriority())
            assertEquals(50, command.priority.priorityKey)
        }
}
