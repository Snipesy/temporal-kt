package com.surrealdev.temporal.client

import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.serialization.typeInfoOf
import io.temporal.api.common.v1.Payloads
import java.util.UUID

/**
 * Starts a new workflow execution.
 *
 * @param R The expected result type of the workflow.
 * @param workflowType The workflow type name.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param args Arguments to pass to the workflow.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified R> TemporalClient.startWorkflow(
    workflowType: String,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    args: Payloads,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle<R> =
    this.startWorkflowInternal(
        workflowType = workflowType,
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = args,
        options = options,
        resultTypeInfo = typeInfoOf<R>(),
    )

/**
 * Starts a new workflow execution.
 *
 * @param R The expected result type of the workflow.
 * @param T The type of the argument to pass to the workflow.
 * @param workflowType The workflow type name.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg Argument to pass to the workflow.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified R, reified T> TemporalClient.startWorkflow(
    workflowType: String,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg: T,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(serializer.serialize(arg))

    return this.startWorkflowInternal(
        workflowType = workflowType,
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloadsBuilder.build(),
        options = options,
        resultTypeInfo = typeInfoOf<R>(),
    )
}

/**
 * Starts a new workflow execution without arguments.
 *
 * @param R The expected result type of the workflow.
 * @param T The type of the argument to pass to the workflow.
 * @param workflowType The workflow type name.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified R> TemporalClient.startWorkflow(
    workflowType: String,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle<R> {
    val payloadsBuilder = Payloads.newBuilder()
    return this.startWorkflowInternal(
        workflowType = workflowType,
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloadsBuilder.build(),
        options = options,
        resultTypeInfo = typeInfoOf<R>(),
    )
}
