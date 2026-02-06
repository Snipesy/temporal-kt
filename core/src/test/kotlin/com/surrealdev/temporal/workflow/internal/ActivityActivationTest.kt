package com.surrealdev.temporal.workflow.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.WorkflowActivityCancelledException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.common.toTemporal
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveActivityJobCancelled
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveActivityJobCompleted
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveActivityJobFailed
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import io.temporal.api.enums.v1.RetryState
import io.temporal.api.failure.v1.ActivityFailureInfo
import io.temporal.api.failure.v1.ApplicationFailureInfo
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive activation-based tests for activity invocation.
 *
 * Tests verify the full activity lifecycle:
 * - Workflow schedules activity → ScheduleActivity command generated
 * - Activity completes → ResolveActivity job processed
 * - Activity result deserialized and returned to workflow
 * - Activity failures propagated correctly
 * - Activity cancellation handled
 * - Multiple activities executing in parallel
 * - Various argument/return type combinations
 *
 * Sprint 9: Activation-based unit tests for activity invocation
 */
class ActivityActivationTest {
    private val serializer = CompositePayloadSerializer.default()

    // ================================================================
    // Test Data Classes
    // ================================================================

    @Serializable
    data class ActivityInput(
        val message: String,
        val count: Int,
    )

    @Serializable
    data class ActivityOutput(
        val result: String,
        val processed: Boolean,
    )

    // ================================================================
    // Test Workflows
    // ================================================================

    @Workflow("SimpleActivityWorkflow")
    class SimpleActivityWorkflow {
        var activityResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            activityResult =
                startActivity(
                    activityType = "TestActivity::greet",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            return "Workflow got: $activityResult"
        }
    }

    @Workflow("ActivityWithArgumentWorkflow")
    class ActivityWithArgumentWorkflow {
        var workflowInput: String? = null
        var activityResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String {
            workflowInput = input
            activityResult =
                startActivity(
                    activityType = "TestActivity::process",
                    arg = input,
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            return activityResult!!
        }
    }

    @Workflow("MultipleActivitiesWorkflow")
    class MultipleActivitiesWorkflow {
        var result1: String? = null
        var result2: String? = null
        var result3: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            result1 =
                startActivity(
                    activityType = "TestActivity::step1",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            result2 =
                startActivity(
                    activityType = "TestActivity::step2",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            result3 =
                startActivity(
                    activityType = "TestActivity::step3",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            return "$result1, $result2, $result3"
        }
    }

    @Workflow("ParallelActivitiesWorkflow")
    class ParallelActivitiesWorkflow {
        var parallelResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val deferred1 =
                async {
                    startActivity(
                        activityType = "TestActivity::parallel1",
                        options = ActivityOptions(startToCloseTimeout = 30.seconds),
                    ).result<String>()
                }
            val deferred2 =
                async {
                    startActivity(
                        activityType = "TestActivity::parallel2",
                        options = ActivityOptions(startToCloseTimeout = 30.seconds),
                    ).result<String>()
                }
            val deferred3 =
                async {
                    startActivity(
                        activityType = "TestActivity::parallel3",
                        options = ActivityOptions(startToCloseTimeout = 30.seconds),
                    ).result<String>()
                }

            val r1 = deferred1.await()
            val r2 = deferred2.await()
            val r3 = deferred3.await()

            parallelResult = "$r1 | $r2 | $r3"
            return parallelResult!!
        }
    }

    @Workflow("ActivityFailureWorkflow")
    class ActivityFailureWorkflow {
        var caughtException: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startActivity(
                    activityType = "TestActivity::failing",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                caughtException = "Caught failure: ${e.message}"
                caughtException!!
            }
    }

    @Workflow("ActivityCancellationWorkflow")
    class ActivityCancellationWorkflow {
        var cancellationResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val handle =
                startActivity(
                    activityType = "TestActivity::longRunning",
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                )

            handle.cancel("User requested cancellation")

            cancellationResult =
                try {
                    handle.result()
                } catch (e: WorkflowActivityCancelledException) {
                    "Activity was cancelled: ${e.activityType}"
                }
            return cancellationResult!!
        }
    }

    @Workflow("ComplexDataTypesWorkflow")
    class ComplexDataTypesWorkflow {
        var output: ActivityOutput? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): ActivityOutput {
            val input = ActivityInput("test", 42)
            output =
                startActivity(
                    activityType = "TestActivity::processComplex",
                    arg = input,
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            return output!!
        }
    }

