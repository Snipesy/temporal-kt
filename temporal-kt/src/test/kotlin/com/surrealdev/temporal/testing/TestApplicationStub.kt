package com.surrealdev.temporal.testing

import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
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
            target = "http://localhost:7233"
            namespace = "test"
        }
    }

/**
 * Creates a WorkflowExecutor for use in tests.
 *
 * @param runId The workflow run ID
 * @param methodInfo The workflow method info
 * @param serializer Optional serializer (defaults to KotlinxJsonSerializer)
 * @param taskQueue Optional task queue name (defaults to "test-task-queue")
 * @param namespace Optional namespace (defaults to "default")
 * @param taskQueueScope Optional task queue scope (defaults to simple scope with stub app as parent)
 * @param parentJob Optional parent job for structured concurrency (defaults to a new Job)
 */
internal fun createTestWorkflowExecutor(
    runId: String = "test-run-id",
    methodInfo: WorkflowMethodInfo,
    serializer: PayloadSerializer = KotlinxJsonSerializer(),
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
        taskQueue = taskQueue,
        namespace = namespace,
        taskQueueScope = taskQueueScope,
        parentJob = parentJob,
    )
