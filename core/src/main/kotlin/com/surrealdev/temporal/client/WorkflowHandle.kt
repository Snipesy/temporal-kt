package com.surrealdev.temporal.client

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.client.history.WorkflowHistory
import com.surrealdev.temporal.client.internal.WorkflowServiceClient
import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.ClientWorkflowCancelledException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowFailedException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowQueryRejectedException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowResultTimeoutException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowTerminatedException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowTimedOutException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowUpdateFailedException
import com.surrealdev.temporal.common.exceptions.WorkflowTimeoutType
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.safeDecode
import com.surrealdev.temporal.serialization.safeDecodeSingle
import com.surrealdev.temporal.serialization.safeEncode
import com.surrealdev.temporal.workflow.WorkflowHandleBase
import com.surrealdev.temporal.workflow.internal.buildCause
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.EventType
import io.temporal.api.enums.v1.HistoryEventFilterType
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.api.workflowservice.v1.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger(WorkflowHandleImpl::class.java)

/**
 * Handle to a workflow execution.
 *
 * Provides methods to wait for results, send signals, get execution history,
 * and manage the workflow lifecycle.
 */
interface WorkflowHandle : WorkflowHandleBase {
    /**
     * The workflow ID.
     */
    override val workflowId: String

    /**
     * The run ID of the workflow execution.
     * May be null if this handle was created without specifying a run ID.
     */
    val runId: String?

    /**
     * Serializer associated with this workflow handle.
     */
    override val serializer: PayloadSerializer

    /**
     * Waits for the workflow to complete and returns its raw result payload.
     *
     * For typed results, use the [result] extension function instead:
     * ```kotlin
     * val result: String = handle.result()
     * ```
     *
     * @param timeout Maximum time to wait for the result. Default is infinite.
     * @return The raw payload result of the workflow, or null if empty
     * @throws com.surrealdev.temporal.common.exceptions.ClientWorkflowFailedException if the workflow failed.
     * @throws com.surrealdev.temporal.common.exceptions.ClientWorkflowCancelledException if the workflow was canceled.
     * @throws com.surrealdev.temporal.common.exceptions.ClientWorkflowTerminatedException if the workflow was terminated.
     * @throws com.surrealdev.temporal.common.exceptions.ClientWorkflowTimedOutException if the workflow timed out.
     * @throws com.surrealdev.temporal.common.exceptions.ClientWorkflowResultTimeoutException if waiting for the result timed out.
     */
    @InternalTemporalApi
    suspend fun resultPayload(timeout: Duration = Duration.INFINITE): TemporalPayload?

    /**
     * Sends a signal to the workflow.
     *
     * This method uses raw payloads. You should use associated reified extension functions for type safety [signal].
     *
     *
     * @param signalName The name of the signal to send.
     * @param args Arguments to pass with the signal.
     */
    @InternalTemporalApi
    override suspend fun signalWithPayloads(
        signalName: String,
        args: TemporalPayloads,
    )

    /**
     * Sends an update to the workflow and waits for the result.
     *
     * Updates are like signals but return a value. They also support
     * validation before the update is accepted.
     *
     * This method uses raw payloads. You should use associated reified extension functions for type safety [update]
     *
     * @param updateName The name of the update to send.
     * @param args Arguments to pass with the update.
     * @return The update result.
     */
    @InternalTemporalApi
    suspend fun updateWithPayloads(
        updateName: String,
        args: TemporalPayloads,
    ): TemporalPayloads

    /**
     * Queries the workflow for its current state.
     *
     * Queries are read-only and do not affect workflow execution.
     *
     * This method uses raw payloads. You should use associated reified extension functions for type safety [query]
     *
     * @param queryType The type of query to execute.
     * @param args Arguments to pass with the query.
     * @return The query result.
     */
    @InternalTemporalApi
    suspend fun queryWithPayloads(
        queryType: String,
        args: TemporalPayloads,
    ): TemporalPayloads

    /**
     * Requests cancellation of the workflow.
     *
     * This is a graceful cancellation request - the workflow can handle it
     * and decide how to respond.
     */
    suspend fun cancel()

