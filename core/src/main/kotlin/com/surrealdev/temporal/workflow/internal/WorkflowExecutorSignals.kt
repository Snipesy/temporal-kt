package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.application.plugin.interceptor.HandleSignal
import com.surrealdev.temporal.application.plugin.interceptor.HandleSignalInput
import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.PayloadProcessingException
import com.surrealdev.temporal.serialization.safeDecode
import kotlin.coroutines.cancellation.CancellationException
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

    // Codec-decode args once — used by both interceptors and dispatch handlers
    val decodedArgs = codec.safeDecode(EncodedTemporalPayloads.fromProtoPayloadList(inputPayloads))

    val interceptorInput =
        HandleSignalInput(
            signalName = signalName,
            args = decodedArgs,
            runId = runId,
            workflowType = methodInfo.workflowType,
            headers = signal.headersMap.takeIf { it.isNotEmpty() }?.mapValues { (_, v) -> TemporalPayload(v) },
        )

    try {
        val chain = hookRegistry.chain(HandleSignal)
        chain.execute(interceptorInput) { input ->
            dispatchSignal(input.signalName, input.args)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Signal interceptor exceptions should not fail the workflow task.
        // Signals are fire-and-forget — log and continue.
        logger.warn("Signal interceptor threw exception for '{}': {}", signalName, e.message, e)
    }
}

/**
 * Internal signal dispatch logic — the terminal handler for the HandleSignal interceptor chain.
 */
private suspend fun WorkflowExecutor.dispatchSignal(
    signalName: String,
    decodedArgs: TemporalPayloads,
) {
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
            invokeRuntimeSignalHandler(runtimeHandler, decodedArgs)
        }

        annotationHandler != null -> {
            invokeAnnotationSignalHandler(annotationHandler, signalName, decodedArgs, isDynamic = false)
        }

        runtimeDynamicHandler != null -> {
            invokeRuntimeDynamicSignalHandler(runtimeDynamicHandler, signalName, decodedArgs)
        }

        annotationDynamicHandler != null -> {
            invokeAnnotationSignalHandler(annotationDynamicHandler, signalName, decodedArgs, isDynamic = true)
        }

        else -> {
            // Buffer the signal for later when a handler is registered
            // Payloads are already decoded so handlers get decoded payloads
            logger.debug("No handler found for signal '{}', buffering for later", signalName)
            ctx?.bufferedSignals?.getOrPut(signalName) { mutableListOf() }?.add(decodedArgs)
        }
    }
}

/**
 * Invokes a runtime-registered signal handler.
 */
private suspend fun WorkflowExecutor.invokeRuntimeSignalHandler(
    handler: suspend (TemporalPayloads) -> Unit,
    decodedArgs: TemporalPayloads,
) {
    val ctx = (context ?: error("WorkflowContext not initialized"))
    ctx.launchHandler {
        try {
            handler(decodedArgs)
        } catch (e: CancellationException) {
            throw e
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
    decodedArgs: TemporalPayloads,
) {
    val ctx = (context ?: error("WorkflowContext not initialized"))
    ctx.launchHandler {
        try {
            handler(signalName, decodedArgs)
        } catch (e: CancellationException) {
            throw e
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
    signalName: String,
    decodedArgs: TemporalPayloads,
    isDynamic: Boolean,
) {
    val ctx = (context ?: error("WorkflowContext not initialized"))
    val method = handler.handlerMethod

    ctx.launchHandler {
        try {
            // Deserialize already-decoded arguments
            val args =
                if (isDynamic) {
                    val remainingParamTypes = handler.parameterTypes.drop(1)
                    val deserializedArgs = deserializeDecodedArguments(decodedArgs, remainingParamTypes)
                    arrayOf(signalName, *deserializedArgs)
                } else {
                    deserializeDecodedArguments(decodedArgs, handler.parameterTypes)
                }

            // Invoke the handler method
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
        } catch (e: PayloadProcessingException) {
            // Serialization error during signal argument deserialize - log and drop signal
            // Signals are fire-and-forget; there's no caller to report error to
            logger.warn(
                "Signal handler payload processing failed for '{}', signal dropped: {}",
                signalName,
                e.message,
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e
            logger.warn("Signal handler threw exception: {}", cause.message, cause)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Signal handlers should not fail the workflow
            logger.warn("Signal handler threw exception: {}", e.message, e)
        }
    }
}
