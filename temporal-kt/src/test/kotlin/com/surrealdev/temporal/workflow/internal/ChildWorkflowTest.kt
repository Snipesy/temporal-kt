package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.WorkflowRegistration
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.testing.ProtoTestHelpers.createActivation
import com.surrealdev.temporal.testing.ProtoTestHelpers.initializeWorkflowJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveChildWorkflowExecutionCancelledJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveChildWorkflowExecutionFailedJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveChildWorkflowExecutionJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveChildWorkflowStartCancelledJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveChildWorkflowStartFailedJob
import com.surrealdev.temporal.testing.ProtoTestHelpers.resolveChildWorkflowStartJob
import com.surrealdev.temporal.testing.createTestWorkflowExecutor
import com.surrealdev.temporal.workflow.ChildWorkflowHandle
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.ParentClosePolicy
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.startChildWorkflow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Comprehensive tests for child workflow functionality.
 *
 * Test categories:
 * 1. Command generation tests: verify StartChildWorkflowExecution commands
 * 2. Start resolution tests: success, failure, cancelled
 * 3. Execution resolution tests: completed, failed, cancelled
 * 4. Options tests: workflow ID, task queue, timeouts, policies
 * 5. Multiple child workflows tests
 * 6. Cancellation tests
 */
class ChildWorkflowTest {
    private val serializer = KotlinxJsonSerializer()

    // ================================================================
    // Test Workflow Classes
    // ================================================================

    @Serializable
    data class ChildResult(
        val value: String,
    )

