package com.surrealdev.temporal.workflow.internal

import com.google.protobuf.ByteString
import com.google.protobuf.util.JsonFormat
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.failure.FAILURE_SOURCE
import com.surrealdev.temporal.serialization.safeDecode
import com.surrealdev.temporal.serialization.safeEncodeSingle
import com.surrealdev.temporal.serialization.safeSerialize
import coresdk.workflow_commands.WorkflowCommands
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import io.temporal.api.sdk.v1.workflowDefinition
import io.temporal.api.sdk.v1.workflowInteractionDefinition
import io.temporal.api.sdk.v1.workflowMetadata
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.full.callSuspend

/**
 * Built-in query type for workflow metadata introspection.
 * This query is used by Temporal UI and CLI to discover registered handlers.
 */
private const val QUERY_TYPE_WORKFLOW_METADATA = "__temporal_workflow_metadata"

/**
 * Built-in query type for workflow stack trace.
 * This query is used by Temporal CLI (`temporal workflow stack`) and UI to inspect
 * the current execution state of a workflow.
 *
 * Returns detailed debugging information including:
 * - Job hierarchy with all coroutines (main, children, handlers)
 * - Workflow state (time, history length, replaying status)
 * - All pending operations (timers, activities, child workflows, etc.)
 * - Dispatcher state
 */
private const val QUERY_TYPE_STACK_TRACE = "__stack_trace"

/**
 * Alias for __stack_trace. Both return the same detailed output.
 */
