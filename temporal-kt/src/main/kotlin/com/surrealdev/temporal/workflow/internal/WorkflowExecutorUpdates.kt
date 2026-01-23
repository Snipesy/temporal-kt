package com.surrealdev.temporal.workflow.internal

import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.extensionReceiverParameter

/*
 * Extension functions for handling workflow updates in WorkflowExecutor.
 */

/**
 * Handles a workflow update job by routing to the appropriate handler.
 *
 * Handler priority:
 * 1. Runtime handler for specific update
 * 2. Annotation handler for specific update
 * 3. Runtime dynamic handler
 * 4. Annotation dynamic handler
 */
internal suspend fun WorkflowExecutor.handleUpdate(
    update: coresdk.workflow_activation.WorkflowActivationOuterClass.DoUpdate,
) {
    val updateName = update.name
    val protocolInstanceId = update.protocolInstanceId
    val inputPayloads = update.inputList
    val runValidator = update.runValidator

    logger.debug(
        "Processing update: name={}, id={}, protocol_instance_id={}, run_validator={}",
        updateName,
        update.id,
        protocolInstanceId,
        runValidator,
    )

    val ctx = context

    // Check runtime-registered handlers first (they take precedence)
    val runtimeHandler = ctx?.runtimeUpdateHandlers?.get(updateName)
    val runtimeDynamicHandler = ctx?.runtimeDynamicUpdateHandler

    // Then check annotation-defined handlers
    val annotationHandler = methodInfo.updateHandlers[updateName]
    val annotationDynamicHandler = methodInfo.updateHandlers[null]

    // Determine which handler to use
    when {
        runtimeHandler != null -> {
            invokeRuntimeUpdateHandler(
                runtimeHandler,
                protocolInstanceId,
                inputPayloads,
                runValidator,
            )
        }

        annotationHandler != null -> {
            invokeAnnotationUpdateHandler(
                annotationHandler,
                update,
                isDynamic = false,
            )
        }

        runtimeDynamicHandler != null -> {
            invokeRuntimeDynamicUpdateHandler(
                runtimeDynamicHandler,
                protocolInstanceId,
                updateName,
                inputPayloads,
                runValidator,
            )
        }

        annotationDynamicHandler != null -> {
            invokeAnnotationUpdateHandler(
                annotationDynamicHandler,
                update,
                isDynamic = true,
            )
        }

        else -> {
            // Unlike signals, updates fail immediately if no handler exists
            logger.debug("No handler found for update '{}', rejecting", updateName)
            addUpdateRejectedCommand(protocolInstanceId, "Unknown update type: $updateName")
        }
    }
}

/**
 * Invokes a runtime-registered update handler.
 */
private suspend fun WorkflowExecutor.invokeRuntimeUpdateHandler(
    handler: UpdateHandlerEntry,
    protocolInstanceId: String,
    args: List<Payload>,
    runValidator: Boolean,
) {
    try {
        // Run validator if requested (in read-only mode)
        if (runValidator && handler.validator != null) {
            state.isReadOnly = true
            try {
                handler.validator.invoke(args)
            } finally {
                state.isReadOnly = false
            }
        }

        // Accept the update
        addUpdateAcceptedCommand(protocolInstanceId)

        // Execute the handler
        val resultPayload = handler.handler(args)

        // Complete with result
        addUpdateCompletedCommand(protocolInstanceId, resultPayload)
    } catch (e: ReadOnlyContextException) {
        logger.warn("Update validator attempted state mutation: {}", e.message)
        addUpdateRejectedCommand(protocolInstanceId, "Validator attempted state mutation: ${e.message}")
    } catch (e: IllegalArgumentException) {
        // Validation failure
        logger.warn("Update validation failed: {}", e.message)
        addUpdateRejectedCommand(protocolInstanceId, e.message ?: "Validation failed")
    } catch (e: Exception) {
        logger.warn("Update handler threw exception: {}", e.message, e)
        addUpdateRejectedCommand(protocolInstanceId, "Update failed: ${e.message ?: e::class.simpleName}")
    }
}

/**
 * Invokes a runtime-registered dynamic update handler.
 */
private suspend fun WorkflowExecutor.invokeRuntimeDynamicUpdateHandler(
    handler: DynamicUpdateHandlerEntry,
    protocolInstanceId: String,
    updateName: String,
    args: List<Payload>,
    runValidator: Boolean,
) {
    try {
        // Run validator if requested (in read-only mode)
        if (runValidator && handler.validator != null) {
            state.isReadOnly = true
            try {
                handler.validator.invoke(updateName, args)
            } finally {
                state.isReadOnly = false
            }
        }

        // Accept the update
        addUpdateAcceptedCommand(protocolInstanceId)

        // Execute the handler
        val resultPayload = handler.handler(updateName, args)

        // Complete with result
        addUpdateCompletedCommand(protocolInstanceId, resultPayload)
    } catch (e: ReadOnlyContextException) {
        logger.warn("Update validator attempted state mutation: {}", e.message)
        addUpdateRejectedCommand(protocolInstanceId, "Validator attempted state mutation: ${e.message}")
    } catch (e: IllegalArgumentException) {
        // Validation failure
        logger.warn("Update validation failed: {}", e.message)
        addUpdateRejectedCommand(protocolInstanceId, e.message ?: "Validation failed")
    } catch (e: Exception) {
        logger.warn("Dynamic update handler threw exception: {}", e.message, e)
        addUpdateRejectedCommand(protocolInstanceId, "Update failed: ${e.message ?: e::class.simpleName}")
    }
}

