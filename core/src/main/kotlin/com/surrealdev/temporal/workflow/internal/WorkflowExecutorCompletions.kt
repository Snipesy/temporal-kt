package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.workflow.ContinueAsNewException
import com.surrealdev.temporal.workflow.VersioningIntent
import coresdk.workflow_commands.WorkflowCommands
import coresdk.workflow_completion.WorkflowCompletion
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.Deferred
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/*
 * Extension functions for building workflow completion responses in WorkflowExecutor.
 */

/**
 * Builds a terminal completion when the main workflow coroutine completes.
 *
 * This handles three outcomes:
 * 1. Successful completion with a result
 * 2. Workflow failure due to an exception
 * 3. Workflow cancellation
 */
internal suspend fun WorkflowExecutor.buildTerminalCompletion(
    result: Deferred<Any?>,
    returnType: kotlin.reflect.KType,
): WorkflowCompletion.WorkflowActivationCompletion {
    terminateWorkflowExecutionJob()

    return try {
        val value = result.await()
        logger.debug(
            "Workflow completed successfully with result type: {}",
            value?.let { it::class.simpleName } ?: "null",
        )

        // Serialize the result
        val resultPayload =
            if (methodInfo.returnType.classifier == Unit::class) {
                Payload.getDefaultInstance()
            } else {
                serializer.serialize(returnType, value)
            }

        // Build completion command
        val completeCommand =
            WorkflowCommands.WorkflowCommand
                .newBuilder()
                .setCompleteWorkflowExecution(
                    WorkflowCommands.CompleteWorkflowExecution
                        .newBuilder()
                        .setResult(resultPayload),
                ).build()

        // Get any pending commands and add the completion
        val commands = state.drainCommands().toMutableList()
        commands.add(completeCommand)

        WorkflowCompletion.WorkflowActivationCompletion
            .newBuilder()
            .setRunId(runId)
            .setSuccessful(
                WorkflowCompletion.Success
                    .newBuilder()
                    .addAllCommands(commands),
            ).build()
    } catch (e: ContinueAsNewException) {
        // Handle continue-as-new (this is not an error, it's a control flow mechanism)
        logger.debug("Workflow requested continue-as-new")
        buildContinueAsNewCompletion(e)
    } catch (e: Exception) {
        // Check if this is a cancellation with the cancel flag set
        if (state.cancelRequested && e is kotlinx.coroutines.CancellationException) {
            logger.debug("Workflow cancelled")
            buildWorkflowCancellationCompletion()
        } else {
            logger.debug("Workflow failed with exception: {}", e.message, e)
            buildWorkflowFailureCompletion(e)
        }
    }
}

/**
 * Builds a success completion with accumulated commands.
 * Used for non-terminal activations (e.g., after processing queries or when workflow is still running).
 */
internal fun WorkflowExecutor.buildSuccessCompletion(): WorkflowCompletion.WorkflowActivationCompletion {
    val commands = state.drainCommands()
    logger.debug("Returning {} commands", commands.size)

    return WorkflowCompletion.WorkflowActivationCompletion
        .newBuilder()
        .setRunId(runId)
        .setSuccessful(
            WorkflowCompletion.Success
                .newBuilder()
                .addAllCommands(commands),
        ).build()
}

/**
 * Builds a failure completion for system-level errors during activation processing.
 * This is different from workflow failure - it indicates the SDK itself encountered an error.
 */
internal fun WorkflowExecutor.buildFailureCompletion(
    exception: Exception,
): WorkflowCompletion.WorkflowActivationCompletion {
    val failure =
        Failure
            .newBuilder()
            .setMessage(exception.message ?: exception::class.simpleName ?: "Unknown error")
            .setStackTrace(exception.stackTraceToString())
            .setSource("Kotlin")
            .build()

    return WorkflowCompletion.WorkflowActivationCompletion
        .newBuilder()
        .setRunId(runId)
        .setFailed(
            WorkflowCompletion.Failure
                .newBuilder()
                .setFailure(failure),
        ).build()
}

/**
 * Builds a workflow failure completion when the workflow code throws an exception.
 * This creates a FailWorkflowExecution command indicating the workflow failed.
 */
internal fun WorkflowExecutor.buildWorkflowFailureCompletion(
    exception: Exception,
): WorkflowCompletion.WorkflowActivationCompletion {
    // Build a workflow failure command
    val failure =
        Failure
            .newBuilder()
            .setMessage(exception.message ?: exception::class.simpleName ?: "Unknown error")
            .setStackTrace(exception.stackTraceToString())
            .setSource("Kotlin")
            .build()

    val failCommand =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setFailWorkflowExecution(
                WorkflowCommands.FailWorkflowExecution
                    .newBuilder()
                    .setFailure(failure),
            ).build()

    val commands = state.drainCommands().toMutableList()
    commands.add(failCommand)

    return WorkflowCompletion.WorkflowActivationCompletion
        .newBuilder()
        .setRunId(runId)
        .setSuccessful(
            WorkflowCompletion.Success
                .newBuilder()
                .addAllCommands(commands),
        ).build()
}