    /**
     * Terminates the workflow forcefully.
     *
     * Unlike cancellation, termination is immediate and the workflow
     * cannot handle it.
     *
     * @param reason Optional reason for termination.
     */
    suspend fun terminate(reason: String? = null)

    /**
     * Describes the workflow execution, returning its current status.
     *
     * @return Description of the workflow execution.
     */
    suspend fun describe(): WorkflowExecutionDescription

    /**
     * Gets the full workflow execution history.
     *
     * This is primarily useful for testing and debugging.
     *
     * @return The workflow history.
     */
    suspend fun getHistory(): WorkflowHistory
}

/**
 * Internal implementation of [WorkflowHandle].
 */
internal class WorkflowHandleImpl(
    override val workflowId: String,
    override var runId: String?,
    private val serviceClient: WorkflowServiceClient,
    override val serializer: PayloadSerializer,
    internal val codec: PayloadCodec,
) : WorkflowHandle {
    @OptIn(InternalTemporalApi::class)
    override suspend fun resultPayload(timeout: Duration): TemporalPayload? {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = if (timeout == Duration.INFINITE) Long.MAX_VALUE else timeout.inWholeMilliseconds

        logger.info("[result] Starting to poll for workflow $workflowId (runId=$runId), timeout=$timeout")

        // Poll for workflow completion
        var pollCount = 0
        while (true) {
            pollCount++
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMillis) {
                logger.warn("[result] Timeout waiting for workflow $workflowId after ${elapsed}ms")
                throw ClientWorkflowResultTimeoutException(workflowId, runId)
            }

            logger.debug("[result] Poll #$pollCount for workflow $workflowId (elapsed=${elapsed}ms)")

            val request =
                GetWorkflowExecutionHistoryRequest
                    .newBuilder()
                    .setNamespace(serviceClient.namespace)
                    .setExecution(
                        WorkflowExecution
                            .newBuilder()
                            .setWorkflowId(workflowId)
                            .also { if (runId != null) it.setRunId(runId) }
                            .build(),
                    ).setHistoryEventFilterType(HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT)
                    .setWaitNewEvent(false) // Don't use long-polling, poll manually for better cancellation
                    .build()

            val response =
                try {
                    serviceClient.getWorkflowExecutionHistory(request)
                } catch (e: Exception) {
                    logger.warn("[result] Poll failed for workflow $workflowId: ${e.message}")
                    // If request fails, continue polling
                    delay(200.milliseconds)
                    continue
                }

            logger.debug("[result] Got ${response.history.eventsCount} events for workflow $workflowId")

            // Find a close event in the response
            val closeEvent =
                response.history.eventsList.find { event ->
                    event.eventType in CLOSE_EVENT_TYPES
                }

            if (closeEvent != null) {
                logger.info(
                    "[result] Found close event ${closeEvent.eventType} for workflow $workflowId after $pollCount polls",
                )
                // Update runId if we didn't have it
                if (runId == null) {
                    try {
                        val desc = describe()
                        runId = desc.runId
                    } catch (_: Exception) {
                        // Ignore - we have the result anyway
                    }
                }
                val remainingTimeout = timeout - elapsed.milliseconds
                return handleCloseEvent(closeEvent, remainingTimeout)
            }

            // No close event yet, wait a bit before polling again
            delay(200.milliseconds)
        }
    }

    companion object {
        private val CLOSE_EVENT_TYPES =
            setOf(
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED,
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED,
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED,
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED,
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT,
                EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW,
            )
    }

    @OptIn(InternalTemporalApi::class)
    private suspend fun handleCloseEvent(
        event: HistoryEvent,
        remainingTimeout: Duration,
    ): TemporalPayload? =
        when (event.eventType) {
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED -> {
                val attrs = event.workflowExecutionCompletedEventAttributes
                if (attrs.hasResult() && attrs.result.payloadsCount > 0) {
                    val payload = attrs.result.getPayloads(0)
                    // Convert proto payload to EncodedTemporalPayloads, then decode with codec
                    codec.safeDecodeSingle(payload)
                } else {
                    // No result
                    null
                }
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED -> {
                val attrs = event.workflowExecutionFailedEventAttributes
                val failure = if (attrs.hasFailure()) attrs.failure else null
                throw ClientWorkflowFailedException(
                    workflowId = workflowId,
                    runId = runId,
                    workflowType = null,
                    failureMessage = failure?.message,
                    cause = failure?.let { buildCause(it, codec) },
                )
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED -> {
                throw ClientWorkflowCancelledException(workflowId, runId)
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED -> {
                val attrs = event.workflowExecutionTerminatedEventAttributes
                throw ClientWorkflowTerminatedException(
                    workflowId = workflowId,
                    runId = runId,
                    reason = attrs.reason.ifEmpty { null },
                )
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT -> {
                throw ClientWorkflowTimedOutException(
                    workflowId = workflowId,
                    runId = runId,
                    timeoutType = WorkflowTimeoutType.WORKFLOW_EXECUTION_TIMEOUT,
                )
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW -> {
                val attrs = event.workflowExecutionContinuedAsNewEventAttributes
                // Follow the continuation and get result from the new run
                val newRunId = attrs.newExecutionRunId
                logger.info(
                    "[handleCloseEvent] Workflow $workflowId continued-as-new to runId=$newRunId, following...",
                )
                val newHandle =
                    WorkflowHandleImpl(
                        workflowId = workflowId,
                        runId = newRunId,
                        serviceClient = serviceClient,
                        serializer = serializer,
                        codec = codec,
                    )
                // Recursively get result from the new run
                newHandle.resultPayload(remainingTimeout)
            }

            else -> {
                throw IllegalStateException("Unexpected close event type: ${event.eventType}")
            }
        }

    @OptIn(InternalTemporalApi::class)
    override suspend fun signalWithPayloads(
        signalName: String,
        args: TemporalPayloads,
    ) {
        val encodedArgs = codec.safeEncode(args)
        val protoPayloads = encodedArgs.toProto()

        val request =
            SignalWorkflowExecutionRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setWorkflowExecution(
                    WorkflowExecution
                        .newBuilder()
                        .setWorkflowId(workflowId)
                        .also { if (runId != null) it.setRunId(runId) }
                        .build(),
                ).setSignalName(signalName)
                .setInput(protoPayloads)
                .build()

        serviceClient.signalWorkflowExecution(request)
    }

    @OptIn(InternalTemporalApi::class)
    override suspend fun updateWithPayloads(
        updateName: String,
        args: TemporalPayloads,
    ): TemporalPayloads {
        val updateId =
            java.util.UUID
                .randomUUID()
                .toString()

        val encodedArgs = codec.safeEncode(args)
        val protoPayloads = encodedArgs.toProto()

        val request =
            UpdateWorkflowExecutionRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setWorkflowExecution(
                    WorkflowExecution
                        .newBuilder()
                        .setWorkflowId(workflowId)
                        .also { if (runId != null) it.setRunId(runId) }
                        .build(),
                ).setRequest(
                    io.temporal.api.update.v1.Request
                        .newBuilder()
                        .setMeta(
                            io.temporal.api.update.v1.Meta
                                .newBuilder()
                                .setUpdateId(updateId)
                                .build(),
                        ).setInput(
                            io.temporal.api.update.v1.Input
                                .newBuilder()
                                .setName(updateName)
                                .setArgs(protoPayloads)
                                .build(),
                        ).build(),
                ).setWaitPolicy(
                    io.temporal.api.update.v1.WaitPolicy
                        .newBuilder()
                        .setLifecycleStage(
                            io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage
                                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
                        ).build(),
                ).build()

        val response = serviceClient.updateWorkflowExecution(request)

        // Check for failure
        if (response.hasOutcome() && response.outcome.hasFailure()) {
            throw ClientWorkflowUpdateFailedException(
                workflowId = workflowId,
                runId = runId,
                updateName = updateName,
                updateId = updateId,
                message = response.outcome.failure.message,
            )
        } else if (response.hasOutcome() && response.outcome.hasSuccess()) {
            return codec.safeDecode(
                EncodedTemporalPayloads.fromProtoPayloadList(response.outcome.success.payloadsList),
            )
        } else {
            throw ClientWorkflowUpdateFailedException(
                workflowId = workflowId,
                runId = runId,
                updateName = updateName,
                updateId = updateId,
                message = "Update failed with unknown outcome",
            )
        }
    }

    @OptIn(InternalTemporalApi::class)
    override suspend fun queryWithPayloads(
        queryType: String,
        args: TemporalPayloads,
    ): TemporalPayloads {
        val encodedArgs = codec.safeEncode(args)
        val protoPayloads = encodedArgs.toProto()

        val request =
            QueryWorkflowRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setExecution(
                    WorkflowExecution
                        .newBuilder()
                        .setWorkflowId(workflowId)
                        .also { if (runId != null) it.setRunId(runId) }
                        .build(),
                ).setQuery(
                    io.temporal.api.query.v1.WorkflowQuery
                        .newBuilder()
                        .setQueryType(queryType)
                        .setQueryArgs(protoPayloads)
                        .build(),
                ).build()

        val response = serviceClient.queryWorkflow(request)

        // Check for query rejected
        if (response.hasQueryRejected()) {
            throw ClientWorkflowQueryRejectedException(
                workflowId = workflowId,
                runId = runId,
                queryType = queryType,
                status = response.queryRejected.status.name,
            )
        }

        return codec.safeDecode(
            EncodedTemporalPayloads.fromProtoPayloadList(response.queryResult.payloadsList),
        )
    }

    override suspend fun cancel() {
        val request =
            RequestCancelWorkflowExecutionRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setWorkflowExecution(
                    WorkflowExecution
                        .newBuilder()
                        .setWorkflowId(workflowId)
                        .also { if (runId != null) it.setRunId(runId) }
                        .build(),
                ).build()

        serviceClient.requestCancelWorkflowExecution(request)
    }

    override suspend fun terminate(reason: String?) {
        val request =
            TerminateWorkflowExecutionRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setWorkflowExecution(
                    WorkflowExecution
                        .newBuilder()
                        .setWorkflowId(workflowId)
                        .also { if (runId != null) it.setRunId(runId) }
                        .build(),
                ).also { if (reason != null) it.setReason(reason) }
                .build()

        serviceClient.terminateWorkflowExecution(request)
    }

    override suspend fun describe(): WorkflowExecutionDescription {
        val request =
            DescribeWorkflowExecutionRequest
                .newBuilder()
                .setNamespace(serviceClient.namespace)
                .setExecution(
                    WorkflowExecution
                        .newBuilder()
                        .setWorkflowId(workflowId)
                        .also { if (runId != null) it.setRunId(runId) }
                        .build(),
                ).build()

        val response = serviceClient.describeWorkflowExecution(request)
        val info = response.workflowExecutionInfo

        return WorkflowExecutionDescription(
            workflowId = info.execution.workflowId,
            runId = info.execution.runId,
            workflowType = info.type.name,
            status = WorkflowExecutionStatus.fromProto(info.status),
            startTime = info.startTime.seconds * 1000 + info.startTime.nanos / 1_000_000,
            closeTime =
                if (info.hasCloseTime()) {
                    info.closeTime.seconds * 1000 + info.closeTime.nanos / 1_000_000
                } else {
                    null
                },
            historyLength = info.historyLength,
        )
    }

    override suspend fun getHistory(): WorkflowHistory {
        val allEvents = mutableListOf<HistoryEvent>()
        var nextPageToken = com.google.protobuf.ByteString.EMPTY

        do {
            val request =
                GetWorkflowExecutionHistoryRequest
                    .newBuilder()
                    .setNamespace(serviceClient.namespace)
                    .setExecution(
                        WorkflowExecution
                            .newBuilder()
                            .setWorkflowId(workflowId)
                            .also { if (runId != null) it.setRunId(runId) }
                            .build(),
                    ).setNextPageToken(nextPageToken)
                    .build()

            val response = serviceClient.getWorkflowExecutionHistory(request)
            allEvents.addAll(response.history.eventsList)
            nextPageToken = response.nextPageToken
        } while (nextPageToken != null && !nextPageToken.isEmpty)

        return WorkflowHistory.fromProto(
            workflowId = workflowId,
            runId = runId,
            history =
                io.temporal.api.history.v1.History
                    .newBuilder()
                    .addAllEvents(allEvents)
                    .build(),
        )
    }
}