/**
 * Invokes an annotation-defined update handler.
 */
private suspend fun WorkflowExecutor.invokeAnnotationUpdateHandler(
    handler: UpdateHandlerInfo,
    update: coresdk.workflow_activation.WorkflowActivationOuterClass.DoUpdate,
    isDynamic: Boolean,
) {
    val protocolInstanceId = update.protocolInstanceId
    val runValidator = update.runValidator

    try {
        val ctx = context ?: error("WorkflowContext not initialized")
        val method = handler.handlerMethod

        // For dynamic handlers, the first argument is the update name
        val args =
            if (isDynamic) {
                val remainingParamTypes = handler.parameterTypes.drop(1)
                val deserializedArgs = deserializeArguments(update.inputList, remainingParamTypes)
                arrayOf(update.name, *deserializedArgs)
            } else {
                deserializeArguments(update.inputList, handler.parameterTypes)
            }

        // Run validator if requested (in read-only mode)
        if (runValidator && handler.validatorMethod != null) {
            state.isReadOnly = true
            try {
                invokeValidatorMethod(handler.validatorMethod, args, ctx)
            } finally {
                state.isReadOnly = false
            }
        }

        // Accept the update
        addUpdateAcceptedCommand(protocolInstanceId)

        // Execute the handler
        val result =
            if (handler.hasContextReceiver) {
                if (handler.isSuspend) {
                    method.callSuspend(workflowInstance!!, ctx, *args)
                } else {
                    method.call(workflowInstance!!, ctx, *args)
                }
            } else {
                if (handler.isSuspend) {
                    method.callSuspend(workflowInstance!!, *args)
                } else {
                    method.call(workflowInstance!!, *args)
                }
            }

        // Serialize the result
        val resultPayload =
            if (result == Unit || handler.returnType.classifier == Unit::class) {
                Payload.getDefaultInstance()
            } else {
                serializer.serialize(handler.returnType, result)
            }

        // Complete with result
        addUpdateCompletedCommand(protocolInstanceId, resultPayload)
    } catch (e: ReadOnlyContextException) {
        logger.warn("Update validator attempted state mutation: {}", e.message)
        addUpdateRejectedCommand(protocolInstanceId, "Validator attempted state mutation: ${e.message}")
    } catch (e: java.lang.reflect.InvocationTargetException) {
        val cause = e.targetException ?: e
        if (cause is IllegalArgumentException) {
            // Validation failure
            logger.warn("Update validation failed: {}", cause.message)
            addUpdateRejectedCommand(protocolInstanceId, cause.message ?: "Validation failed")
        } else {
            logger.warn("Update handler threw exception: {}", cause.message, cause)
            addUpdateRejectedCommand(
                protocolInstanceId,
                "Update failed: ${cause.message ?: cause::class.simpleName}",
            )
        }
    } catch (e: IllegalArgumentException) {
        // Validation failure
        logger.warn("Update validation failed: {}", e.message)
        addUpdateRejectedCommand(protocolInstanceId, e.message ?: "Validation failed")
    } catch (e: Exception) {
        logger.warn("Update handler threw exception: {}", e.message, e)
        addUpdateRejectedCommand(protocolInstanceId, "Update failed: ${e.message ?: e::class.simpleName}")
    }
}

/**
 * Invokes an update validator method.
 */
private fun WorkflowExecutor.invokeValidatorMethod(
    validator: kotlin.reflect.KFunction<*>,
    args: Array<Any?>,
    ctx: WorkflowContextImpl,
) {
    // Validators cannot be suspend functions
    // Check if validator uses WorkflowContext as extension receiver
    val extensionReceiver = validator.extensionReceiverParameter
    val hasContextReceiver =
        extensionReceiver?.type?.classifier == com.surrealdev.temporal.workflow.WorkflowContext::class

    if (hasContextReceiver) {
        validator.call(workflowInstance!!, ctx, *args)
    } else {
        validator.call(workflowInstance!!, *args)
    }
}

/**
 * Adds an update accepted command.
 */
private fun WorkflowExecutor.addUpdateAcceptedCommand(protocolInstanceId: String) {
    val updateResponse =
        WorkflowCommands.UpdateResponse
            .newBuilder()
            .setProtocolInstanceId(protocolInstanceId)
            .setAccepted(
                com.google.protobuf.Empty
                    .getDefaultInstance(),
            ).build()

    val command =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setUpdateResponse(updateResponse)
            .build()

    state.addCommand(command)
}

/**
 * Adds an update rejected command.
 */
private fun WorkflowExecutor.addUpdateRejectedCommand(
    protocolInstanceId: String,
    message: String,
) {
    val failure =
        Failure
            .newBuilder()
            .setMessage(message)
            .setSource("Kotlin")
            .build()

    val updateResponse =
        WorkflowCommands.UpdateResponse
            .newBuilder()
            .setProtocolInstanceId(protocolInstanceId)
            .setRejected(failure)
            .build()

    val command =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setUpdateResponse(updateResponse)
            .build()

    state.addCommand(command)
}

/**
 * Adds an update completed command.
 */
private fun WorkflowExecutor.addUpdateCompletedCommand(
    protocolInstanceId: String,
    payload: Payload,
) {
    val updateResponse =
        WorkflowCommands.UpdateResponse
            .newBuilder()
            .setProtocolInstanceId(protocolInstanceId)
            .setCompleted(payload)
            .build()

    val command =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setUpdateResponse(updateResponse)
            .build()

    state.addCommand(command)
}
