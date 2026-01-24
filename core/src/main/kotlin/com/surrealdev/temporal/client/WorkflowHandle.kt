package com.surrealdev.temporal.client

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.client.history.WorkflowHistory
import com.surrealdev.temporal.client.internal.WorkflowServiceClient
import com.surrealdev.temporal.serialization.PayloadSerializer
import io.temporal.api.common.v1.Payloads
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.EventType
import io.temporal.api.enums.v1.HistoryEventFilterType
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.api.workflowservice.v1.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = LoggerFactory.getLogger(WorkflowHandleImpl::class.java)

/**
 * Handle to a workflow execution.
 *
 * Provides methods to wait for results, send signals, get execution history,
 * and manage the workflow lifecycle.
 *
 * @param R The expected result type of the workflow.
 */
interface WorkflowHandle<R> {
    /**
     * The workflow ID.
     */
    val workflowId: String

    /**
     * The run ID of the workflow execution.
     * May be null if this handle was created without specifying a run ID.
     */
    val runId: String?

    /**
     * Serializer associated with this workflow handle.
     */
    val serializer: PayloadSerializer

    /**
     * Waits for the workflow to complete and returns the result.
     *
     * @param timeout Maximum time to wait for the result. Default is infinite.
     * @return The workflow result.
     * @throws WorkflowFailedException if the workflow failed.
     * @throws WorkflowCanceledException if the workflow was canceled.
     * @throws WorkflowTerminatedException if the workflow was terminated.
     * @throws WorkflowTimedOutException if the workflow timed out.
     * @throws WorkflowResultTimeoutException if waiting for the result timed out.
     */
    suspend fun result(timeout: Duration = Duration.INFINITE): R

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
    suspend fun signalWithPayloads(
        signalName: String,
        args: Payloads,
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
        args: Payloads,
    ): Payloads

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
        args: Payloads,
    ): Payloads

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
internal class WorkflowHandleImpl<R>(
    override val workflowId: String,
    override var runId: String?,
    private val resultTypeInfo: KType,
    private val serviceClient: WorkflowServiceClient,
    override val serializer: PayloadSerializer,
) : WorkflowHandle<R> {
    override suspend fun result(timeout: Duration): R {
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
                throw WorkflowResultTimeoutException(workflowId, runId)
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
                return handleCloseEvent(closeEvent)
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

    @Suppress("UNCHECKED_CAST")
    private fun handleCloseEvent(event: HistoryEvent): R =
        when (event.eventType) {
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED -> {
                val attrs = event.workflowExecutionCompletedEventAttributes
                if (attrs.hasResult() && attrs.result.payloadsCount > 0) {
                    val payload = attrs.result.getPayloads(0)
                    serializer.deserialize(resultTypeInfo, payload) as R
                } else {
                    // No result (Unit return type)
                    Unit as R
                }
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED -> {
                val attrs = event.workflowExecutionFailedEventAttributes
                throw WorkflowFailedException(
                    workflowId = workflowId,
                    runId = runId,
                    workflowType = null,
                    failure = if (attrs.hasFailure()) attrs.failure else null,
                )
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED -> {
                throw WorkflowCanceledException(workflowId, runId)
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED -> {
                val attrs = event.workflowExecutionTerminatedEventAttributes
                throw WorkflowTerminatedException(
                    workflowId = workflowId,
                    runId = runId,
                    reason = attrs.reason.ifEmpty { null },
                )
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT -> {
                throw WorkflowTimedOutException(
                    workflowId = workflowId,
                    runId = runId,
                    timeoutType = WorkflowTimeoutType.WORKFLOW_EXECUTION_TIMEOUT,
                )
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW -> {
                val attrs = event.workflowExecutionContinuedAsNewEventAttributes
                // Follow the continuation and get result from the new run
                val newRunId = attrs.newExecutionRunId
                val newHandle =
                    WorkflowHandleImpl<R>(
                        workflowId = workflowId,
                        runId = newRunId,
                        resultTypeInfo = resultTypeInfo,
                        serviceClient = serviceClient,
                        serializer = serializer,
                    )
                // This is a blocking recursive call - could potentially be optimized
                throw UnsupportedOperationException("Continue-as-new result following not yet implemented")
            }

            else -> {
                throw IllegalStateException("Unexpected close event type: ${event.eventType}")
            }
        }

    override suspend fun signalWithPayloads(
        signalName: String,
        args: Payloads,
    ) {
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
                .setInput(args)
                .build()

        serviceClient.signalWorkflowExecution(request)
    }

    override suspend fun updateWithPayloads(
        updateName: String,
        args: Payloads,
    ): Payloads {
        val updateId =
            java.util.UUID
                .randomUUID()
                .toString()

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
                                .setArgs(args)
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
            throw WorkflowUpdateFailedException(
                workflowId = workflowId,
                runId = runId,
                updateName = updateName,
                updateId = updateId,
                message = response.outcome.failure.message,
            )
        } else if (response.hasOutcome() && response.outcome.hasSuccess()) {
            return response.outcome.success
        } else {
            throw WorkflowUpdateFailedException(
                workflowId = workflowId,
                runId = runId,
                updateName = updateName,
                updateId = updateId,
                message = "Update failed with unknown outcome",
            )
        }
    }

    override suspend fun queryWithPayloads(
        queryType: String,
        args: Payloads,
    ): Payloads {
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
                        .setQueryArgs(args)
                        .build(),
                ).build()

        val response = serviceClient.queryWorkflow(request)

        // Check for query rejected
        if (response.hasQueryRejected()) {
            throw WorkflowQueryRejectedException(
                workflowId = workflowId,
                runId = runId,
                queryType = queryType,
                status = response.queryRejected.status.name,
            )
        }

        return response.queryResult
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