    // ================================================================
    // Helper Methods
    // ================================================================

    private data class ExecutorResult(
        val executor: WorkflowExecutor,
        val runId: String,
        val workflow: Any,
        val completion: coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion,
    )

    private suspend inline fun <reified T : Any> createExecutorWithWorkflow(workflowType: String): ExecutorResult {
        val workflow = T::class.constructors.first().call()
        val runMethod =
            T::class
                .members
                .first { it.name == "run" } as KFunction<*>

        val workflowMethodInfo =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                workflowClass = T::class,
                instanceFactory = { workflow },
                parameterTypes = emptyList(),
                returnType = typeOf<String>(),
                hasContextReceiver = true,
                isSuspend = true,
            )

        val runId = "test-run-${UUID.randomUUID()}"
        val executor =
            createTestWorkflowExecutor(
                runId = runId,
                methodInfo = workflowMethodInfo,
                serializer = serializer,
            )

        // Initialize the workflow
        val initActivation =
            createActivation(
                runId = runId,
                jobs = listOf(initializeWorkflowJob(workflowType = workflowType)),
                isReplaying = false,
            )
        val completion = executor.activate(initActivation)

        return ExecutorResult(executor, runId, workflow, completion)
    }

    @OptIn(InternalTemporalApi::class)
    private fun createPayload(data: String): TemporalPayload =
        Payload
            .newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8(data))
            .build()
            .toTemporal()

    private fun getCommandsFromCompletion(
        completion: coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion,
    ): List<WorkflowCommands.WorkflowCommand> =
        if (completion.hasSuccessful()) {
            completion.successful.commandsList
        } else {
            emptyList()
        }

    // ================================================================
    // 1. Basic Activity Lifecycle Tests
    // ================================================================

    @Test
    fun `workflow schedules activity and generates ScheduleActivity command`() =
        runTest {
            val result = createExecutorWithWorkflow<SimpleActivityWorkflow>("SimpleActivityWorkflow")

            assertTrue(result.completion.hasSuccessful())

            val commands = getCommandsFromCompletion(result.completion)
            assertEquals(1, commands.size)
            assertTrue(commands[0].hasScheduleActivity())
            assertEquals("TestActivity::greet", commands[0].scheduleActivity.activityType)
        }

    @Test
    fun `activity completion resolves handle and workflow continues`() =
        runTest {
            val result = createExecutorWithWorkflow<SimpleActivityWorkflow>("SimpleActivityWorkflow")
            val workflow = result.workflow as SimpleActivityWorkflow
            val scope = CoroutineScope(Dispatchers.Default)

            val commands = getCommandsFromCompletion(result.completion)
            val seq = commands[0].scheduleActivity.seq

            // Resolve activity with result
            val resultPayload = createPayload("\"Hello from activity!\"")
            val completion =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs = listOf(resolveActivityJobCompleted(seq, resultPayload)),
                        isReplaying = false,
                    ),
                )

            assertTrue(completion.hasSuccessful())
            assertEquals("Hello from activity!", workflow.activityResult)
        }

    @Test
    fun `activity with argument passes data correctly`() =
        runTest {
            val workflow = ActivityWithArgumentWorkflow()
            val runMethod =
                ActivityWithArgumentWorkflow::class
                    .members
                    .first { it.name == "run" } as KFunction<*>

            val workflowMethodInfo =
                WorkflowMethodInfo(
                    workflowType = "ActivityWithArgumentWorkflow",
                    runMethod = runMethod,
                    workflowClass = ActivityWithArgumentWorkflow::class,
                    instanceFactory = { workflow },
                    parameterTypes = listOf(typeOf<String>()),
                    returnType = typeOf<String>(),
                    hasContextReceiver = true,
                    isSuspend = true,
                )

            val runId = "test-run-${UUID.randomUUID()}"
            val executor =
                createTestWorkflowExecutor(
                    runId = runId,
                    methodInfo = workflowMethodInfo,
                    serializer = serializer,
                )

            val scope = CoroutineScope(Dispatchers.Default)

            // Initialize with argument
            val inputPayload = createPayload("\"Test Input\"")
            val initCompletion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs =
                            listOf(
                                initializeWorkflowJob(
                                    workflowType = "ActivityWithArgumentWorkflow",
                                    arguments = listOf(inputPayload),
                                ),
                            ),
                        isReplaying = false,
                    ),
                )

            val commands = getCommandsFromCompletion(initCompletion)
            val seq = commands[0].scheduleActivity.seq

            // Resolve activity
            val resultPayload = createPayload("\"Processed: Test Input\"")
            val completion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs = listOf(resolveActivityJobCompleted(seq, resultPayload)),
                        isReplaying = false,
                    ),
                )

            assertTrue(completion.hasSuccessful())
            assertEquals("Processed: Test Input", workflow.activityResult)
        }

    // ================================================================
    // 2. Multiple Activities Tests
    // ================================================================

    @Test
    fun `workflow executes multiple activities sequentially`() =
        runTest {
            val result = createExecutorWithWorkflow<MultipleActivitiesWorkflow>("MultipleActivitiesWorkflow")
            val workflow = result.workflow as MultipleActivitiesWorkflow
            val scope = CoroutineScope(Dispatchers.Default)

            // Activity 1
            var commands = getCommandsFromCompletion(result.completion)
            assertEquals(1, commands.size)
            assertEquals("TestActivity::step1", commands[0].scheduleActivity.activityType)
            val seq1 = commands[0].scheduleActivity.seq

            val completion1 =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs = listOf(resolveActivityJobCompleted(seq1, createPayload("\"Step1 Done\""))),
                        isReplaying = false,
                    ),
                )

            // Activity 2
            commands = getCommandsFromCompletion(completion1)
            assertEquals(1, commands.size)
            assertEquals("TestActivity::step2", commands[0].scheduleActivity.activityType)
            val seq2 = commands[0].scheduleActivity.seq

            val completion2 =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs = listOf(resolveActivityJobCompleted(seq2, createPayload("\"Step2 Done\""))),
                        isReplaying = false,
                    ),
                )

            // Activity 3
            commands = getCommandsFromCompletion(completion2)
            assertEquals(1, commands.size)
            assertEquals("TestActivity::step3", commands[0].scheduleActivity.activityType)
            val seq3 = commands[0].scheduleActivity.seq

            val completion =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs = listOf(resolveActivityJobCompleted(seq3, createPayload("\"Step3 Done\""))),
                        isReplaying = false,
                    ),
                )

            assertTrue(completion.hasSuccessful())
            assertEquals("Step1 Done", workflow.result1)
            assertEquals("Step2 Done", workflow.result2)
            assertEquals("Step3 Done", workflow.result3)
        }

    @Test
    fun `workflow executes multiple activities in parallel`() =
        runTest {
            val result = createExecutorWithWorkflow<ParallelActivitiesWorkflow>("ParallelActivitiesWorkflow")
            val workflow = result.workflow as ParallelActivitiesWorkflow
            val scope = CoroutineScope(Dispatchers.Default)

            // All three activities should be scheduled in parallel
            val commands = getCommandsFromCompletion(result.completion)
            assertEquals(3, commands.size)

            val activityTypes = commands.map { it.scheduleActivity.activityType }.toSet()
            assertTrue(activityTypes.contains("TestActivity::parallel1"))
            assertTrue(activityTypes.contains("TestActivity::parallel2"))
            assertTrue(activityTypes.contains("TestActivity::parallel3"))

            val seqs = commands.map { it.scheduleActivity.seq }

            // Resolve all activities
            val completion =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs =
                            listOf(
                                resolveActivityJobCompleted(seqs[0], createPayload("\"Result1\"")),
                                resolveActivityJobCompleted(seqs[1], createPayload("\"Result2\"")),
                                resolveActivityJobCompleted(seqs[2], createPayload("\"Result3\"")),
                            ),
                        isReplaying = false,
                    ),
                )

            assertTrue(completion.hasSuccessful())
            assertNotNull(workflow.parallelResult)
            assertTrue(workflow.parallelResult!!.contains("Result1"))
            assertTrue(workflow.parallelResult!!.contains("Result2"))
            assertTrue(workflow.parallelResult!!.contains("Result3"))
        }

    // ================================================================
    // 3. Activity Failure Tests
    // ================================================================

    @Test
    fun `activity failure propagates to workflow`() =
        runTest {
            val result = createExecutorWithWorkflow<ActivityFailureWorkflow>("ActivityFailureWorkflow")
            val workflow = result.workflow as ActivityFailureWorkflow
            val scope = CoroutineScope(Dispatchers.Default)

            val commands = getCommandsFromCompletion(result.completion)
            val seq = commands[0].scheduleActivity.seq

            // Resolve activity with failure
            val completion =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs = listOf(resolveActivityJobFailed(seq, "Activity execution failed")),
                        isReplaying = false,
                    ),
                )

            assertTrue(completion.hasSuccessful())
            assertNotNull(workflow.caughtException)
            assertTrue(workflow.caughtException!!.contains("Caught failure"))
            assertTrue(workflow.caughtException!!.contains("Activity execution failed"))
        }

    @Test
    fun `activity failure with nested cause preserves exception chain`() =
        runTest {
            val result = createExecutorWithWorkflow<SimpleActivityWorkflow>("SimpleActivityWorkflow")
            val scope = CoroutineScope(Dispatchers.Default)

            val commands = getCommandsFromCompletion(result.completion)
            val seq = commands[0].scheduleActivity.seq

            // Create nested failure
            val applicationFailure =
                Failure
                    .newBuilder()
                    .setMessage("Database connection timeout")
                    .setApplicationFailureInfo(
                        ApplicationFailureInfo
                            .newBuilder()
                            .setType("DatabaseError")
                            .setNonRetryable(false),
                    ).build()

            val activityFailure =
                Failure
                    .newBuilder()
                    .setMessage("Activity failed to execute")
                    .setCause(applicationFailure)
                    .setActivityFailureInfo(
                        ActivityFailureInfo
                            .newBuilder()
                            .setRetryState(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED),
                    ).build()

            val resolveActivity =
                coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveActivity
                    .newBuilder()
                    .setSeq(seq)
                    .setResult(
                        coresdk.activity_result.ActivityResult.ActivityResolution
                            .newBuilder()
                            .setFailed(
                                coresdk.activity_result.ActivityResult.Failure
                                    .newBuilder()
                                    .setFailure(activityFailure),
                            ),
                    ).build()

            val job =
                coresdk.workflow_activation.WorkflowActivationOuterClass.WorkflowActivationJob
                    .newBuilder()
                    .setResolveActivity(resolveActivity)
                    .build()

            val completion =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs = listOf(job),
                        isReplaying = false,
                    ),
                )

            // Workflow should fail with FailWorkflowExecution command
            assertTrue(completion.hasSuccessful())
            val completionCommands = getCommandsFromCompletion(completion)
            val failCommand = completionCommands.find { it.hasFailWorkflowExecution() }
            assertNotNull(failCommand, "Should have FailWorkflowExecution command")
            assertTrue(
                failCommand.failWorkflowExecution.failure.message
                    .contains("Activity failed to execute"),
            )
        }

    // ================================================================
    // 4. Activity Cancellation Tests
    // ================================================================

    @Test
    fun `activity cancellation generates RequestCancelActivity command`() =
        runTest {
            val result = createExecutorWithWorkflow<ActivityCancellationWorkflow>("ActivityCancellationWorkflow")

            val commands = getCommandsFromCompletion(result.completion)

            // Should have both ScheduleActivity and RequestCancelActivity
            assertEquals(2, commands.size)
            assertTrue(commands[0].hasScheduleActivity())
            assertTrue(commands[1].hasRequestCancelActivity())

            val scheduleSeq = commands[0].scheduleActivity.seq
            val cancelSeq = commands[1].requestCancelActivity.seq
            assertEquals(scheduleSeq, cancelSeq)
        }

    @Test
    fun `cancelled activity resolves with WorkflowActivityCancelledException`() =
        runTest {
            val result = createExecutorWithWorkflow<ActivityCancellationWorkflow>("ActivityCancellationWorkflow")
            val workflow = result.workflow as ActivityCancellationWorkflow
            val scope = CoroutineScope(Dispatchers.Default)

            val commands = getCommandsFromCompletion(result.completion)
            val seq = commands[0].scheduleActivity.seq

            // Resolve activity as cancelled
            val completion =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs = listOf(resolveActivityJobCancelled(seq)),
                        isReplaying = false,
                    ),
                )

            assertTrue(completion.hasSuccessful())
            assertNotNull(workflow.cancellationResult)
            assertTrue(workflow.cancellationResult!!.contains("Activity was cancelled"))
            assertTrue(workflow.cancellationResult!!.contains("TestActivity::longRunning"))
        }

    // ================================================================
    // 5. Complex Data Types Tests
    // ================================================================

    @Test
    fun `activity with complex serializable data types`() =
        runTest {
            val result = createExecutorWithWorkflow<ComplexDataTypesWorkflow>("ComplexDataTypesWorkflow")
            val workflow = result.workflow as ComplexDataTypesWorkflow
            val scope = CoroutineScope(Dispatchers.Default)

            val commands = getCommandsFromCompletion(result.completion)
            val scheduleCommand = commands[0].scheduleActivity

            // Verify input was serialized
            assertEquals(1, scheduleCommand.argumentsCount)

            val seq = scheduleCommand.seq

            // Create complex output
            val outputJson = """{"result":"Processed test with count 42","processed":true}"""
            val outputPayload = createPayload(outputJson)

            // Resolve activity
            val completion =
                result.executor.activate(
                    createActivation(
                        runId = result.runId,
                        jobs = listOf(resolveActivityJobCompleted(seq, outputPayload)),
                        isReplaying = false,
                    ),
                )

            assertTrue(completion.hasSuccessful())
            assertNotNull(workflow.output)
            assertEquals("Processed test with count 42", workflow.output!!.result)
            assertTrue(workflow.output!!.processed)
        }

    // ================================================================
    // 6. Replay Tests
    // ================================================================

    @Test
    fun `activity result is consistent across replay`() =
        runTest {
            // First execution
            val result1 = createExecutorWithWorkflow<SimpleActivityWorkflow>("SimpleActivityWorkflow")
            val workflow1 = result1.workflow as SimpleActivityWorkflow
            val scope1 = CoroutineScope(Dispatchers.Default)

            val commands = getCommandsFromCompletion(result1.completion)
            val seq = commands[0].scheduleActivity.seq

            val resultPayload = createPayload("\"Original Result\"")
            result1.executor.activate(
                createActivation(
                    runId = result1.runId,
                    jobs = listOf(resolveActivityJobCompleted(seq, resultPayload)),
                    isReplaying = false,
                ),
            )

            val firstResult = workflow1.activityResult

            // Replay - create new executor for replay
            val workflow2 = SimpleActivityWorkflow()
            val runMethod2 =
                SimpleActivityWorkflow::class
                    .members
                    .first { it.name == "run" } as KFunction<*>

            val workflowMethodInfo2 =
                WorkflowMethodInfo(
                    workflowType = "SimpleActivityWorkflow",
                    runMethod = runMethod2,
                    workflowClass = SimpleActivityWorkflow::class,
                    instanceFactory = { workflow2 },
                    parameterTypes = emptyList(),
                    returnType = typeOf<String>(),
                    hasContextReceiver = true,
                    isSuspend = true,
                )

            val runId2 = "test-run-${UUID.randomUUID()}"
            val executor2 =
                createTestWorkflowExecutor(
                    runId = runId2,
                    methodInfo = workflowMethodInfo2,
                    serializer = serializer,
                )

            val scope2 = CoroutineScope(Dispatchers.Default)

            executor2.activate(
                createActivation(
                    runId = runId2,
                    jobs = listOf(initializeWorkflowJob(workflowType = "SimpleActivityWorkflow")),
                    isReplaying = true,
                ),
            )

            executor2.activate(
                createActivation(
                    runId = runId2,
                    jobs = listOf(resolveActivityJobCompleted(seq, resultPayload)),
                    isReplaying = true,
                ),
            )

            val replayResult = workflow2.activityResult

            // Verify same result
            assertEquals(firstResult, replayResult)
            assertEquals("Original Result", replayResult)
        }

    // ================================================================
    // 7. Edge Cases
    // ================================================================

    @Test
    fun `activity handle isDone is false before resolution`() =
        runTest {
            val result = createExecutorWithWorkflow<SimpleActivityWorkflow>("SimpleActivityWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            val seq = commands[0].scheduleActivity.seq

            val handle = result.executor.state.getActivity(seq)
            assertNotNull(handle)
            assertFalse(handle.isDone)
        }

    @Test
    fun `activity handle isDone is true after resolution`() =
        runTest {
            // Use a workflow that stores the handle and signals to allow checking mid-execution
            @Workflow("HandleCheckWorkflow")
            class HandleCheckWorkflow {
                var handle: com.surrealdev.temporal.workflow.ActivityHandle? = null
                var shouldContinue = false

                @WorkflowRun
                suspend fun WorkflowContext.run(): String {
                    handle =
                        startActivity(
                            activityType = "TestActivity::check",
                            options = ActivityOptions(startToCloseTimeout = 30.seconds),
                        )
                    // Wait for signal to continue
                    awaitCondition { shouldContinue }
                    return handle!!.result()
                }
            }

            val workflow = HandleCheckWorkflow()
            val runMethod =
                HandleCheckWorkflow::class
                    .members
                    .first { it.name == "run" } as KFunction<*>

            val workflowMethodInfo =
                WorkflowMethodInfo(
                    workflowType = "HandleCheckWorkflow",
                    runMethod = runMethod,
                    workflowClass = HandleCheckWorkflow::class,
                    instanceFactory = { workflow },
                    parameterTypes = emptyList(),
                    returnType = typeOf<String>(),
                    hasContextReceiver = true,
                    isSuspend = true,
                )

            val runId = "test-run-${UUID.randomUUID()}"
            val executor =
                createTestWorkflowExecutor(
                    runId = runId,
                    methodInfo = workflowMethodInfo,
                    serializer = serializer,
                )

            val scope = CoroutineScope(Dispatchers.Default)

            // Initialize workflow - it will start the activity and wait for signal
            val initCompletion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs = listOf(initializeWorkflowJob(workflowType = "HandleCheckWorkflow")),
                        isReplaying = false,
                    ),
                )

            val commands = getCommandsFromCompletion(initCompletion)
            val seq = commands[0].scheduleActivity.seq

            // Verify handle is not done before resolution
            val handleSnapshot = workflow.handle
            assertNotNull(handleSnapshot)
            assertFalse(handleSnapshot!!.isDone)

            // Resolve the activity
            executor.activate(
                createActivation(
                    runId = runId,
                    jobs = listOf(resolveActivityJobCompleted(seq, createPayload("\"Done\""))),
                    isReplaying = false,
                ),
            )

            // Verify handle is done after resolution
            val handleSnapshotAfter = workflow.handle
            assertNotNull(handleSnapshotAfter)
            assertTrue(handleSnapshotAfter!!.isDone)
        }

    @Test
    fun `activity with Unit return type completes successfully`() =
        runTest {
            @Workflow("UnitActivityWorkflow")
            class UnitActivityWorkflow {
                var completed = false

                @WorkflowRun
                suspend fun WorkflowContext.run(): String {
                    startActivity(
                        activityType = "TestActivity::doSomething",
                        options = ActivityOptions(startToCloseTimeout = 30.seconds),
                    ).result<Unit>()
                    completed = true
                    return "Done"
                }
            }

            val workflow = UnitActivityWorkflow()
            val runMethod =
                UnitActivityWorkflow::class
                    .members
                    .first { it.name == "run" } as KFunction<*>

            val workflowMethodInfo =
                WorkflowMethodInfo(
                    workflowType = "UnitActivityWorkflow",
                    runMethod = runMethod,
                    workflowClass = UnitActivityWorkflow::class,
                    instanceFactory = { workflow },
                    parameterTypes = emptyList(),
                    returnType = typeOf<String>(),
                    hasContextReceiver = true,
                    isSuspend = true,
                )

            val runId = "test-run-${UUID.randomUUID()}"
            val executor =
                createTestWorkflowExecutor(
                    runId = runId,
                    methodInfo = workflowMethodInfo,
                    serializer = serializer,
                )

            val scope = CoroutineScope(Dispatchers.Default)

            val initCompletion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs = listOf(initializeWorkflowJob(workflowType = "UnitActivityWorkflow")),
                        isReplaying = false,
                    ),
                )

            val commands = getCommandsFromCompletion(initCompletion)
            val seq = commands[0].scheduleActivity.seq

            val completion =
                executor.activate(
                    createActivation(
                        runId = runId,
                        jobs = listOf(resolveActivityJobCompleted(seq, Payload.getDefaultInstance().toTemporal())),
                        isReplaying = false,
                    ),
                )

            assertTrue(completion.hasSuccessful())
            assertTrue(workflow.completed)
        }
}
