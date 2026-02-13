package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.application.plugin.interceptor.ExecuteUpdateInput
import com.surrealdev.temporal.application.plugin.interceptor.InterceptorChain
import com.surrealdev.temporal.application.plugin.interceptor.ValidateUpdateInput
import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.PayloadProcessingException
import com.surrealdev.temporal.common.failure.FAILURE_SOURCE
import com.surrealdev.temporal.serialization.safeDecode
import com.surrealdev.temporal.serialization.safeEncodeSingle
import com.surrealdev.temporal.serialization.safeSerialize
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
 * The update flow:
 * 1. Resolve the handler (determine which handler type to use)
 * 2. Launch a handler coroutine where both interceptor chains and the actual handler run
 * 3. Inside the coroutine:
 *    a. ValidateUpdate interceptor chain wraps the actual validator
 *    b. Accept the update
 *    c. ExecuteUpdate interceptor chain wraps the actual handler
 *    d. Complete with result
 *
 * This ensures interceptor exceptions can't produce dual accept+reject responses,
 * and the ValidateUpdate chain genuinely gates validation.
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

    // Codec-decode args once — used by both interceptors and dispatch handlers
    val decodedArgs = codec.safeDecode(EncodedTemporalPayloads.fromProtoPayloadList(inputPayloads))
    val headers = update.headersMap.takeIf { it.isNotEmpty() }?.mapValues { (_, v) -> TemporalPayload(v) }

    // Resolve the handler before launching — fail fast if no handler exists
    val resolved = resolveUpdateHandler(updateName)
    if (resolved == null) {
        logger.debug("No handler found for update '{}', rejecting", updateName)
        addUpdateRejectedCommand(protocolInstanceId, "Unknown update type: $updateName")
        return
    }

    val ctx = (context ?: error("WorkflowContext not initialized")) as WorkflowContextImpl

    ctx.launchHandler {
        try {
            // ValidateUpdate interceptor chain — terminal handler runs the actual validator
            if (runValidator) {
                val validateInput =
                    ValidateUpdateInput(
                        updateName = updateName,
                        protocolInstanceId = protocolInstanceId,
                        args = decodedArgs,
                        runId = runId,
                        headers = headers,
                    )
                val validateChain = InterceptorChain(interceptorRegistry.validateUpdate)
                validateChain.execute(validateInput) { input ->
                    if (resolved.validate != null) {
                        state.isReadOnly = true
                        try {
                            resolved.validate.invoke(input.args)
                        } finally {
                            state.isReadOnly = false
                        }
                    }
                }
            }

            // Accept the update
            addUpdateAcceptedCommand(protocolInstanceId)

            // ExecuteUpdate interceptor chain — terminal handler runs the actual handler
            val executeInput =
                ExecuteUpdateInput(
                    updateName = updateName,
                    protocolInstanceId = protocolInstanceId,
                    args = decodedArgs,
                    runId = runId,
                    headers = headers,
                )
            val executeChain = InterceptorChain(interceptorRegistry.executeUpdate)
            val resultPayload =
                executeChain.execute(executeInput) { input ->
                    resolved.execute(input.args)
                }

            // Complete with result
            addUpdateCompletedCommand(protocolInstanceId, resultPayload as Payload)
        } catch (e: PayloadProcessingException) {
            logger.warn("Update handler payload processing failed for '{}': {}", updateName, e.message)
            addUpdateRejectedCommand(protocolInstanceId, "Failed to process update arguments: ${e.message}")
        } catch (e: ReadOnlyContextException) {
            logger.warn("Update validator attempted state mutation: {}", e.message)
            addUpdateRejectedCommand(protocolInstanceId, "Validator attempted state mutation: ${e.message}")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is IllegalArgumentException) {
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
            logger.warn("Update validation failed: {}", e.message)
            addUpdateRejectedCommand(protocolInstanceId, e.message ?: "Validation failed")
        } catch (e: Exception) {
            logger.warn("Update handler threw exception: {}", e.message, e)
            addUpdateRejectedCommand(protocolInstanceId, "Update failed: ${e.message ?: e::class.simpleName}")
        }
    }
}

/**
 * Abstraction over the 4 update handler types (runtime, runtime dynamic, annotation, annotation dynamic).
 * Separates handler resolution from execution so interceptor chains can wrap the actual logic.
 */
private class ResolvedUpdateHandler(
    /** Validator function. Throws IllegalArgumentException on validation failure. Null if no validator. */
    val validate: ((TemporalPayloads) -> Unit)?,
    /** Handler function. Returns the fully encoded result Payload. */
    val execute: suspend (TemporalPayloads) -> Payload,
)

