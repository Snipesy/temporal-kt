package com.surrealdev.temporal.testing

import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.SimpleAttributeScope
import com.surrealdev.temporal.workflow.internal.WorkflowExecutor
import com.surrealdev.temporal.workflow.internal.WorkflowMethodInfo
import kotlinx.coroutines.Job

/**
 * Creates a stub TemporalApplication for use in tests.
 *
 * This application is not started and is only used to satisfy
 * constructor parameters that require an application instance.
 */
internal fun createStubApplication(): TemporalApplication =
    TemporalApplication {
        connection {
            target = "localhost:7233"
            namespace = "test"
        }
    }

/**
 * A minimal workflow that parks forever, for tests that exercise activation/job
 * processing without caring about the workflow body. Unlike a dummy reflection
 * target, this is a real suspend workflow: the executor keeps it alive across
 * activations and never emits terminal commands or task failures on its behalf.
 */
class IdleTestWorkflow {
    suspend fun com.surrealdev.temporal.workflow.WorkflowContext.run(): String {
        awaitCondition { false }
        return "unreachable"
    }
}

/**
 * Creates a WorkflowExecutor whose workflow is an [IdleTestWorkflow] parked on
 * `awaitCondition { false }` - useful for job-processing tests.
 */
internal fun createIdleTestWorkflowExecutor(
    runId: String = "test-run-id",
    workflowType: String = "TestWorkflow",
    serializer: PayloadSerializer = CompositePayloadSerializer.default(),
): WorkflowExecutor {
    val workflow = IdleTestWorkflow()
    val runMethod =
        IdleTestWorkflow::class
            .members
            .first { it.name == "run" } as kotlin.reflect.KFunction<*>
    val methodInfo =
        WorkflowMethodInfo(
            workflowType = workflowType,
            runMethod = runMethod,
            workflowClass = IdleTestWorkflow::class,
            instanceFactory = { workflow },
            parameterTypes = emptyList(),
            returnType = kotlin.reflect.typeOf<String>(),
            hasContextReceiver = true,
            isSuspend = true,
        )
    return createTestWorkflowExecutor(
        runId = runId,
        methodInfo = methodInfo,
        serializer = serializer,
    )
}

/**
 * Creates a WorkflowExecutor for use in tests.
 *
 * @param runId The workflow run ID
 * @param methodInfo The workflow method info
 * @param serializer Optional serializer (defaults to CompositePayloadSerializer)
 * @param taskQueue Optional task queue name (defaults to "test-task-queue")
 * @param namespace Optional namespace (defaults to "default")
 * @param taskQueueScope Optional task queue scope (defaults to simple scope with stub app as parent)
 * @param parentJob Optional parent job for structured concurrency (defaults to a new Job)
 */
internal fun createTestWorkflowExecutor(
    runId: String = "test-run-id",
    methodInfo: WorkflowMethodInfo,
    serializer: PayloadSerializer = CompositePayloadSerializer.default(),
    codec: PayloadCodec = com.surrealdev.temporal.serialization.NoOpCodec,
    taskQueue: String = "test-task-queue",
    namespace: String = "default",
    taskQueueScope: AttributeScope =
        SimpleAttributeScope(
            attributes = Attributes(concurrent = false),
            parentScope = createStubApplication(),
        ),
    parentJob: Job = Job(),
): WorkflowExecutor =
    WorkflowExecutor(
        runId = runId,
        methodInfo = methodInfo,
        serializer = serializer,
        codec = codec,
        taskQueue = taskQueue,
        namespace = namespace,
        taskQueueScope = taskQueueScope,
        parentJob = parentJob,
    )