private const val QUERY_TYPE_ENHANCED_STACK_TRACE = "__enhanced_stack_trace"

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

    // Handle built-in queries first
    if (queryType == QUERY_TYPE_WORKFLOW_METADATA) {
        handleWorkflowMetadataQuery(queryId)
        return
    }
    if (queryType == QUERY_TYPE_STACK_TRACE || queryType == QUERY_TYPE_ENHANCED_STACK_TRACE) {
        handleStackTraceQuery(queryId)
        return
    }

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
    handler: suspend (TemporalPayloads) -> TemporalPayload,
    args: List<Payload>,
    isDynamic: Boolean,
) {
    try {
        val encoded = EncodedTemporalPayloads.fromProtoPayloadList(args)
        val temporalArgs = codec.safeDecode(encoded)
        val resultPayload = handler(temporalArgs)
        addSuccessQueryResult(queryId, codec.safeEncodeSingle(resultPayload))
    } catch (e: CancellationException) {
        throw e
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
@OptIn(InternalTemporalApi::class)
private suspend fun WorkflowExecutor.handleRuntimeDynamicQuery(
    queryId: String,
    queryType: String,
    handler: suspend (queryType: String, args: TemporalPayloads) -> TemporalPayload,
    args: List<Payload>,
) {
    try {
        val encoded = EncodedTemporalPayloads.fromProtoPayloadList(args)
        val temporalArgs = codec.safeDecode(encoded)
        val resultPayload = handler(queryType, temporalArgs)
        addSuccessQueryResult(queryId, codec.safeEncodeSingle(resultPayload))
    } catch (e: CancellationException) {
        throw e
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

        // Serialize the result using the handler's declared return type, then encode with codec
        val payload =
            if (result == Unit || handler.returnType.classifier == Unit::class) {
                Payload.getDefaultInstance()
            } else {
                val serialized = serializer.safeSerialize(handler.returnType, result)
                codec.safeEncodeSingle(serialized)
            }

        addSuccessQueryResult(queryId, payload)
    } catch (e: CancellationException) {
        throw e
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
            .setSource(FAILURE_SOURCE)
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

/**
 * Handles the built-in __temporal_workflow_metadata query.
 * This query returns information about all registered handlers (queries, signals, updates)
 * for use by Temporal UI and CLI.
 */
private fun WorkflowExecutor.handleWorkflowMetadataQuery(queryId: String) {
    val ctx = context

    // Collect query definitions (use Set to deduplicate)
    val queryDefs = mutableSetOf<String>()
    queryDefs.add(QUERY_TYPE_WORKFLOW_METADATA) // Include the built-in query itself
    queryDefs.add(QUERY_TYPE_STACK_TRACE) // Include the stack trace query
    queryDefs.add(QUERY_TYPE_ENHANCED_STACK_TRACE) // Include the enhanced stack trace query
    methodInfo.queryHandlers.keys
        .filterNotNull()
        .forEach { queryDefs.add(it) }
    ctx?.runtimeQueryHandlers?.keys?.forEach { queryDefs.add(it) }

    // Collect signal definitions
    val signalDefs = mutableSetOf<String>()
    methodInfo.signalHandlers.keys
        .filterNotNull()
        .forEach { signalDefs.add(it) }
    ctx?.runtimeSignalHandlers?.keys?.forEach { signalDefs.add(it) }

    // Collect update definitions
    val updateDefs = mutableSetOf<String>()
    methodInfo.updateHandlers.keys
        .filterNotNull()
        .forEach { updateDefs.add(it) }
    ctx?.runtimeUpdateHandlers?.keys?.forEach { updateDefs.add(it) }

    // Build the metadata response using proto DSL
    val metadata =
        workflowMetadata {
            definition =
                workflowDefinition {
                    type = methodInfo.workflowType

                    // Add query definitions sorted alphabetically
                    queryDefs.sorted().forEach { name ->
                        queryDefinitions +=
                            workflowInteractionDefinition {
                                this.name = name
                                this.description = getQueryDescription(name)
                            }
                    }

                    // Add signal definitions sorted alphabetically
                    signalDefs.sorted().forEach { name ->
                        signalDefinitions +=
                            workflowInteractionDefinition {
                                this.name = name
                                this.description = getSignalDescription(name)
                            }
                    }

                    // Add update definitions sorted alphabetically
                    updateDefs.sorted().forEach { name ->
                        updateDefinitions +=
                            workflowInteractionDefinition {
                                this.name = name
                                this.description = getUpdateDescription(name)
                            }
                    }
                }
            // currentDetails could be added here if WorkflowContext exposes it
        }

    // Serialize as JSON payload (required for Temporal UI compatibility)
    val jsonString = JsonFormat.printer().print(metadata)
    val payload =
        Payload
            .newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8(jsonString))
            .build()

    addSuccessQueryResult(queryId, payload)
}

/**
 * Gets the description for a query handler by name.
 */
private fun WorkflowExecutor.getQueryDescription(name: String): String =
    when (name) {
        QUERY_TYPE_WORKFLOW_METADATA -> {
            "Returns metadata about the workflow including registered handlers."
        }

        QUERY_TYPE_STACK_TRACE -> {
            "Returns the workflow's coroutine stack trace."
        }

        QUERY_TYPE_ENHANCED_STACK_TRACE -> {
            "Returns detailed debugging information including all pending " +
                "operations and workflow state."
        }

        else -> {
            methodInfo.queryHandlers[name]?.description ?: ""
        }
    }

/**
 * Gets the description for a signal handler by name.
 * TODO: SignalHandlerInfo doesn't have a description field yet, so we return empty string.
 */
private fun WorkflowExecutor.getSignalDescription(name: String): String {
    // SignalHandlerInfo doesn't have description field - return empty for now
    return ""
}

/**
 * Gets the description for an update handler by name.
 * TODO: UpdateHandlerInfo doesn't have a description field yet, so we return empty string.
 */
private fun WorkflowExecutor.getUpdateDescription(name: String): String {
    // UpdateHandlerInfo doesn't have description field - return empty for now
    return ""
}

/**
 * Gets the status string for a Job.
 */
private fun getJobStatus(job: kotlinx.coroutines.Job): String =
    when {
        job.isCancelled -> "CANCELLED"
        job.isCompleted -> "COMPLETED"
        job.isActive -> "ACTIVE"
        else -> "UNKNOWN"
    }

/**
 * Handles the built-in __stack_trace query.
 * This query returns detailed debugging information about the workflow's current execution state,
 * including job hierarchy, pending operations, coroutine status, workflow state, and dispatcher info.
 *
 * Used by `temporal workflow stack` CLI command and Temporal UI.
 */
private fun WorkflowExecutor.handleStackTraceQuery(queryId: String) {
    val sb = StringBuilder()

    // Header with workflow identification
    sb.appendLine("Workflow Stack Trace")
    sb.appendLine("====================")
    sb.appendLine("Workflow Type: ${methodInfo.workflowType}")
    sb.appendLine("Run ID: $runId")
    sb.appendLine()

    // Job hierarchy status
    sb.appendLine("Job Hierarchy")
    sb.appendLine("-------------")

    // Main coroutine
    val main = mainCoroutine
    val mainStatus =
        when {
            main == null -> "NOT STARTED"
            main.isCompleted && main.isCancelled -> "CANCELLED"
            main.isCompleted -> "COMPLETED"
            main.isActive -> "ACTIVE (suspended)"
            else -> "UNKNOWN"
        }
    sb.appendLine("Main workflow coroutine: $mainStatus")

    // Workflow execution job and its children
    val execJob = workflowExecutionJob
    if (execJob != null) {
        sb.appendLine("workflowExecutionJob: ${getJobStatus(execJob)}")
        val ctx = context
        if (ctx != null) {
            sb.appendLine("  └── context.job: ${getJobStatus(ctx.job)}")
            var childIndex = 0
            ctx.job.children.forEach { childJob ->
                childIndex++
                val isMain = childJob === main
                val label = if (isMain) "main" else "child-$childIndex"
                sb.appendLine("        └── $label: ${getJobStatus(childJob)}")
            }
            if (childIndex == 0) {
                sb.appendLine("        └── (no children)")
            }
        }
    }

    // Handler job and its children (signal/update handlers)
    val hJob = handlerJob
    if (hJob != null) {
        sb.appendLine("handlerJob: ${getJobStatus(hJob)}")
        var handlerIndex = 0
        hJob.children.forEach { childJob ->
            handlerIndex++
            sb.appendLine("  └── handler-$handlerIndex: ${getJobStatus(childJob)}")
        }
        if (handlerIndex == 0) {
            sb.appendLine("  └── (no handlers)")
        }
    }
    sb.appendLine()

    // Pending operations from state
    val debugInfo = state.getDebugInfo()

    sb.appendLine("Workflow State")
    sb.appendLine("--------------")
    sb.appendLine("Current time: ${debugInfo.currentTime ?: "unknown"}")
    sb.appendLine("History length: ${debugInfo.historyLength}")
    sb.appendLine("Is replaying: ${debugInfo.isReplaying}")
    sb.appendLine("Cancel requested: ${debugInfo.cancelRequested}")
    sb.appendLine()

    sb.appendLine("Pending Operations")
    sb.appendLine("------------------")

    // Timers
    val totalTimers = debugInfo.pendingTimers.size + debugInfo.pendingTimerContinuations.size
    if (totalTimers > 0) {
        sb.appendLine("Timers ($totalTimers pending):")
        debugInfo.pendingTimers.forEach { seq ->
            sb.appendLine("  - Timer(seq=$seq) [deferred-based]")
        }
        debugInfo.pendingTimerContinuations.forEach { seq ->
            sb.appendLine("  - Timer(seq=$seq) [continuation-based]")
        }
    } else {
        sb.appendLine("Timers: none")
    }

    // Activities
    if (debugInfo.pendingActivities.isNotEmpty()) {
        sb.appendLine("Activities (${debugInfo.pendingActivities.size} pending):")
        debugInfo.pendingActivities.forEach { activity ->
            sb.appendLine("  - Activity(seq=${activity.seq}, type=\"${activity.activityType}\")")
        }
    } else {
        sb.appendLine("Activities: none")
    }

    // Local activities
    if (debugInfo.pendingLocalActivities.isNotEmpty()) {
        sb.appendLine("Local Activities (${debugInfo.pendingLocalActivities.size} pending):")
        debugInfo.pendingLocalActivities.forEach { activity ->
            sb.appendLine("  - LocalActivity(seq=${activity.seq}, type=\"${activity.activityType}\")")
        }
    } else {
        sb.appendLine("Local Activities: none")
    }

    // Child workflows
    if (debugInfo.pendingChildWorkflows.isNotEmpty()) {
        sb.appendLine("Child Workflows (${debugInfo.pendingChildWorkflows.size} pending):")
        debugInfo.pendingChildWorkflows.forEach { child ->
            val startedStatus = if (child.started) "started" else "starting"
            sb.appendLine(
                "  - ChildWorkflow(seq=${child.seq}, type=\"${child.workflowType}\", id=\"${child.workflowId}\", status=$startedStatus)",
            )
        }
    } else {
        sb.appendLine("Child Workflows: none")
    }

    // External signals
    if (debugInfo.pendingExternalSignals.isNotEmpty()) {
        sb.appendLine("External Signals (${debugInfo.pendingExternalSignals.size} pending):")
        debugInfo.pendingExternalSignals.forEach { seq ->
            sb.appendLine("  - ExternalSignal(seq=$seq)")
        }
    } else {
        sb.appendLine("External Signals: none")
    }

    // External cancels
    if (debugInfo.pendingExternalCancels.isNotEmpty()) {
        sb.appendLine("External Cancels (${debugInfo.pendingExternalCancels.size} pending):")
        debugInfo.pendingExternalCancels.forEach { seq ->
            sb.appendLine("  - ExternalCancel(seq=$seq)")
        }
    } else {
        sb.appendLine("External Cancels: none")
    }

    // Conditions
    if (debugInfo.pendingConditions > 0) {
        sb.appendLine("Await Conditions: ${debugInfo.pendingConditions} pending")
    } else {
        sb.appendLine("Await Conditions: none")
    }

    sb.appendLine()

    // Dispatcher state
    sb.appendLine("Dispatcher State")
    sb.appendLine("----------------")
    sb.appendLine("Has pending work: ${workflowDispatcher.hasPendingWork()}")
    sb.appendLine()

    // Return as plain text payload
    val payload =
        Payload
            .newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("plain/plain"))
            .setData(ByteString.copyFromUtf8(sb.toString()))
            .build()

    addSuccessQueryResult(queryId, payload)
}
