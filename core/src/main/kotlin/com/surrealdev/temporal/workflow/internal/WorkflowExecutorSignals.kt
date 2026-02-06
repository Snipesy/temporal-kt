package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayloads
import io.temporal.api.common.v1.Payload
import kotlin.reflect.full.callSuspend

/*
 * Extension functions for handling workflow signals in WorkflowExecutor.
 */

/**
 * Handles a signal workflow job by routing to the appropriate handler.
 *
 * Handler priority:
 * 1. Runtime-registered handler for specific signal
 * 2. Annotation-defined handler for specific signal
 * 3. Runtime dynamic handler
 * 4. Annotation dynamic handler
 * 5. Buffer the signal for later
 */
internal suspend fun WorkflowExecutor.handleSignal(
    signal: coresdk.workflow_activation.WorkflowActivationOuterClass.SignalWorkflow,
) {
    val signalName = signal.signalName
    val inputPayloads = signal.inputList

    logger.debug("Processing signal: name={}, args={}", signalName, inputPayloads.size)

    val ctx = context

    // Check runtime-registered handlers first (they take precedence)
    val runtimeHandler = ctx?.runtimeSignalHandlers?.get(signalName)
    val runtimeDynamicHandler = ctx?.runtimeDynamicSignalHandler

    // Then check annotation-defined handlers
    val annotationHandler = methodInfo.signalHandlers[signalName]
    val annotationDynamicHandler = methodInfo.signalHandlers[null]

    // Determine which handler to use
    when {
        runtimeHandler != null -> {
            invokeRuntimeSignalHandler(runtimeHandler, inputPayloads)
        }

        annotationHandler != null -> {
            invokeAnnotationSignalHandler(annotationHandler, signal, isDynamic = false)
        }

        runtimeDynamicHandler != null -> {
            invokeRuntimeDynamicSignalHandler(runtimeDynamicHandler, signalName, inputPayloads)
        }

        annotationDynamicHandler != null -> {
            invokeAnnotationSignalHandler(annotationDynamicHandler, signal, isDynamic = true)
        }

        else -> {
            // Buffer the signal for later when a handler is registered
            // Decode eagerly before buffering so handlers get decoded payloads
            logger.debug("No handler found for signal '{}', buffering for later", signalName)
            val encoded = EncodedTemporalPayloads.fromProtoPayloadList(inputPayloads)
            val decodedPayloads = codec.decode(encoded)
            ctx?.bufferedSignals?.getOrPut(signalName) { mutableListOf() }?.add(decodedPayloads)
        }
    }
}

/**
 * Invokes a runtime-registered signal handler.
 */
private suspend fun WorkflowExecutor.invokeRuntimeSignalHandler(
    handler: suspend (TemporalPayloads) -> Unit,
    args: List<Payload>,
) {
    val ctx = (context ?: error("WorkflowContext not initialized")) as WorkflowContextImpl
    ctx.launchHandler {
        try {
            val encoded = EncodedTemporalPayloads.fromProtoPayloadList(args)
            val payloads = codec.decode(encoded)
            handler(payloads)
        } catch (e: Exception) {
            // Signal handlers should not fail the workflow
            // Log the error but continue
            logger.warn("Signal handler threw exception: {}", e.message, e)
        }
    }
}

/**
 * Invokes a runtime-registered dynamic signal handler.
 */
private suspend fun WorkflowExecutor.invokeRuntimeDynamicSignalHandler(
    handler: suspend (signalName: String, args: TemporalPayloads) -> Unit,
    signalName: String,
    args: List<Payload>,
) {
    val ctx = (context ?: error("WorkflowContext not initialized")) as WorkflowContextImpl
    ctx.launchHandler {
        try {
            val encoded = EncodedTemporalPayloads.fromProtoPayloadList(args)
            val payloads = codec.decode(encoded)
            handler(signalName, payloads)
        } catch (e: Exception) {
            // Signal handlers should not fail the workflow
            logger.warn("Dynamic signal handler threw exception: {}", e.message, e)
        }
    }
}

/**
 * Invokes an annotation-defined signal handler.
 */
private suspend fun WorkflowExecutor.invokeAnnotationSignalHandler(
    handler: SignalHandlerInfo,
    signal: coresdk.workflow_activation.WorkflowActivationOuterClass.SignalWorkflow,
    isDynamic: Boolean,
) {
    val ctx = (context ?: error("WorkflowContext not initialized")) as WorkflowContextImpl
    val method = handler.handlerMethod

    // Deserialize arguments outside launch (doesn't need workflow dispatcher)
    val args =
        if (isDynamic) {
            val remainingParamTypes = handler.parameterTypes.drop(1)
            val deserializedArgs = deserializeArguments(signal.inputList, remainingParamTypes)
            arrayOf(signal.signalName, *deserializedArgs)
        } else {
            deserializeArguments(signal.inputList, handler.parameterTypes)
        }

    ctx.launchHandler {
        try {
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
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e
            logger.warn("Signal handler threw exception: {}", cause.message, cause)
        } catch (e: Exception) {
            // Signal handlers should not fail the workflow
            logger.warn("Signal handler threw exception: {}", e.message, e)
        }
    }
}