/**
 * Builds a workflow cancellation completion when the workflow is cancelled.
 * This creates a CancelWorkflowExecution command.
 */
internal fun WorkflowExecutor.buildWorkflowCancellationCompletion(): WorkflowCompletion.WorkflowActivationCompletion {
    // Build a workflow cancellation command
    val cancelCommand =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setCancelWorkflowExecution(
                WorkflowCommands.CancelWorkflowExecution.getDefaultInstance(),
            ).build()

    val commands = state.drainCommands().toMutableList()
    commands.add(cancelCommand)

    return WorkflowCompletion.WorkflowActivationCompletion
        .newBuilder()
        .setRunId(runId)
        .setSuccessful(
            WorkflowCompletion.Success
                .newBuilder()
                .addAllCommands(commands),
        ).build()
}

/**
 * Builds a continue-as-new completion when the workflow calls continueAsNew().
 * This creates a ContinueAsNewWorkflowExecution command.
 */
internal fun WorkflowExecutor.buildContinueAsNewCompletion(
    exception: ContinueAsNewException,
): WorkflowCompletion.WorkflowActivationCompletion {
    val options = exception.options

    // Build the ContinueAsNewWorkflowExecution command
    val commandBuilder =
        WorkflowCommands.ContinueAsNewWorkflowExecution
            .newBuilder()
            .setWorkflowType(options.workflowType ?: methodInfo.workflowType)
            .setTaskQueue(options.taskQueue ?: taskQueue)

    // Serialize and add arguments with their type information
    exception.typedArgs.forEach { (type, value) ->
        val payload = serializer.serialize(type, value)
        commandBuilder.addArguments(payload)
    }

    // Set optional fields if provided
    options.workflowRunTimeout?.let {
        commandBuilder.setWorkflowRunTimeout(it.toProtoDuration())
    }
    options.workflowTaskTimeout?.let {
        commandBuilder.setWorkflowTaskTimeout(it.toProtoDuration())
    }
    options.memo?.let { memo ->
        commandBuilder.putAllMemo(memo)
    }
    options.searchAttributes?.let { attrs ->
        commandBuilder.putAllSearchAttributes(attrs)
    }
    options.retryPolicy?.let { policy ->
        commandBuilder.setRetryPolicy(policy.toProtoRetryPolicy())
    }
    options.headers?.let { headers ->
        commandBuilder.putAllHeaders(headers)
    }
    commandBuilder.setVersioningIntent(options.versioningIntent.toProtoVersioningIntent())

    val continueAsNewCommand =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setContinueAsNewWorkflowExecution(commandBuilder)
            .build()

    // Get any pending commands and add the continue-as-new command
    val commands = state.drainCommands().toMutableList()
    commands.add(continueAsNewCommand)

    return WorkflowCompletion.WorkflowActivationCompletion
        .newBuilder()
        .setRunId(runId)
        .setSuccessful(
            WorkflowCompletion.Success
                .newBuilder()
                .addAllCommands(commands),
        ).build()
}

// =============================================================================
// Conversion Utilities for Continue-As-New
// =============================================================================

/**
 * Converts a Kotlin [Duration] to a protobuf [com.google.protobuf.Duration].
 */
private fun Duration.toProtoDuration(): com.google.protobuf.Duration {
    val javaDuration = this.toJavaDuration()
    return com.google.protobuf.Duration
        .newBuilder()
        .setSeconds(javaDuration.seconds)
        .setNanos(javaDuration.nano)
        .build()
}

/**
 * Converts domain [VersioningIntent] to protobuf enum.
 */
private fun VersioningIntent.toProtoVersioningIntent(): coresdk.common.Common.VersioningIntent =
    when (this) {
        VersioningIntent.UNSPECIFIED -> coresdk.common.Common.VersioningIntent.UNSPECIFIED
        VersioningIntent.DEFAULT -> coresdk.common.Common.VersioningIntent.DEFAULT
        VersioningIntent.COMPATIBLE -> coresdk.common.Common.VersioningIntent.COMPATIBLE
    }

/**
 * Converts domain [com.surrealdev.temporal.workflow.RetryPolicy] to protobuf message.
 */
private fun com.surrealdev.temporal.workflow.RetryPolicy.toProtoRetryPolicy(): io.temporal.api.common.v1.RetryPolicy {
    val retryPolicyBuilder =
        io.temporal.api.common.v1.RetryPolicy
            .newBuilder()
            .setInitialInterval(initialInterval.toProtoDuration())
            .setBackoffCoefficient(backoffCoefficient)
            .setMaximumAttempts(maximumAttempts)

    maximumInterval?.let {
        retryPolicyBuilder.setMaximumInterval(it.toProtoDuration())
    }

    if (nonRetryableErrorTypes.isNotEmpty()) {
        retryPolicyBuilder.addAllNonRetryableErrorTypes(nonRetryableErrorTypes)
    }

    return retryPolicyBuilder.build()
}