/**
 * Resolves which update handler to use without executing it.
 *
 * Handler priority:
 * 1. Runtime handler for specific update
 * 2. Annotation handler for specific update
 * 3. Runtime dynamic handler
 * 4. Annotation dynamic handler
 *
 * @return The resolved handler, or null if no handler is registered for this update name
 */
private fun WorkflowExecutor.resolveUpdateHandler(updateName: String): ResolvedUpdateHandler? {
    val ctx = context

    // Check runtime-registered handlers first (they take precedence)
    val runtimeHandler = ctx?.runtimeUpdateHandlers?.get(updateName)
    val runtimeDynamicHandler = ctx?.runtimeDynamicUpdateHandler

    // Then check annotation-defined handlers
    val annotationHandler = methodInfo.updateHandlers[updateName]
    val annotationDynamicHandler = methodInfo.updateHandlers[null]

    return when {
        runtimeHandler != null -> resolveRuntimeHandler(runtimeHandler)
        annotationHandler != null ->
            resolveAnnotationHandler(annotationHandler, updateName, isDynamic = false)
        runtimeDynamicHandler != null ->
            resolveRuntimeDynamicHandler(runtimeDynamicHandler, updateName)
        annotationDynamicHandler != null ->
            resolveAnnotationHandler(annotationDynamicHandler, updateName, isDynamic = true)
        else -> null
    }
}

/**
 * Resolves a runtime-registered update handler into validate/execute lambdas.
 */
private fun WorkflowExecutor.resolveRuntimeHandler(handler: UpdateHandlerEntry): ResolvedUpdateHandler =
    ResolvedUpdateHandler(
        validate = handler.validator?.let { v -> { args -> v(args) } },
        execute = { args ->
            val resultPayload = handler.handler(args)
            codec.safeEncodeSingle(resultPayload)
        },
    )

/**
 * Resolves a runtime-registered dynamic update handler into validate/execute lambdas.
 */
private fun WorkflowExecutor.resolveRuntimeDynamicHandler(
    handler: DynamicUpdateHandlerEntry,
    updateName: String,
): ResolvedUpdateHandler =
    ResolvedUpdateHandler(
        validate = handler.validator?.let { v -> { args -> v(updateName, args) } },
        execute = { args ->
            val resultPayload = handler.handler(updateName, args)
            codec.safeEncodeSingle(resultPayload)
        },
    )

/**
 * Resolves an annotation-defined update handler into validate/execute lambdas.
 */
private fun WorkflowExecutor.resolveAnnotationHandler(
    handler: UpdateHandlerInfo,
    updateName: String,
    isDynamic: Boolean,
): ResolvedUpdateHandler {
    val ctx = (context ?: error("WorkflowContext not initialized")) as WorkflowContextImpl
    val method = handler.handlerMethod

    return ResolvedUpdateHandler(
        validate =
            handler.validatorMethod?.let { validatorMethod ->
                { args: TemporalPayloads ->
                    val deserialized =
                        if (isDynamic) {
                            val remainingParamTypes = handler.parameterTypes.drop(1)
                            val deserializedArgs = deserializeDecodedArguments(args, remainingParamTypes)
                            arrayOf(updateName, *deserializedArgs)
                        } else {
                            deserializeDecodedArguments(args, handler.parameterTypes)
                        }
                    invokeValidatorMethod(validatorMethod, deserialized, ctx)
                }
            },
        execute = { args: TemporalPayloads ->
            val deserialized =
                if (isDynamic) {
                    val remainingParamTypes = handler.parameterTypes.drop(1)
                    val deserializedArgs = deserializeDecodedArguments(args, remainingParamTypes)
                    arrayOf(updateName, *deserializedArgs)
                } else {
                    deserializeDecodedArguments(args, handler.parameterTypes)
                }

            val result =
                if (handler.hasContextReceiver) {
                    if (handler.isSuspend) {
                        method.callSuspend(workflowInstance!!, ctx, *deserialized)
                    } else {
                        method.call(workflowInstance!!, ctx, *deserialized)
                    }
                } else {
                    if (handler.isSuspend) {
                        method.callSuspend(workflowInstance!!, *deserialized)
                    } else {
                        method.call(workflowInstance!!, *deserialized)
                    }
                }

            if (result == Unit || handler.returnType.classifier == Unit::class) {
                Payload.getDefaultInstance()
            } else {
                val serialized = serializer.safeSerialize(handler.returnType, result)
                codec.safeEncodeSingle(serialized)
            }
        },
    )
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
            .setSource(FAILURE_SOURCE)
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
