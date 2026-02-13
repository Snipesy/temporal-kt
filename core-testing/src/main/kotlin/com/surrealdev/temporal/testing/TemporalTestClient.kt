package com.surrealdev.temporal.testing

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.client.TemporalClientImpl
import com.surrealdev.temporal.client.WorkflowExecutionList
import com.surrealdev.temporal.client.WorkflowHandle
import com.surrealdev.temporal.client.WorkflowStartOptions
import com.surrealdev.temporal.client.history.WorkflowHistory
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.core.TemporalTestServer
import com.surrealdev.temporal.serialization.PayloadSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Tracks time-skipping state locally to avoid redundant RPC calls.
 *
 * The test server uses a counter-based lock system. This tracker mirrors that
 * counter locally so we only make RPC calls when the state actually changes.
 *
 * Uses a Mutex to ensure thread-safe state transitions. When multiple handles
 * call result() concurrently, only the first unlock and last lock make actual
 * RPC calls to the server.
 *
 * Note: Like the Python SDK, concurrent workflow result awaiting from the same
 * client shares time-skipping state. All concurrent awaits will have time
 * skipping unlocked until all complete.
 */
internal class TimeSkippingStateTracker(
    private val testServer: TemporalTestServer,
) {
    private val mutex = Mutex()
    private var unlockCount = 0

    /**
     * Increments unlock count. Only makes RPC if transitioning from locked → unlocked.
     * Thread-safe via mutex.
     */
    suspend fun unlock() {
        mutex.withLock {
            if (unlockCount == 0) {
                testServer.unlockTimeSkipping()
            }
            unlockCount++
        }
    }

    /**
     * Decrements unlock count. Only makes RPC if transitioning from unlocked → locked.
     * Thread-safe via mutex.
     */
    suspend fun lock() {
        mutex.withLock {
            unlockCount--
            if (unlockCount == 0) {
                testServer.lockTimeSkipping()
            }
        }
    }
}

/**
 * A test client that wraps [TemporalClientImpl] and provides time-skipping support.
 *
 * When using a [TemporalTestServer], this client returns [TimeSkippingWorkflowHandle]s
 * that automatically unlock time skipping when awaiting workflow results, matching
 * the behavior of the Python SDK.
 *
 * Time skipping behavior:
 * - Time skipping starts **locked** (normal real-time behavior)
 * - When `result()` is called on a workflow handle, time skipping is **unlocked**
 * - After the result is obtained, time skipping is **locked** again
 *
 * This allows signals, updates, and queries to work normally (time locked),
 * while workflow results with timers complete instantly (time unlocked during await).
 */
class TemporalTestClient internal constructor(
    private val delegate: TemporalClientImpl,
    private val testServer: TemporalTestServer,
) : TemporalClient {
    // Shared state tracker for all handles from this client
    internal val timeSkippingState = TimeSkippingStateTracker(testServer)
    override val serializer: PayloadSerializer
        get() = delegate.serializer

    @OptIn(InternalTemporalApi::class)
    override suspend fun startWorkflowWithPayloads(
        workflowType: String,
        taskQueue: String,
        workflowId: String,
        args: TemporalPayloads,
        options: WorkflowStartOptions,
    ): WorkflowHandle {
        val handle =
            delegate.startWorkflowWithPayloads(
                workflowType = workflowType,
                taskQueue = taskQueue,
                workflowId = workflowId,
                args = args,
                options = options,
            )
        return TimeSkippingWorkflowHandle(handle, timeSkippingState)
    }

    override fun getWorkflowHandleInternal(
        workflowId: String,
        runId: String?,
    ): WorkflowHandle {
        val handle =
            delegate.getWorkflowHandleInternal(
                workflowId = workflowId,
                runId = runId,
            )
        return TimeSkippingWorkflowHandle(handle, timeSkippingState)
    }

    override suspend fun listWorkflows(
        query: String,
        pageSize: Int,
    ): WorkflowExecutionList = delegate.listWorkflows(query, pageSize)

    override suspend fun countWorkflows(query: String): Long = delegate.countWorkflows(query)

    override suspend fun close() {
        delegate.close()
    }
}

/**
 * A workflow handle wrapper that unlocks time skipping around [resultPayload] calls.
 *
 * This matches the Python SDK's `_TimeSkippingWorkflowHandle` behavior:
 * - Time skipping is unlocked before awaiting the result
 * - Time skipping is locked again after the result is obtained (or on error)
 *
 * Uses a shared [TimeSkippingStateTracker] to avoid redundant RPC calls when
 * multiple handles are awaited concurrently.
 *
 * All other operations (signal, update, query, etc.) pass through to the delegate
 * without modifying time skipping state.
 */
internal class TimeSkippingWorkflowHandle(
    private val delegate: WorkflowHandle,
    private val stateTracker: TimeSkippingStateTracker,
) : WorkflowHandle {
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
     *
     * When multiple handles call result() concurrently, only the first unlock
     * and last lock make actual RPC calls, thanks to the shared state tracker.
     */
    override suspend fun resultPayload(timeout: Duration): TemporalPayload? {
        stateTracker.unlock()
        try {
            return delegate.resultPayload(timeout)
        } finally {
            try {
                stateTracker.lock()
            } catch (_: Exception) {
                // Swallow errors when locking - the result is more important
            }
        }
    }

    // All other operations pass through without time skipping changes

    override suspend fun signalWithPayloads(
        signalName: String,
        args: TemporalPayloads,
    ) {
        delegate.signalWithPayloads(signalName, args)
    }

    override suspend fun updateWithPayloads(
        updateName: String,
        args: TemporalPayloads,
    ): TemporalPayloads = delegate.updateWithPayloads(updateName, args)

    override suspend fun queryWithPayloads(
        queryType: String,
        args: TemporalPayloads,
    ): TemporalPayloads = delegate.queryWithPayloads(queryType, args)

    override suspend fun cancel() {
        delegate.cancel()
    }

    override suspend fun terminate(reason: String?) {
        delegate.terminate(reason)
    }

    override suspend fun describe() = delegate.describe()

    override suspend fun getHistory(): WorkflowHistory = delegate.getHistory()
}
