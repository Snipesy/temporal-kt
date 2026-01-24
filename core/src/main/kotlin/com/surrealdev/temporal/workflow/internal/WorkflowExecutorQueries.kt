package com.surrealdev.temporal.workflow.internal

import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import kotlin.reflect.full.callSuspend

/*
 * Extension functions for handling workflow queries in WorkflowExecutor.
 */

/**
 * Handles a query workflow job by routing to the appropriate handler.
 *
 * Handler priority:
 * 1. Runtime handler for specific query type
 * 2. Annotation handler for specific query type
 * 3. Runtime dynamic handler
 * 4. Annotation dynamic handler
 */
internal suspend fun WorkflowExecutor.handleQuery(
    query: coresdk.workflow_activation.WorkflowActivationOuterClass.QueryWorkflow,
) {
    val queryId = query.queryId
    val queryType = query.queryType

    logger.debug("Processing query: id={}, type={}", queryId, queryType)

    val ctx = context

    // Check runtime-registered handlers first (they take precedence)
    val runtimeHandler = ctx?.runtimeQueryHandlers?.get(queryType)
    val runtimeDynamicHandler = ctx?.runtimeDynamicQueryHandler

    // Then check annotation-defined handlers
    val annotationHandler = methodInfo.queryHandlers[queryType]
    val annotationDynamicHandler = methodInfo.queryHandlers[null]

    // Determine which handler to use
    when {
        runtimeHandler != null -> {
            handleRuntimeQuery(queryId, queryType, runtimeHandler, query.argumentsList, isDynamic = false)
        }

        annotationHandler != null -> {
            handleAnnotationQuery(queryId, queryType, annotationHandler, query, isDynamic = false)
        }

        runtimeDynamicHandler != null -> {
            handleRuntimeDynamicQuery(queryId, queryType, runtimeDynamicHandler, query.argumentsList)
        }

        annotationDynamicHandler != null -> {
            handleAnnotationQuery(queryId, queryType, annotationDynamicHandler, query, isDynamic = true)
        }

        else -> {
            logger.debug("No handler found for query type: {}", queryType)
            addFailedQueryResult(queryId, "Unknown query type: $queryType")
        }
    }
}

/**
 * Handles a runtime-registered query handler (specific query type).
 * Runtime handlers receive raw Payloads and return a Payload directly.
 */
private suspend fun WorkflowExecutor.handleRuntimeQuery(
    queryId: String,
    queryType: String,
    handler: suspend (List<Payload>) -> Payload,
    args: List<Payload>,
    isDynamic: Boolean,
) {
    try {
        val resultPayload = handler(args)
        addSuccessQueryResult(queryId, resultPayload)
    } catch (e: ReadOnlyContextException) {
        logger.warn("Query handler attempted state mutation: {}", e.message)
        addFailedQueryResult(queryId, "Query attempted state mutation: ${e.message}")
    } catch (e: Exception) {
        logger.warn("Query handler threw exception: {}", e.message)
        addFailedQueryResult(queryId, "Query failed: ${e.message ?: e::class.simpleName}")
    }
}

/**
 * Handles a runtime-registered dynamic query handler.
 * Dynamic handlers receive the query type name and raw Payloads, and return a Payload.
 */
private suspend fun WorkflowExecutor.handleRuntimeDynamicQuery(
    queryId: String,
    queryType: String,
    handler: suspend (queryType: String, args: List<Payload>) -> Payload,
    args: List<Payload>,
) {
    try {
        val resultPayload = handler(queryType, args)
        addSuccessQueryResult(queryId, resultPayload)
    } catch (e: ReadOnlyContextException) {
        logger.warn("Query handler attempted state mutation: {}", e.message)
        addFailedQueryResult(queryId, "Query attempted state mutation: ${e.message}")
    } catch (e: Exception) {
        logger.warn("Query handler threw exception: {}", e.message)
        addFailedQueryResult(queryId, "Query failed: ${e.message ?: e::class.simpleName}")
    }
}

/**
 * Handles an annotation-defined query handler.
 */
private suspend fun WorkflowExecutor.handleAnnotationQuery(
    queryId: String,
    queryType: String,
    handler: QueryHandlerInfo,
    query: coresdk.workflow_activation.WorkflowActivationOuterClass.QueryWorkflow,
    isDynamic: Boolean,
) {
    try {
        // For dynamic handlers, the first argument is the query type name
        val args =
            if (isDynamic) {
                val remainingParamTypes = handler.parameterTypes.drop(1)
                val deserializedArgs = deserializeArguments(query.argumentsList, remainingParamTypes)
                arrayOf(queryType, *deserializedArgs)
            } else {
                deserializeArguments(query.argumentsList, handler.parameterTypes)
            }

        val result = invokeQueryHandler(handler, args)

        // Serialize the result using the handler's declared return type
        val payload =
            if (result == Unit || handler.returnType.classifier == Unit::class) {
                Payload.getDefaultInstance()
            } else {
                serializer.serialize(handler.returnType, result)
            }

        addSuccessQueryResult(queryId, payload)
    } catch (e: ReadOnlyContextException) {
        logger.warn("Query handler attempted state mutation: {}", e.message, e)
        addFailedQueryResult(queryId, "Query attempted state mutation: ${e.message}")
    } catch (e: java.lang.reflect.InvocationTargetException) {
        val cause = e.targetException ?: e
        logger.warn("Query handler threw exception: {}", cause.message, e)
        addFailedQueryResult(queryId, "Query failed: ${cause.message ?: cause::class.simpleName}")
    } catch (e: Exception) {
        logger.warn("Query handler threw exception: {}", e.message, e)
        addFailedQueryResult(queryId, "Query failed: ${e.message ?: e::class.simpleName}")
    }
}

/**
 * Invokes an annotation-defined query handler method.
 */
private suspend fun WorkflowExecutor.invokeQueryHandler(
    handler: QueryHandlerInfo,
    args: Array<Any?>,
): Any? {
    val ctx = context ?: error("WorkflowContext not initialized")
    val method = handler.handlerMethod ?: error("Handler method is null for annotation-defined handler")

    return if (handler.hasContextReceiver) {
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
}

/**
 * Adds a successful query result to the pending results.
 */
internal fun WorkflowExecutor.addSuccessQueryResult(
    queryId: String,
    payload: Payload,
) {
    val queryResult =
        WorkflowCommands.QueryResult
            .newBuilder()
            .setQueryId(queryId)
            .setSucceeded(
                WorkflowCommands.QuerySuccess
                    .newBuilder()
                    .setResponse(payload),
            ).build()

    val command =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setRespondToQuery(queryResult)
            .build()

    pendingQueryResults.add(command)
}

/**
 * Adds a failed query result to the pending results.
 */
internal fun WorkflowExecutor.addFailedQueryResult(
    queryId: String,
    errorMessage: String,
) {
    val failure =
        Failure
            .newBuilder()
            .setMessage(errorMessage)
            .setSource("Kotlin")
            .build()

    val queryResult =
        WorkflowCommands.QueryResult
            .newBuilder()
            .setQueryId(queryId)
            .setFailed(failure)
            .build()

    val command =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setRespondToQuery(queryResult)
            .build()

    pendingQueryResults.add(command)
}
