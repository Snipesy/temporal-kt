package com.surrealdev.temporal.client

import com.surrealdev.temporal.client.history.WorkflowHistory
import com.surrealdev.temporal.client.internal.WorkflowServiceClient
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.TypeInfo
import com.surrealdev.temporal.serialization.typeInfoOf
import io.temporal.api.common.v1.Payloads
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.EventType
import io.temporal.api.enums.v1.HistoryEventFilterType
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest
import kotlinx.coroutines.delay
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.getLogger("WorkflowHandle")

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
     * @param signalName The name of the signal to send.
     * @param args Arguments to pass with the signal.
     */
    suspend fun signal(
        signalName: String,
        vararg args: Any?,
    )

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
    private val resultTypeInfo: TypeInfo,
    private val serviceClient: WorkflowServiceClient,
    private val serializer: PayloadSerializer,
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
                logger.warning("[result] Timeout waiting for workflow $workflowId after ${elapsed}ms")
                throw WorkflowResultTimeoutException(workflowId, runId)
            }

            logger.fine("[result] Poll #$pollCount for workflow $workflowId (elapsed=${elapsed}ms)")

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
                    logger.warning("[result] Poll failed for workflow $workflowId: ${e.message}")
                    // If request fails, continue polling
                    delay(200.milliseconds)
                    continue
                }

            logger.fine("[result] Got ${response.history.eventsCount} events for workflow $workflowId")

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
                throw WorkflowException("Unexpected close event type: ${event.eventType}")
            }
        }

    override suspend fun signal(
        signalName: String,
        vararg args: Any?,
    ) {
        val payloadsBuilder = Payloads.newBuilder()
        args.forEach { arg ->
            val typeInfo =
                if (arg != null) {
                    typeInfoOf(arg)
                } else {
                    typeInfoOf<Any?>()
                }
            payloadsBuilder.addPayloads(serializer.serialize(typeInfo, arg))
        }

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
                .setInput(payloadsBuilder.build())
                .build()

        serviceClient.signalWorkflowExecution(request)
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

/**
 * Creates a type info for runtime values.
 * Note: This loses generic type information.
 */
private fun typeInfoOf(value: Any): TypeInfo =
    TypeInfo(
        type = value::class.createType(nullable = false),
        reifiedClass = value::class,
    )

/**
 * Extension function to create a KType from KClass.
 */
private fun kotlin.reflect.KClass<*>.createType(nullable: Boolean = false): kotlin.reflect.KType =
    object : kotlin.reflect.KType {
        override val annotations: List<Annotation> = emptyList()
        override val arguments: List<kotlin.reflect.KTypeProjection> = emptyList()
        override val classifier: kotlin.reflect.KClassifier = this@createType
        override val isMarkedNullable: Boolean = nullable
    }
