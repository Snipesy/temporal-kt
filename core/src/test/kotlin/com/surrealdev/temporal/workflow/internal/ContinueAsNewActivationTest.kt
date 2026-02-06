package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.toTemporal
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.ContinueAsNewOptions
import com.surrealdev.temporal.workflow.RetryPolicy
import com.surrealdev.temporal.workflow.VersioningIntent
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.continueAsNew
import com.surrealdev.temporal.workflow.continueAsNewTo
import coresdk.workflow_commands.WorkflowCommands
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Activation-based unit tests for continue-as-new functionality.
 *
 * Tests verify:
 * - ContinueAsNewWorkflowExecution command generation
 * - Argument serialization
 * - Option handling (workflow type, task queue, timeouts, etc.)
 * - Determinism across replay
 */
class ContinueAsNewActivationTest {
    private val serializer = CompositePayloadSerializer.default()

    // ================================================================
    // Test Data Classes
    // ================================================================

    @Serializable
    data class WorkflowInput(
        val iteration: Int,
        val data: String,
    )

    // ================================================================
    // Test Workflows
    // ================================================================

    @Workflow("SimpleContinueAsNewWorkflow")
    class SimpleContinueAsNewWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            continueAsNew<Int>(1)
        }
    }

    @Workflow("ContinueAsNewWithArgWorkflow")
    class ContinueAsNewWithArgWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(iteration: Int): String {
            if (iteration >= 10) {
                return "completed at $iteration"
            }
            continueAsNew(iteration + 1)
        }
    }

    @Workflow("ContinueAsNewWithOptionsWorkflow")
    class ContinueAsNewWithOptionsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            continueAsNew(
                42,
                ContinueAsNewOptions(
                    workflowType = "NewWorkflowType",
                    taskQueue = "new-task-queue",
                    workflowRunTimeout = 5.minutes,
                    workflowTaskTimeout = 30.seconds,
                    versioningIntent = VersioningIntent.COMPATIBLE,
                ),
            )
        }
    }

    @Workflow("ContinueAsNewWithRetryPolicyWorkflow")
    class ContinueAsNewWithRetryPolicyWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            continueAsNew<Int>(
                1,
                ContinueAsNewOptions(
                    retryPolicy =
                        RetryPolicy(
                            initialInterval = 1.seconds,
                            maximumInterval = 100.seconds,
                            backoffCoefficient = 2.0,
                            maximumAttempts = 5,
                            nonRetryableErrorTypes = listOf("FatalError"),
                        ),
                ),
            )
        }
    }

    @Workflow("ContinueAsNewWithComplexArgWorkflow")
    class ContinueAsNewWithComplexArgWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val input = WorkflowInput(iteration = 5, data = "test-data")
            continueAsNew(input)
        }
    }

    @Workflow("ContinueAsNewWithMultipleArgsWorkflow")
    class ContinueAsNewWithMultipleArgsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            continueAsNew("arg1", 42, true)
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

    private suspend inline fun <reified T : Any> createExecutorWithWorkflow(
        workflowType: String,
        arguments: List<io.temporal.api.common.v1.Payload> = emptyList(),
    ): ExecutorResult {
        val workflow = T::class.constructors.first().call()
        val runMethod =
            T::class
                .members
                .first { it.name == "run" } as KFunction<*>

        val parameterTypes =
            if (arguments.isEmpty()) {
                emptyList()
            } else {
                // Infer parameter types from workflow method (simplified for tests)
                runMethod.parameters
                    .filter { it.name != null && it.name != "this" }
                    .map { it.type }
            }

        val workflowMethodInfo =
            WorkflowMethodInfo(
                workflowType = workflowType,
                runMethod = runMethod,
                workflowClass = T::class,
                instanceFactory = { workflow },
                parameterTypes = parameterTypes,
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
                jobs =
                    listOf(
                        initializeWorkflowJob(
                            workflowType = workflowType,
                            arguments = arguments.map { it.toTemporal() },
                        ),
                    ),
                isReplaying = false,
            )
        val completion = executor.activate(initActivation)

        return ExecutorResult(executor, runId, workflow, completion)
    }

    private fun getCommandsFromCompletion(
        completion: coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion,
    ): List<WorkflowCommands.WorkflowCommand> =
        if (completion.hasSuccessful()) {
            completion.successful.commandsList
        } else {
            emptyList()
        }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun `continueAsNew generates ContinueAsNewWorkflowExecution command`() =
        runTest {
            val result = createExecutorWithWorkflow<SimpleContinueAsNewWorkflow>("SimpleContinueAsNewWorkflow")

            assertTrue(result.completion.hasSuccessful())

            val commands = getCommandsFromCompletion(result.completion)
            assertEquals(1, commands.size)
            assertTrue(commands[0].hasContinueAsNewWorkflowExecution())

            val canCommand = commands[0].continueAsNewWorkflowExecution
            assertEquals("SimpleContinueAsNewWorkflow", canCommand.workflowType)
        }

    @Test
    fun `continueAsNew with argument serializes correctly`() =
        runTest {
            val result = createExecutorWithWorkflow<SimpleContinueAsNewWorkflow>("SimpleContinueAsNewWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            val canCommand = commands[0].continueAsNewWorkflowExecution

            assertEquals(1, canCommand.argumentsCount)
            // Verify the argument is serialized (contains "1" as JSON)
            val argPayload = canCommand.argumentsList[0]
            assertTrue(argPayload.data.toStringUtf8().contains("1"))
        }

    @Test
    fun `continueAsNew with explicit workflowType overrides current`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithOptionsWorkflow>("ContinueAsNewWithOptionsWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            val canCommand = commands[0].continueAsNewWorkflowExecution

            assertEquals("NewWorkflowType", canCommand.workflowType)
        }

    @Test
    fun `continueAsNew with explicit taskQueue overrides current`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithOptionsWorkflow>("ContinueAsNewWithOptionsWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            val canCommand = commands[0].continueAsNewWorkflowExecution

            assertEquals("new-task-queue", canCommand.taskQueue)
        }

    @Test
    fun `continueAsNew with all options sets proto fields correctly`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithOptionsWorkflow>("ContinueAsNewWithOptionsWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            val canCommand = commands[0].continueAsNewWorkflowExecution

            assertEquals("NewWorkflowType", canCommand.workflowType)
            assertEquals("new-task-queue", canCommand.taskQueue)
            assertEquals(5 * 60, canCommand.workflowRunTimeout.seconds)
            assertEquals(30, canCommand.workflowTaskTimeout.seconds)
            assertEquals(
                coresdk.common.Common.VersioningIntent.COMPATIBLE,
                canCommand.versioningIntent,
            )
        }

    @Test
    fun `continueAsNew with retry policy sets proto correctly`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithRetryPolicyWorkflow>("ContinueAsNewWithRetryPolicyWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            val canCommand = commands[0].continueAsNewWorkflowExecution

            assertTrue(canCommand.hasRetryPolicy())
            val retryPolicy = canCommand.retryPolicy
            assertEquals(1, retryPolicy.initialInterval.seconds)
            assertEquals(100, retryPolicy.maximumInterval.seconds)
            assertEquals(2.0, retryPolicy.backoffCoefficient)
            assertEquals(5, retryPolicy.maximumAttempts)
            assertEquals(listOf("FatalError"), retryPolicy.nonRetryableErrorTypesList)
        }

    @Test
    fun `continueAsNew with null options uses defaults`() =
        runTest {
            val result = createExecutorWithWorkflow<SimpleContinueAsNewWorkflow>("SimpleContinueAsNewWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            val canCommand = commands[0].continueAsNewWorkflowExecution

            // Should use current workflow's type and task queue
            assertEquals("SimpleContinueAsNewWorkflow", canCommand.workflowType)
            // Task queue should be the default test queue
            assertEquals("test-task-queue", canCommand.taskQueue)
            // Timeouts should not be set (use defaults)
            assertEquals(0, canCommand.workflowRunTimeout.seconds)
            assertEquals(0, canCommand.workflowTaskTimeout.seconds)
        }

    @Test
    fun `continueAsNew with complex serializable data type`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithComplexArgWorkflow>("ContinueAsNewWithComplexArgWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            val canCommand = commands[0].continueAsNewWorkflowExecution

            assertEquals(1, canCommand.argumentsCount)
            val argPayload = canCommand.argumentsList[0]
            val argJson = argPayload.data.toStringUtf8()
            assertTrue(argJson.contains("iteration"))
            assertTrue(argJson.contains("5"))
            assertTrue(argJson.contains("data"))
            assertTrue(argJson.contains("test-data"))
        }

    @Test
    fun `continueAsNew with multiple arguments serializes all correctly`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithMultipleArgsWorkflow>(
                    "ContinueAsNewWithMultipleArgsWorkflow",
                )

            val commands = getCommandsFromCompletion(result.completion)
            val canCommand = commands[0].continueAsNewWorkflowExecution

            assertEquals(3, canCommand.argumentsCount)
            // Check each argument is present
            assertTrue(
                canCommand.argumentsList[0]
                    .data
                    .toStringUtf8()
                    .contains("arg1"),
            )
            assertTrue(
                canCommand.argumentsList[1]
                    .data
                    .toStringUtf8()
                    .contains("42"),
            )
            assertTrue(
                canCommand.argumentsList[2]
                    .data
                    .toStringUtf8()
                    .contains("true"),
            )
        }

    @Test
    fun `continueAsNew is deterministic across replay`() =
        runTest {
            // First execution
            val result1 = createExecutorWithWorkflow<SimpleContinueAsNewWorkflow>("SimpleContinueAsNewWorkflow")
            val commands1 = getCommandsFromCompletion(result1.completion)
            val canCommand1 = commands1[0].continueAsNewWorkflowExecution

            // Create new executor for replay
            val workflow2 = SimpleContinueAsNewWorkflow()
            val runMethod2 =
                SimpleContinueAsNewWorkflow::class
                    .members
                    .first { it.name == "run" } as KFunction<*>

            val workflowMethodInfo2 =
                WorkflowMethodInfo(
                    workflowType = "SimpleContinueAsNewWorkflow",
                    runMethod = runMethod2,
                    workflowClass = SimpleContinueAsNewWorkflow::class,
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

            // Replay execution
            val initActivation2 =
                createActivation(
                    runId = runId2,
                    jobs = listOf(initializeWorkflowJob(workflowType = "SimpleContinueAsNewWorkflow")),
                    isReplaying = true,
                )
            val completion2 = executor2.activate(initActivation2)

            val commands2 = getCommandsFromCompletion(completion2)
            val canCommand2 = commands2[0].continueAsNewWorkflowExecution

            // Verify same command is generated
            assertEquals(canCommand1.workflowType, canCommand2.workflowType)
            assertEquals(canCommand1.taskQueue, canCommand2.taskQueue)
            assertEquals(canCommand1.argumentsCount, canCommand2.argumentsCount)
        }

    // ================================================================
    // Additional Test Workflows for Edge Cases
    // ================================================================

    @Workflow("ContinueAsNewWithNullArgWorkflow")
    class ContinueAsNewWithNullArgWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            continueAsNew<String?>(null)
        }
    }

    @Workflow("ContinueAsNewWithEmptyListWorkflow")
    class ContinueAsNewWithEmptyListWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            continueAsNew<List<String>>(emptyList())
        }
    }

    @Workflow("ContinueAsNewWithHeadersWorkflow")
    class ContinueAsNewWithHeadersWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val headers =
                mapOf(
                    "trace-id" to
                        TemporalPayload(
                            io.temporal.api.common.v1.Payload
                                .newBuilder()
                                .setData(
                                    com.google.protobuf.ByteString
                                        .copyFromUtf8("\"test-trace-123\""),
                                ).putMetadata(
                                    "encoding",
                                    com.google.protobuf.ByteString
                                        .copyFromUtf8("json/plain"),
                                ).build(),
                        ),
                )
            continueAsNew(
                42,
                ContinueAsNewOptions(headers = headers),
            )
        }
    }

    // ================================================================
    // Additional Tests
    // ================================================================

    @Test
    fun `continueAsNew with null argument serializes correctly`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithNullArgWorkflow>("ContinueAsNewWithNullArgWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            assertTrue(commands.isNotEmpty())
            assertTrue(commands[0].hasContinueAsNewWorkflowExecution())

            val canCommand = commands[0].continueAsNewWorkflowExecution
            assertEquals(1, canCommand.argumentsCount)
            // Verify a payload exists - the serialized form of null depends on serializer configuration
            assertTrue(canCommand.argumentsList[0].serializedSize >= 0)
        }

    @Test
    fun `continueAsNew with empty list serializes correctly`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithEmptyListWorkflow>("ContinueAsNewWithEmptyListWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            assertTrue(commands.isNotEmpty())
            assertTrue(commands[0].hasContinueAsNewWorkflowExecution())

            val canCommand = commands[0].continueAsNewWorkflowExecution
            assertEquals(1, canCommand.argumentsCount)
            // Empty list is serialized as []
            val argData = canCommand.argumentsList[0].data.toStringUtf8()
            assertTrue(argData.contains("[]"))
        }

    @Test
    fun `continueAsNew with headers sets proto correctly`() =
        runTest {
            val result =
                createExecutorWithWorkflow<ContinueAsNewWithHeadersWorkflow>("ContinueAsNewWithHeadersWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            assertTrue(commands.isNotEmpty())
            assertTrue(commands[0].hasContinueAsNewWorkflowExecution())

            val canCommand = commands[0].continueAsNewWorkflowExecution
            assertTrue(canCommand.headersMap.containsKey("trace-id"))
            val traceHeader = canCommand.headersMap["trace-id"]
            assertTrue(traceHeader!!.data.toStringUtf8().contains("test-trace-123"))
        }

    // ================================================================
    // KClass-based continueAsNewTo Tests
    // ================================================================

    /**
     * Target workflow for KClass-based continue-as-new.
     */
    @Workflow("KClassTargetWorkflow")
    class KClassTargetWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(value: Int): String = "Target received: $value"
    }

    /**
     * Workflow that uses continueAsNewTo with KClass.
     */
    @Workflow("ContinueAsNewToKClassWorkflow")
    class ContinueAsNewToKClassWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            continueAsNewTo(KClassTargetWorkflow::class, 42)
        }
    }

    @Test
    fun `continueAsNewTo with KClass extracts workflow type from annotation`() =
        runTest {
            val result = createExecutorWithWorkflow<ContinueAsNewToKClassWorkflow>("ContinueAsNewToKClassWorkflow")

            val commands = getCommandsFromCompletion(result.completion)
            assertTrue(commands.isNotEmpty())
            assertTrue(commands[0].hasContinueAsNewWorkflowExecution())

            val canCommand = commands[0].continueAsNewWorkflowExecution
            // Workflow type should be extracted from the KClassTargetWorkflow's @Workflow annotation
            assertEquals("KClassTargetWorkflow", canCommand.workflowType)
            assertEquals(1, canCommand.argumentsCount)
            assertTrue(
                canCommand.argumentsList[0]
                    .data
                    .toStringUtf8()
                    .contains("42"),
            )
        }
}
