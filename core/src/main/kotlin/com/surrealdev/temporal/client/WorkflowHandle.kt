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
import com.surrealdev.temporal.common.failure.buildCause
import com.surrealdev.temporal.common.toProto
import com.surrealdev.temporal.core.TemporalCoreException
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.safeDecode
import com.surrealdev.temporal.serialization.safeDecodeSingle
import com.surrealdev.temporal.serialization.safeEncode
import com.surrealdev.temporal.workflow.WorkflowHandleBase
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.EventType
import io.temporal.api.enums.v1.HistoryEventFilterType
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.api.workflowservice.v1.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger(WorkflowHandleImpl::class.java)

/**
 * Configuration for result long-polling behavior.
 *
 * Uses bounded server-side long polling: the server holds the connection open for up to
 * [longPollTimeout], returning immediately when the workflow closes or when the timeout
 * expires. This matches the pattern used by all official Temporal SDKs (Go, Java, Python, TS)
 * while keeping FFM call durations bounded.
 *
 * @property longPollTimeout Maximum time per long-poll RPC call. The server holds the connection
 *   open for up to this duration. When it expires, the client re-issues the poll. Default is 20s,
 *   yielding ~3 requests/minute steady-state with near-zero result delivery latency.
 */
data class ResultPollingConfig(
    val longPollTimeout: Duration = 20.seconds,
)

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
     * For typed results, use the `result()` extension function instead:
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
     * This method uses raw payloads. You should use associated reified extension functions for type safety like `signal()`.
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
     * This method uses raw payloads. You should use associated reified extension functions for type safety like `update()`.
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
     * This method uses raw payloads. You should use associated reified extension functions for type safety like `query()`.
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
    private val pollingConfig: ResultPollingConfig = ResultPollingConfig(),
) : WorkflowHandle {
    @OptIn(InternalTemporalApi::class)
    override suspend fun resultPayload(timeout: Duration): TemporalPayload? {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = if (timeout == Duration.INFINITE) Long.MAX_VALUE else timeout.inWholeMilliseconds
        val longPollTimeoutMillis = pollingConfig.longPollTimeout.inWholeMilliseconds.toInt()

        logger.info("[result] Starting to poll for workflow $workflowId (runId=$runId), timeout=$timeout")

        // Use local variable for runId to avoid mutating handle state during run-following
        var currentRunId = runId
        var nextPageToken = com.google.protobuf.ByteString.EMPTY
        var pollCount = 0
        while (true) {
            pollCount++
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMillis) {
                logger.warn("[result] Timeout waiting for workflow $workflowId after ${elapsed}ms")
                throw ClientWorkflowResultTimeoutException(workflowId, currentRunId)
            }

            logger.debug(
                "[result] Long-poll #$pollCount for workflow $workflowId (runId=$currentRunId, elapsed=${elapsed}ms)",
            )

            // Bounded long poll: server holds connection for up to longPollTimeout,
            // returning immediately when a close event arrives. The per-RPC timeout
            // ensures the FFM call duration is bounded.
            val request =
                GetWorkflowExecutionHistoryRequest
                    .newBuilder()
                    .setNamespace(serviceClient.namespace)
                    .setExecution(
                        WorkflowExecution
                            .newBuilder()
                            .setWorkflowId(workflowId)
                            .also { if (currentRunId != null) it.setRunId(currentRunId) }
                            .build(),
                    ).setHistoryEventFilterType(HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT)
                    .setWaitNewEvent(true)
                    .setSkipArchival(true)
                    .setNextPageToken(nextPageToken)
                    .build()

            val response =
                boundedPoll("result") {
                    // Recompute effective timeout each retry, capped to remaining user timeout
                    val remainingMs = timeoutMillis - (System.currentTimeMillis() - startTime)
                    if (remainingMs <= 0) throw ClientWorkflowResultTimeoutException(workflowId, currentRunId)
                    val effectiveTimeoutMillis = longPollTimeoutMillis.toLong().coerceAtMost(remainingMs).toInt()
                    serviceClient.getWorkflowExecutionHistory(request, timeoutMillis = effectiveTimeoutMillis)
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
                when (val result = handleCloseEvent(closeEvent)) {
                    is CloseEventResult.Completed -> {
                        return if (result.payload != null) {
                            codec.safeDecodeSingle(result.payload)
                        } else {
                            null
                        }
                    }
                    is CloseEventResult.Failed -> {
                        throw ClientWorkflowFailedException(
                            workflowId = workflowId,
                            runId = currentRunId,
                            workflowType = null,
                            failureMessage = result.failure?.message,
                            cause = result.failure?.let { buildCause(it, codec) },
                        )
                    }
                    is CloseEventResult.TimedOut -> {
                        throw ClientWorkflowTimedOutException(
                            workflowId = workflowId,
                            runId = currentRunId,
                            timeoutType = WorkflowTimeoutType.WORKFLOW_EXECUTION_TIMEOUT,
                        )
                    }
                    is CloseEventResult.FollowRun -> {
                        // Follow continuation without recursion — update local runId and re-poll
                        currentRunId = result.newRunId
                        nextPageToken = com.google.protobuf.ByteString.EMPTY
                        pollCount = 0
                        continue
                    }
                }
            }

            // Server returned without close event — update page token and re-poll immediately
            // (no delay needed, the server already held the connection for up to longPollTimeout)
            val responseToken = response.nextPageToken
            if (responseToken != null && !responseToken.isEmpty) {
                nextPageToken = responseToken
            }
        }
    }

    /**
     * Retries an RPC call on DEADLINE_EXCEEDED, for bounded long-polling of
     * blocking RPCs (updates, queries). Non-deadline errors are re-thrown immediately.
     */
    private suspend fun <T> boundedPoll(
        operation: String,
        block: suspend () -> T,
    ): T {
        while (true) {
            // Check for coroutine cancellation before each poll attempt so that
            // a user-initiated cancel isn't mistaken for an RPC timeout below.
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: TemporalCoreException) {
                // Rust Core SDK returns CANCELLED (1) for client-side timeout_millis expiry,
                // and the gRPC server returns DEADLINE_EXCEEDED (4) for server-side deadlines.
                // Both mean our bounded poll window elapsed — safe to retry.
                if (e.statusCode == GRPC_CANCELLED || e.statusCode == GRPC_DEADLINE_EXCEEDED) {
                    logger.debug("[{}] RPC timed out for workflow {}, re-polling", operation, workflowId)
                    continue
                }
                throw e
            }
        }
    }

    companion object {
        // Rust Core SDK returns CANCELLED (1) for client-side timeout_millis expiry,
        // while the gRPC server returns DEADLINE_EXCEEDED (4) for server-side deadlines.
        // Both indicate a bounded poll timeout that should be retried.
        private const val GRPC_CANCELLED = 1
        private const val GRPC_DEADLINE_EXCEEDED = 4

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

    private sealed class CloseEventResult {
        data class Completed(
            val payload: io.temporal.api.common.v1.Payload?,
        ) : CloseEventResult()

        data class FollowRun(
            val newRunId: String,
        ) : CloseEventResult()

        data class Failed(
            val failure: io.temporal.api.failure.v1.Failure?,
        ) : CloseEventResult()

        data object TimedOut : CloseEventResult()
    }

    private fun handleCloseEvent(event: HistoryEvent): CloseEventResult =
        when (event.eventType) {
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED -> {
                val attrs = event.workflowExecutionCompletedEventAttributes
                if (attrs.newExecutionRunId.isNotEmpty()) {
                    logger.info(
                        "[handleCloseEvent] Workflow $workflowId completed with continuation to runId=${attrs.newExecutionRunId}, following...",
                    )
                    CloseEventResult.FollowRun(attrs.newExecutionRunId)
                } else if (attrs.hasResult() && attrs.result.payloadsCount > 0) {
                    CloseEventResult.Completed(attrs.result.getPayloads(0))
                } else {
                    CloseEventResult.Completed(null)
                }
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED -> {
                val attrs = event.workflowExecutionFailedEventAttributes
                if (attrs.newExecutionRunId.isNotEmpty()) {
                    logger.info(
                        "[handleCloseEvent] Workflow $workflowId failed with retry to runId=${attrs.newExecutionRunId}, following...",
                    )
                    CloseEventResult.FollowRun(attrs.newExecutionRunId)
                } else {
                    CloseEventResult.Failed(if (attrs.hasFailure()) attrs.failure else null)
                }
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
                val attrs = event.workflowExecutionTimedOutEventAttributes
                if (attrs.newExecutionRunId.isNotEmpty()) {
                    logger.info(
                        "[handleCloseEvent] Workflow $workflowId timed out with retry to runId=${attrs.newExecutionRunId}, following...",
                    )
                    CloseEventResult.FollowRun(attrs.newExecutionRunId)
                } else {
                    CloseEventResult.TimedOut
                }
            }

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW -> {
                val attrs = event.workflowExecutionContinuedAsNewEventAttributes
                val newRunId = attrs.newExecutionRunId
                // Defensive check: newExecutionRunId should always be present for continued-as-new
                if (newRunId.isEmpty()) {
                    throw IllegalStateException(
                        "CONTINUED_AS_NEW event missing newExecutionRunId for workflow $workflowId (runId=$runId)",
                    )
                }
                logger.info(
                    "[handleCloseEvent] Workflow $workflowId continued-as-new to runId=$newRunId, following...",
                )
                CloseEventResult.FollowRun(newRunId)
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

        // Bounded poll: the server blocks until the update handler completes, but we
        // cap each RPC to longPollTimeout and retry with the same updateId (idempotent).
        val timeoutMillis = pollingConfig.longPollTimeout.inWholeMilliseconds.toInt()
        val response =
            boundedPoll("update $updateName") {
                serviceClient.updateWorkflowExecution(request, timeoutMillis = timeoutMillis)
            }

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

        // Bounded poll: the server blocks until a worker executes the query, but we
        // cap each RPC to longPollTimeout and retry (queries are idempotent).
        val timeoutMillis = pollingConfig.longPollTimeout.inWholeMilliseconds.toInt()
        val response =
            boundedPoll("query $queryType") {
                serviceClient.queryWorkflow(request, timeoutMillis = timeoutMillis)
            }

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
