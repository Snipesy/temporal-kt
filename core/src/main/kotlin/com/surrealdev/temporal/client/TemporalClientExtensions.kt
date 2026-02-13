package com.surrealdev.temporal.client

import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.serialization.serialize
import com.surrealdev.temporal.workflow.getWorkflowType
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Starts a new workflow execution without arguments.
 *
 * @param workflowType The workflow type name.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend fun TemporalClient.startWorkflow(
    workflowType: String,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle =
    this.startWorkflowWithPayloads(
        workflowType = workflowType,
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = TemporalPayloads.EMPTY.toProto(),
        options = options,
    )

/**
 * Starts a new workflow execution with a single argument.
 *
 * @param T The type of the argument.
 * @param workflowType The workflow type name.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg The argument to pass to the workflow.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified T> TemporalClient.startWorkflow(
    workflowType: String,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg: T,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle {
    val payload = serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startWorkflowWithPayloads(
        workflowType = workflowType,
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloads.toProto(),
        options = options,
    )
}

/**
 * Starts a new workflow execution with two arguments.
 *
 * @param T1 The type of the first argument.
 * @param T2 The type of the second argument.
 * @param workflowType The workflow type name.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified T1, reified T2> TemporalClient.startWorkflow(
    workflowType: String,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg1: T1,
    arg2: T2,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startWorkflowWithPayloads(
        workflowType = workflowType,
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloads.toProto(),
        options = options,
    )
}

/**
 * Starts a new workflow execution with three arguments.
 *
 * @param T1 The type of the first argument.
 * @param T2 The type of the second argument.
 * @param T3 The type of the third argument.
 * @param workflowType The workflow type name.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param arg3 The third argument.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified T1, reified T2, reified T3> TemporalClient.startWorkflow(
    workflowType: String,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payload3 = serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startWorkflowWithPayloads(
        workflowType = workflowType,
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloads.toProto(),
        options = options,
    )
}

/**
 * Starts a new workflow execution with four arguments.
 *
 * @param T1 The type of the first argument.
 * @param T2 The type of the second argument.
 * @param T3 The type of the third argument.
 * @param T4 The type of the fourth argument.
 * @param workflowType The workflow type name.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param arg3 The third argument.
 * @param arg4 The fourth argument.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified T1, reified T2, reified T3, reified T4> TemporalClient.startWorkflow(
    workflowType: String,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payload3 = serializer.serialize(arg3)
    val payload4 = serializer.serialize(arg4)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3, payload4))

    return this.startWorkflowWithPayloads(
        workflowType = workflowType,
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloads.toProto(),
        options = options,
    )
}

// =============================================================================
// KClass-Based Overloads
// =============================================================================

/**
 * Starts a new workflow execution using a workflow class reference without arguments.
 *
 * The workflow type is automatically determined from the @Workflow annotation
 * or the class name.
 *
 * @param workflowClass The workflow class annotated with @Workflow.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend fun TemporalClient.startWorkflow(
    workflowClass: KClass<*>,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle =
    this.startWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = TemporalPayloads.EMPTY.toProto(),
        options = options,
    )

/**
 * Starts a new workflow execution using a workflow class reference with a single argument.
 *
 * @param T The type of the argument.
 * @param workflowClass The workflow class annotated with @Workflow.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg The argument to pass to the workflow.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified T> TemporalClient.startWorkflow(
    workflowClass: KClass<*>,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg: T,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle {
    val payload = serializer.serialize(arg)
    val payloads = TemporalPayloads.of(listOf(payload))

    return this.startWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloads.toProto(),
        options = options,
    )
}

/**
 * Starts a new workflow execution using a workflow class reference with two arguments.
 *
 * @param T1 The type of the first argument.
 * @param T2 The type of the second argument.
 * @param workflowClass The workflow class annotated with @Workflow.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified T1, reified T2> TemporalClient.startWorkflow(
    workflowClass: KClass<*>,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg1: T1,
    arg2: T2,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2))

    return this.startWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloads.toProto(),
        options = options,
    )
}

/**
 * Starts a new workflow execution using a workflow class reference with three arguments.
 *
 * @param T1 The type of the first argument.
 * @param T2 The type of the second argument.
 * @param T3 The type of the third argument.
 * @param workflowClass The workflow class annotated with @Workflow.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param arg3 The third argument.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified T1, reified T2, reified T3> TemporalClient.startWorkflow(
    workflowClass: KClass<*>,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg1: T1,
    arg2: T2,
    arg3: T3,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payload3 = serializer.serialize(arg3)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3))

    return this.startWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloads.toProto(),
        options = options,
    )
}

/**
 * Starts a new workflow execution using a workflow class reference with four arguments.
 *
 * @param T1 The type of the first argument.
 * @param T2 The type of the second argument.
 * @param T3 The type of the third argument.
 * @param T4 The type of the fourth argument.
 * @param workflowClass The workflow class annotated with @Workflow.
 * @param taskQueue The task queue to run the workflow on.
 * @param workflowId The workflow ID. Auto-generated if not specified.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param arg3 The third argument.
 * @param arg4 The fourth argument.
 * @param options Additional workflow options.
 * @return A handle to the started workflow execution.
 */
suspend inline fun <reified T1, reified T2, reified T3, reified T4> TemporalClient.startWorkflow(
    workflowClass: KClass<*>,
    taskQueue: String,
    workflowId: String = UUID.randomUUID().toString(),
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4,
    options: WorkflowStartOptions = WorkflowStartOptions(),
): WorkflowHandle {
    val payload1 = serializer.serialize(arg1)
    val payload2 = serializer.serialize(arg2)
    val payload3 = serializer.serialize(arg3)
    val payload4 = serializer.serialize(arg4)
    val payloads = TemporalPayloads.of(listOf(payload1, payload2, payload3, payload4))

    return this.startWorkflowWithPayloads(
        workflowType = workflowClass.getWorkflowType(),
        taskQueue = taskQueue,
        workflowId = workflowId,
        args = payloads.toProto(),
        options = options,
    )
}
