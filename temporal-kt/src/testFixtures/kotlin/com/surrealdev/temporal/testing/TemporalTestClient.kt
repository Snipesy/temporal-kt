package com.surrealdev.temporal.testing

import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.client.TemporalClientImpl
import com.surrealdev.temporal.client.WorkflowHandle
import com.surrealdev.temporal.client.WorkflowStartOptions
import com.surrealdev.temporal.client.history.WorkflowHistory
import com.surrealdev.temporal.core.TemporalTestServer
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.TypeInfo
import io.temporal.api.common.v1.Payloads
import kotlin.time.Duration

/**
 * A test client that wraps [TemporalClientImpl] and provides time-skipping support.
 *
 * When using a [TemporalTestServer], this client returns [TimeSkippingWorkflowHandle]s
 * that automatically unlock time skipping when awaiting workflow results, matching
 * the behavior of the Python SDK.
 *
 * Time skipping behavior:
 * - Time skipping starts **locked** (normal real-time behavior)
 * - When [WorkflowHandle.result] is called, time skipping is **unlocked**
 * - After the result is obtained, time skipping is **locked** again
 *
 * This allows signals, updates, and queries to work normally (time locked),
 * while workflow results with timers complete instantly (time unlocked during await).
 */
class TemporalTestClient internal constructor(
    private val delegate: TemporalClientImpl,
    private val testServer: TemporalTestServer,
) : TemporalClient {
    override val serializer: PayloadSerializer
        get() = delegate.serializer

    override suspend fun <R> startWorkflow(
        workflowType: String,
        taskQueue: String,
        workflowId: String,
        args: Payloads,
        options: WorkflowStartOptions,
        resultTypeInfo: TypeInfo,
    ): WorkflowHandle<R> {
        val handle =
            delegate.startWorkflow<R>(
                workflowType = workflowType,
                taskQueue = taskQueue,
                workflowId = workflowId,
                args = args,
                options = options,
                resultTypeInfo = resultTypeInfo,
            )
        return TimeSkippingWorkflowHandle(handle, testServer)
    }

    override fun <R> getWorkflowHandleInternal(
        workflowId: String,
        runId: String?,
        resultTypeInfo: TypeInfo,
    ): WorkflowHandle<R> {
        val handle =
            delegate.getWorkflowHandleInternal<R>(
                workflowId = workflowId,
                runId = runId,
                resultTypeInfo = resultTypeInfo,
            )
        return TimeSkippingWorkflowHandle(handle, testServer)
    }

    override suspend fun close() {
        delegate.close()
    }
}

/**
 * A workflow handle wrapper that unlocks time skipping around [result] calls.
 *
 * This matches the Python SDK's `_TimeSkippingWorkflowHandle` behavior:
 * - Time skipping is unlocked before awaiting the result
 * - Time skipping is locked again after the result is obtained (or on error)
 *
 * All other operations (signal, update, query, etc.) pass through to the delegate
 * without modifying time skipping state.
 */
internal class TimeSkippingWorkflowHandle<R>(
    private val delegate: WorkflowHandle<R>,
    private val testServer: TemporalTestServer,
) : WorkflowHandle<R> {
    override val workflowId: String
        get() = delegate.workflowId

    override val runId: String?
        get() = delegate.runId

    override val serializer: PayloadSerializer
        get() = delegate.serializer

    /**
     * Waits for the workflow to complete, with automatic time skipping.
     *
     * Time skipping is unlocked before awaiting the result and locked again
     * after the result is obtained (or if an error occurs).
     */
    override suspend fun result(timeout: Duration): R {
        testServer.unlockTimeSkipping()
        try {
            return delegate.result(timeout)
        } finally {
            try {
                testServer.lockTimeSkipping()
            } catch (_: Exception) {
                // Swallow errors when locking - the result is more important
            }
        }
    }

    // All other operations pass through without time skipping changes

    override suspend fun signal(
        signalName: String,
        args: Payloads,
    ) {
        delegate.signal(signalName, args)
    }

    override suspend fun update(
        updateName: String,
        args: Payloads,
    ): Payloads = delegate.update(updateName, args)

    override suspend fun query(
        queryType: String,
        args: Payloads,
    ): Payloads = delegate.query(queryType, args)

    override suspend fun cancel() {
        delegate.cancel()
    }

    override suspend fun terminate(reason: String?) {
        delegate.terminate(reason)
    }

    override suspend fun describe() = delegate.describe()

    override suspend fun getHistory(): WorkflowHistory = delegate.getHistory()
}