    @Workflow("SimpleChildWorkflowParent")
    class SimpleChildWorkflowParent {
        var childResult: String? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            childResult = startChildWorkflow<String>("ChildWorkflow", ChildWorkflowOptions()).result()
            return "parent received: $childResult"
        }
    }

    @Workflow("ChildWorkflowWithArgsParent")
    class ChildWorkflowWithArgsParent {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String {
            val result =
                startChildWorkflow<ChildResult, String>(
                    "ChildWithArgs",
                    input,
                    ChildWorkflowOptions(),
                ).result()
            return "processed: ${result.value}"
        }
    }

    @Workflow("ChildWorkflowWithOptionsParent")
    class ChildWorkflowWithOptionsParent {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val options =
                ChildWorkflowOptions(
                    workflowId = "custom-child-id",
                    taskQueue = "custom-task-queue",
                    workflowExecutionTimeout = 10.minutes,
                    parentClosePolicy = ParentClosePolicy.ABANDON,
                )
            val result = startChildWorkflow<String>("ChildWorkflow", options).result()
            return result
        }
    }

    @Workflow("MultipleChildWorkflowsParent")
    class MultipleChildWorkflowsParent {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val result1 = startChildWorkflow<String>("Child1", ChildWorkflowOptions()).result()
            val result2 = startChildWorkflow<String>("Child2", ChildWorkflowOptions()).result()
            return "$result1 + $result2"
        }
    }

    @Workflow("ChildWorkflowHandleParent")
    class ChildWorkflowHandleParent {
        var handle: ChildWorkflowHandle<String>? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            handle = startChildWorkflow<String>("ChildWorkflow", ChildWorkflowOptions())
            return handle!!.result()
        }
    }

    @Workflow("ChildWorkflowCancelParent")
    class ChildWorkflowCancelParent {
        var handle: ChildWorkflowHandle<String>? = null

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            handle = startChildWorkflow<String>("ChildWorkflow", ChildWorkflowOptions())
            handle!!.cancel("Test cancellation")
            return "cancelled"
        }
    }

    // ================================================================
    // Command Generation Tests
    // ================================================================

    @Test
    fun `child workflow generates StartChildWorkflowExecution command`() =
        runTest {
            val result = createInitializedExecutor(SimpleChildWorkflowParent())

            // Get the commands from the completion (commands are drained during activate)
            val commands = getCommandsFromCompletion(result.completion)

            // Should have one StartChildWorkflowExecution command
            val childCommand = commands.find { it.hasStartChildWorkflowExecution() }
            assertNotNull(childCommand, "Should have StartChildWorkflowExecution command")

            val startChild = childCommand.startChildWorkflowExecution
            assertEquals("ChildWorkflow", startChild.workflowType)
            assertEquals(1, startChild.seq)
        }

    @Test
    fun `child workflow command includes custom workflow ID`() =
        runTest {
            val result = createInitializedExecutor(ChildWorkflowWithOptionsParent())

            val commands = getCommandsFromCompletion(result.completion)
            val childCommand = commands.find { it.hasStartChildWorkflowExecution() }
            assertNotNull(childCommand)

            assertEquals("custom-child-id", childCommand.startChildWorkflowExecution.workflowId)
        }

    @Test
    fun `child workflow command includes custom task queue`() =
        runTest {
            val result = createInitializedExecutor(ChildWorkflowWithOptionsParent())

            val commands = getCommandsFromCompletion(result.completion)
            val childCommand = commands.find { it.hasStartChildWorkflowExecution() }
            assertNotNull(childCommand)

            assertEquals("custom-task-queue", childCommand.startChildWorkflowExecution.taskQueue)
        }

    @Test
    fun `child workflow command includes parent close policy`() =
        runTest {
            val result = createInitializedExecutor(ChildWorkflowWithOptionsParent())

            val commands = getCommandsFromCompletion(result.completion)
            val childCommand = commands.find { it.hasStartChildWorkflowExecution() }
            assertNotNull(childCommand)

            assertEquals(
                coresdk.child_workflow.ChildWorkflow.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON,
                childCommand.startChildWorkflowExecution.parentClosePolicy,
            )
        }

    @Test
    fun `child workflow auto-generates workflow ID when not specified`() =
        runTest {
            val result = createInitializedExecutor(SimpleChildWorkflowParent())

            val commands = getCommandsFromCompletion(result.completion)
            val childCommand = commands.find { it.hasStartChildWorkflowExecution() }
            assertNotNull(childCommand)

            // Should be parent workflow ID + child + seq
            assertTrue(childCommand.startChildWorkflowExecution.workflowId.contains("-child-"))
        }

    // ================================================================
    // Start Resolution Tests
    // ================================================================

    @Test
    fun `child workflow start success sets run ID on handle`() =
        runTest {
            val workflow = ChildWorkflowHandleParent()
            val result = createInitializedExecutor(workflow)

            // Resolve start success
            val childRunId = UUID.randomUUID().toString()
            val startActivation =
                createActivation(
                    runId = result.runId,
                    jobs = listOf(resolveChildWorkflowStartJob(seq = 1, runId = childRunId)),
                )
            result.executor.activate(startActivation)

            // Handle should now have the run ID
            assertNotNull(workflow.handle)
            assertEquals(childRunId, workflow.handle!!.firstExecutionRunId)
        }

    @Test
    fun `child workflow start failure throws ChildWorkflowStartFailureException`() =
        runTest {
            val workflow = ChildWorkflowHandleParent()
            val result = createInitializedExecutor(workflow)

            // Resolve start failure
            val startActivation =
                createActivation(
                    runId = result.runId,
                    jobs =
                        listOf(
                            resolveChildWorkflowStartFailedJob(
                                seq = 1,
                                workflowId = "test-child-id",
                                workflowType = "ChildWorkflow",
                            ),
                        ),
                )
            val completion = result.executor.activate(startActivation)

            // Workflow should fail with FailWorkflowExecution command
            assertTrue(completion.hasSuccessful())
            val failCommand = getCommandsFromCompletion(completion).find { it.hasFailWorkflowExecution() }
            assertNotNull(failCommand, "Should have FailWorkflowExecution command")
        }

    @Test
    fun `child workflow start cancelled throws ChildWorkflowCancelledException`() =
        runTest {
            val workflow = ChildWorkflowHandleParent()
            val result = createInitializedExecutor(workflow)

            // Resolve start cancelled
            val startActivation =
                createActivation(
                    runId = result.runId,
                    jobs =
                        listOf(
                            resolveChildWorkflowStartCancelledJob(
                                seq = 1,
                                message = "Start cancelled",
                            ),
                        ),
                )
            val completion = result.executor.activate(startActivation)

            // Workflow should fail with FailWorkflowExecution command
            assertTrue(completion.hasSuccessful())
            val failCommand = getCommandsFromCompletion(completion).find { it.hasFailWorkflowExecution() }
            assertNotNull(failCommand, "Should have FailWorkflowExecution command")
        }

    // ================================================================
    // Execution Resolution Tests
    // ================================================================

    @Test
    fun `child workflow execution completed returns result`() =
        runTest {
            val workflow = SimpleChildWorkflowParent()
            val result = createInitializedExecutor(workflow)

            // Resolve start success then execution
            val startActivation =
                createActivation(
                    runId = result.runId,
                    jobs = listOf(resolveChildWorkflowStartJob(seq = 1)),
                )
            result.executor.activate(startActivation)

            val resultPayload = serializer.serialize("child result")
            val execActivation =
                createActivation(
                    runId = result.runId,
                    jobs = listOf(resolveChildWorkflowExecutionJob(seq = 1, result = resultPayload)),
                )
            val completion = result.executor.activate(execActivation)

            assertTrue(completion.hasSuccessful())
            assertEquals("child result", workflow.childResult)
        }

    @Test
    fun `child workflow execution failed throws ChildWorkflowFailureException`() =
        runTest {
            val workflow = SimpleChildWorkflowParent()
            val result = createInitializedExecutor(workflow)

            // Resolve start success then execution failure
            val startActivation =
                createActivation(
                    runId = result.runId,
                    jobs = listOf(resolveChildWorkflowStartJob(seq = 1)),
                )
            result.executor.activate(startActivation)

            val execActivation =
                createActivation(
                    runId = result.runId,
                    jobs =
                        listOf(
                            resolveChildWorkflowExecutionFailedJob(
                                seq = 1,
                                message = "Child failed",
                            ),
                        ),
                )
            val completion = result.executor.activate(execActivation)

            // Workflow should fail with FailWorkflowExecution command
            assertTrue(completion.hasSuccessful())
            val failCommand = getCommandsFromCompletion(completion).find { it.hasFailWorkflowExecution() }
            assertNotNull(failCommand, "Should have FailWorkflowExecution command")
        }

    @Test
    fun `child workflow execution cancelled throws ChildWorkflowCancelledException`() =
        runTest {
            val workflow = SimpleChildWorkflowParent()
            val result = createInitializedExecutor(workflow)

            // Resolve start success then execution cancelled
            val startActivation =
                createActivation(
                    runId = result.runId,
                    jobs = listOf(resolveChildWorkflowStartJob(seq = 1)),
                )
            result.executor.activate(startActivation)

            val execActivation =
                createActivation(
                    runId = result.runId,
                    jobs =
                        listOf(
                            resolveChildWorkflowExecutionCancelledJob(
                                seq = 1,
                                message = "Child cancelled",
                            ),
                        ),
                )
            val completion = result.executor.activate(execActivation)

            // Workflow should fail with FailWorkflowExecution command
            assertTrue(completion.hasSuccessful())
            val failCommand = getCommandsFromCompletion(completion).find { it.hasFailWorkflowExecution() }
            assertNotNull(failCommand, "Should have FailWorkflowExecution command")
        }

    // ================================================================
    // Multiple Child Workflows Tests
    // ================================================================

    @Test
    fun `multiple child workflows generate sequential commands`() =
        runTest {
            val result = createInitializedExecutor(MultipleChildWorkflowsParent())

            val commands = getCommandsFromCompletion(result.completion)
            val childCommands = commands.filter { it.hasStartChildWorkflowExecution() }

            // First child should be started, second waits for first to complete
            assertEquals(1, childCommands.size)
            assertEquals("Child1", childCommands[0].startChildWorkflowExecution.workflowType)
        }

    @Test
    fun `sequential child workflows complete in order`() =
        runTest {
            val workflow = MultipleChildWorkflowsParent()
            val result = createInitializedExecutor(workflow)

            // Complete first child
            val start1 = createActivation(runId = result.runId, jobs = listOf(resolveChildWorkflowStartJob(seq = 1)))
            result.executor.activate(start1)

            val result1 = serializer.serialize("result1")
            val exec1 =
                createActivation(
                    runId = result.runId,
                    jobs = listOf(resolveChildWorkflowExecutionJob(seq = 1, result = result1)),
                )
            val exec1Completion = result.executor.activate(exec1)

            // Now second child should be started - get commands from the completion
            val commands = getCommandsFromCompletion(exec1Completion)
            val child2Command = commands.find { it.hasStartChildWorkflowExecution() }
            assertNotNull(child2Command)
            assertEquals("Child2", child2Command.startChildWorkflowExecution.workflowType)
        }

    // ================================================================
    // Cancellation Tests
    // ================================================================

    @Test
    fun `cancel child workflow generates CancelChildWorkflowExecution command`() =
        runTest {
            val workflow = ChildWorkflowCancelParent()
            val result = createInitializedExecutor(workflow)

            val commands = getCommandsFromCompletion(result.completion)

            // Should have both start and cancel commands
            val startCommand = commands.find { it.hasStartChildWorkflowExecution() }
            val cancelCommand = commands.find { it.hasCancelChildWorkflowExecution() }

            assertNotNull(startCommand, "Should have start command")
            assertNotNull(cancelCommand, "Should have cancel command")
            assertEquals(1, cancelCommand.cancelChildWorkflowExecution.childWorkflowSeq)
        }

    // ================================================================
    // Handle Tests
    // ================================================================

    @Test
    fun `child workflow handle has correct ID`() =
        runTest {
            val workflow = ChildWorkflowHandleParent()
            createInitializedExecutor(workflow)

            assertNotNull(workflow.handle)
            assertTrue(workflow.handle!!.id.contains("-child-"))
        }

    @Test
    fun `child workflow handle firstExecutionRunId is null before start resolution`() =
        runTest {
            val workflow = ChildWorkflowHandleParent()
            createInitializedExecutor(workflow)

            assertNotNull(workflow.handle)
            assertNull(workflow.handle!!.firstExecutionRunId)
        }

    // ================================================================
    // Helper Methods
    // ================================================================

    /**
     * Data class to hold the result of initializing an executor.
     */
    private data class ExecutorInitResult(
        val executor: WorkflowExecutor,
        val runId: String,
        val completion: coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion,
    )

    private suspend fun createInitializedExecutor(workflowImpl: Any): ExecutorInitResult {
        val klass = workflowImpl::class
        val workflowAnnotation = klass.findAnnotation<Workflow>()
        val workflowType =
            workflowAnnotation?.name?.takeIf { it.isNotBlank() }
                ?: klass.simpleName
                ?: error("Cannot determine workflow type")

        val registry = WorkflowRegistry()
        registry.register(
            WorkflowRegistration(
                workflowType = workflowType,
                implementation = workflowImpl,
                instanceFactory = { workflowImpl },
            ),
        )

        val methodInfo =
            registry.lookup(workflowType)
                ?: error("Workflow not found: $workflowType")

        val runId = UUID.randomUUID().toString()

        val executor =
            createTestWorkflowExecutor(
                runId = runId,
                methodInfo = methodInfo,
                serializer = serializer,
            )

        // Initialize the workflow
        val initActivation =
            createActivation(
                runId = runId,
                jobs = listOf(initializeWorkflowJob(workflowType = workflowType)),
            )
        val completion = executor.activate(initActivation)

        return ExecutorInitResult(executor, runId, completion)
    }

    /**
     * Helper to get commands from the completion (since drainCommands is called during activate).
     */
    private fun getCommandsFromCompletion(
        completion: coresdk.workflow_completion.WorkflowCompletion.WorkflowActivationCompletion,
    ): List<coresdk.workflow_commands.WorkflowCommands.WorkflowCommand> =
        if (completion.hasSuccessful()) {
            completion.successful.commandsList
        } else {
            emptyList()
        }
}
