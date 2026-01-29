package com.surrealdev.temporal.workflow.internal

import coresdk.activity_result.ActivityResult
import coresdk.child_workflow.ChildWorkflow
import coresdk.workflow_activation.WorkflowActivationOuterClass.ResolveChildWorkflowExecutionStart
import coresdk.workflow_commands.WorkflowCommands.WorkflowCommand
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Manages the state of a single workflow execution.
 *
 * This class tracks:
 * - Sequence numbers for deterministic command ordering
 * - Pending operations (timers, activities) that are waiting for resolution
 * - Commands to send back to the Temporal server
 * - Workflow time and replay state
 *
 * Thread safety: This class is designed for single-threaded access within a workflow.
 * Multiple workflow runs can exist concurrently, but each run is processed sequentially.
 */
internal class WorkflowState(
    val runId: String,
) {
    companion object {
        private val logger = Logger.getLogger(WorkflowState::class.java.name)
    }

    /**
     * Sequence number counter for deterministic operation ordering.
     * Incremented for each timer, activity, child workflow, etc.
     */
    private var nextSeq = 1

    /**
     * Generates the next sequence number.
     * This is a mutation operation and cannot be performed in read-only mode.
     */
    fun nextSeq(): Int {
        if (isReadOnly) {
            throw ReadOnlyContextException(
                "Cannot generate sequence number in read-only mode (e.g., during query processing)",
            )
        }

        val seq = nextSeq++

        // Check for MAX_VALUE before field wraps around
        if (seq == Int.MAX_VALUE) {
            throw IllegalStateException(
                "Sequence overflow at MAX_VALUE. runId=$runId",
            )
        }

        // Overflow protection warnings (check field value after increment)
        when (nextSeq) {
            1_000_000_000 -> {
                logger.warning(
                    "Workflow reached 1 billion operations. runId=$runId",
                )
            }

            2_000_000_000 -> {
                logger.severe(
                    "CRITICAL: Workflow reached 2 billion operations. runId=$runId",
                )
            }
        }

        return seq
    }

    /**
     * Current workflow time as provided by the activation.
     * This is deterministic and survives replay.
     */
    var currentTime: Instant = Instant.fromEpochMilliseconds(0)
        private set

    /**
     * Whether cancellation has been requested for this workflow.
     * Once true, remains true for the lifetime of the workflow run.
     */
    var cancelRequested: Boolean = false
        internal set

    /**
     * Whether the workflow is currently replaying past events.
     * When replaying, side effects should not be executed.
     */
    var isReplaying: Boolean = false
        private set

    /**
     * Random seed for deterministic random number generation.
     * Updated when the activation contains an UpdateRandomSeed job.
     */
    var randomSeed: Long = 0
        internal set

    /**
     * Whether the workflow is in read-only mode (e.g., during query processing).
     * When true, any attempt to mutate workflow state will throw [ReadOnlyContextException].
     * This ensures queries cannot affect workflow history.
     */
    var isReadOnly: Boolean = false
        internal set

    /**
     * History length (event count) as of the current activation.
     */
    var historyLength: Int = 0
        private set

    /**
     * History size in bytes as of the current activation.
     * Can be used alongside [continueAsNewSuggested] to make continue-as-new decisions.
     */
    var historySizeBytes: Long = 0
        private set

    /**
     * Server's recommendation on whether to continue-as-new.
     * This is set by the server based on both history length and size limits.
     */
    var continueAsNewSuggested: Boolean = false
        private set

    /**
     * Pending timer operations (deferred-based), keyed by sequence number.
     * Used by WorkflowContext.sleep().
     */
    private val pendingTimers = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    /**
     * Pending timer operations (continuation-based), keyed by sequence number.
     * Used by kotlinx.coroutines.delay() interception.
     */
    private val pendingTimerContinuations = ConcurrentHashMap<Int, CancellableContinuation<Unit>>()

    /**
     * Pending timeout callback operations, keyed by sequence number.
     * Used by kotlinx.coroutines.withTimeout() interception.
     */
    private val pendingTimeoutCallbacks = ConcurrentHashMap<Int, Runnable>()

    /**
     * Pending activity operations, keyed by sequence number.
     */
    private val pendingActivities = ConcurrentHashMap<Int, RemoteActivityHandleImpl<*>>()

    /**
     * Pending child workflow operations, keyed by sequence number.
     */
    private val pendingChildWorkflows = ConcurrentHashMap<Int, ChildWorkflowHandleImpl<*>>()

    /**
     * Pending local activity operations, keyed by sequence number.
     * Separate from regular activities because local activities have different
     * resolution handling (DoBackoff support, marker-based replay).
     */
    private val pendingLocalActivities = ConcurrentHashMap<Int, LocalActivityHandleImpl<*>>()

    /**
     * Registered conditions waiting to be satisfied.
     * Each entry is a pair of (predicate, deferred) where the deferred completes when the predicate returns true.
     * This enables deterministic condition waiting without busy-wait loops.
     */
    private val conditions = mutableListOf<Pair<() -> Boolean, CompletableDeferred<Unit>>>()

    /**
     * Patches notified by Core during replay (from NotifyHasPatch jobs).
     * When Core sends a NotifyHasPatch job, it means a patch marker exists in history.
     */
    private val notifiedPatches = mutableSetOf<String>()

    /**
     * Memoized patch results for deterministic behavior within a single execution.
     * Once patched() is called for a patch ID, the result is cached here.
     */
    private val patchMemo = mutableMapOf<String, Boolean>()

    /**
     * Commands accumulated during this activation.
     * Drained and sent back to the server at the end of activation processing.
     */
    private val commands = mutableListOf<WorkflowCommand>()

    /**
     * Updates state from the activation's metadata.
     */
    fun updateFromActivation(
        timestamp: com.google.protobuf.Timestamp?,
        isReplaying: Boolean,
        historyLength: Int,
        historySizeBytes: Long = 0,
        continueAsNewSuggested: Boolean = false,
    ) {
        if (timestamp != null) {
            val javaInstant = java.time.Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())
            this.currentTime = javaInstant.toKotlinInstant()
        }
        this.isReplaying = isReplaying
        this.historyLength = historyLength
        this.historySizeBytes = historySizeBytes
        this.continueAsNewSuggested = continueAsNewSuggested
    }

    /**
     * Registers a pending timer and returns a deferred to await its completion.
     * Used by WorkflowContext.sleep().
     */
    fun registerTimer(seq: Int): CompletableDeferred<Unit> {
        if (isReadOnly) {
            throw ReadOnlyContextException("Cannot register timer in read-only mode (e.g., during query processing)")
        }
        val deferred = CompletableDeferred<Unit>()
        pendingTimers[seq] = deferred
        return deferred
    }

    /**
     * Registers a pending timer with a continuation to resume when it fires.
     * Used by kotlinx.coroutines.delay() interception.
     *
     * @param seq The sequence number for this timer
     * @param continuation The continuation to resume when the timer fires
     */
    fun registerTimerContinuation(
        seq: Int,
        continuation: CancellableContinuation<Unit>,
    ) {
        if (isReadOnly) {
            throw ReadOnlyContextException("Cannot register timer in read-only mode (e.g., during query processing)")
        }
        pendingTimerContinuations[seq] = continuation
    }

    /**
     * Registers a timeout callback to be executed when the timer fires.
     * Used by kotlinx.coroutines.withTimeout() interception.
     *
     * @param seq The sequence number for this timer
     * @param callback The callback to run when the timer fires
     */
    fun registerTimeoutCallback(
        seq: Int,
        callback: Runnable,
    ) {
        if (isReadOnly) {
            throw ReadOnlyContextException(
                "Cannot register timeout callback in read-only mode (e.g., during query processing)",
            )
        }
        pendingTimeoutCallbacks[seq] = callback
    }

    /**
     * Cancels a pending timeout callback by its sequence number.
     * Used when the operation completes before the timeout fires.
     *
     * @param seq The sequence number of the timeout to cancel
     * @return true if the callback was found and removed, false otherwise
     */
    fun cancelTimeoutCallback(seq: Int): Boolean = pendingTimeoutCallbacks.remove(seq) != null

    /**
     * Resolves a timer by its sequence number.
     * Called when a FireTimer job is received in an activation.
     * Handles deferred-based, continuation-based, and callback-based timers.
     *
     * @return The timeout callback to execute, or null if none
     */
    fun resolveTimer(seq: Int): Runnable? {
        // Try deferred-based timer first
        pendingTimers.remove(seq)?.complete(Unit)
        // Then try continuation-based timer
        pendingTimerContinuations.remove(seq)?.resume(Unit)
        // Return timeout callback if present (caller should execute it)
        return pendingTimeoutCallbacks.remove(seq)
    }

    /**
     * Registers a pending activity handle.
     */
    fun registerActivity(
        seq: Int,
        handle: RemoteActivityHandleImpl<*>,
    ) {
        if (isReadOnly) {
            throw ReadOnlyContextException("Cannot register activity in read-only mode (e.g., during query processing)")
        }
        pendingActivities[seq] = handle
    }

    /**
     * Gets a pending activity by its sequence number.
     */
    fun getActivity(seq: Int): RemoteActivityHandleImpl<*>? = pendingActivities[seq]

    /**
     * Resolves an activity by its sequence number.
     * Delegates to ActivityHandleImpl.resolve() for all exception mapping.
     */
    fun resolveActivity(
        seq: Int,
        result: ActivityResult.ActivityResolution,
    ) {
        val handle = pendingActivities.remove(seq) ?: return
        handle.resolve(result)
    }

    /**
     * Registers a pending local activity handle.
     */
    fun registerLocalActivity(
        seq: Int,
        handle: LocalActivityHandleImpl<*>,
    ) {
        if (isReadOnly) {
            throw ReadOnlyContextException(
                "Cannot register local activity in read-only mode (e.g., during query processing)",
            )
        }
        pendingLocalActivities[seq] = handle
    }

    /**
     * Gets a pending local activity by its sequence number.
     */
    fun getLocalActivity(seq: Int): LocalActivityHandleImpl<*>? = pendingLocalActivities[seq]

    /**
     * Resolves a local activity by its sequence number.
     *
     * Local activities can resolve with:
     * - Completed: Activity finished successfully
     * - Failed: Activity failed after all retries
     * - Cancelled: Activity was cancelled
     * - Backoff: Retry delay exceeds threshold, lang should schedule timer and retry
     *
     * For backoff resolution, the handle is NOT removed from pending because
     * a new ScheduleLocalActivity command will be sent with a new sequence number.
     */
    fun resolveLocalActivity(
        seq: Int,
        result: ActivityResult.ActivityResolution,
    ) {
        val handle =
            pendingLocalActivities[seq] ?: run {
                logger.warning("No pending local activity found for seq=$seq")
                return
            }

        when {
            result.hasCompleted() -> {
                pendingLocalActivities.remove(seq)
                handle.resolveCompleted(result.completed)
            }

            result.hasFailed() -> {
                pendingLocalActivities.remove(seq)
                handle.resolveFailed(result.failed)
            }

            result.hasCancelled() -> {
                pendingLocalActivities.remove(seq)
                handle.resolveCancelled(result.cancelled)
            }

            result.hasBackoff() -> {
                // For backoff, we remove from current seq but the handle will re-register
                // with a new seq after the timer fires
                pendingLocalActivities.remove(seq)
                handle.resolveBackoff(result.backoff)
            }

            else -> {
                logger.severe("Unknown local activity resolution status for seq=$seq")
                pendingLocalActivities.remove(seq)
                throw IllegalStateException("Unknown local activity resolution status for seq=$seq")
            }
        }
    }

    /**
     * Registers a pending child workflow handle.
     * The handle manages its own deferreds for start and execution resolution.
     */
    fun registerChildWorkflow(
        seq: Int,
        handle: ChildWorkflowHandleImpl<*>,
    ) {
        if (isReadOnly) {
            throw ReadOnlyContextException(
                "Cannot register child workflow in read-only mode (e.g., during query processing)",
            )
        }
        pendingChildWorkflows[seq] = handle
    }

    /**
     * Gets a pending child workflow by its sequence number.
     */
    fun getChildWorkflow(seq: Int): ChildWorkflowHandleImpl<*>? = pendingChildWorkflows[seq]

    /**
     * Resolves a child workflow start by its sequence number.
     * Called when a ResolveChildWorkflowExecutionStart job is received.
     */
    fun resolveChildWorkflowStart(
        seq: Int,
        resolution: ResolveChildWorkflowExecutionStart,
    ) {
        val handle = pendingChildWorkflows[seq] ?: return
        handle.resolveStart(resolution)

        // If start failed, remove from pending (no execution resolution will come)
        if (resolution.hasFailed() || resolution.hasCancelled()) {
            pendingChildWorkflows.remove(seq)
        }
    }

    /**
     * Resolves a child workflow execution by its sequence number.
     * Called when a ResolveChildWorkflowExecution job is received.
     */
    fun resolveChildWorkflowExecution(
        seq: Int,
        result: ChildWorkflow.ChildWorkflowResult,
    ) {
        val handle = pendingChildWorkflows.remove(seq) ?: return
        handle.resolveExecution(result)
    }

    /**
     * Registers a condition and returns a deferred that completes when the condition becomes true.
     * The condition will be checked during the event loop after signals/updates and non-query jobs.
     *
     * @param predicate The condition to check
     * @return A deferred that completes when the predicate returns true
     */
    fun registerCondition(predicate: () -> Boolean): CompletableDeferred<Unit> {
        if (isReadOnly) {
            throw ReadOnlyContextException(
                "Cannot register condition in read-only mode (e.g., during query processing)",
            )
        }
        val deferred = CompletableDeferred<Unit>()
        conditions.add(predicate to deferred)
        return deferred
    }

    /**
     * Removes a condition from the registry by its deferred.
     * Called for cleanup when the await is canceled (e.g., by timeout).
     *
     * @param deferred The deferred associated with the condition to remove
     * @return true if the condition was found and removed, false otherwise
     */
    fun removeCondition(deferred: CompletableDeferred<Unit>): Boolean {
        val iterator = conditions.iterator()
        while (iterator.hasNext()) {
            val (_, condDeferred) = iterator.next()
            if (condDeferred === deferred) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    /**
     * Checks all registered conditions and completes any whose predicates are now true.
     * This is called after processing signals/updates and non-query jobs to allow
     * condition-based workflow logic to proceed deterministically.
     *
     * Conditions that are already completed (e.g., canceled by timeout) are removed
     * without evaluating their predicates.
     */
    fun checkConditions() {
        val iterator = conditions.iterator()
        while (iterator.hasNext()) {
            val (predicate, deferred) = iterator.next()

            // Skip if already completed (e.g., canceled by timeout)
            if (deferred.isCompleted) {
                iterator.remove()
                continue
            }

            try {
                if (predicate()) {
                    deferred.complete(Unit)
                    iterator.remove()
                }
            } catch (e: Exception) {
                // If the predicate throws, complete the deferred with the exception
                deferred.completeExceptionally(e)
                iterator.remove()
            }
        }
    }

    /**
     * Records that a patch was notified by Core.
     * Called when a NotifyHasPatch job is processed.
     *
     * @param patchId The patch identifier from the NotifyHasPatch job
     */
    fun notifyPatch(patchId: String) {
        notifiedPatches.add(patchId)
    }

    /**
     * Checks if a patch was notified by Core (exists in history).
     *
     * @param patchId The patch identifier to check
     * @return true if Core notified this patch (marker exists in history)
     */
    fun isPatchNotified(patchId: String): Boolean = patchId in notifiedPatches

    /**
     * Gets the memoized result for a patch ID.
     *
     * @param patchId The patch identifier
     * @return The memoized result, or null if not yet evaluated
     */
    fun getPatchMemo(patchId: String): Boolean? = patchMemo[patchId]

    /**
     * Memoizes the result for a patch ID.
     *
     * @param patchId The patch identifier
     * @param value The result to memoize
     */
    fun setPatchMemo(
        patchId: String,
        value: Boolean,
    ) {
        patchMemo[patchId] = value
    }

    /**
     * Adds a command to be sent at the end of this activation.
     * Throws [ReadOnlyContextException] if called in read-only mode (e.g., during query processing).
     */
    fun addCommand(cmd: WorkflowCommand) {
        if (isReadOnly) {
            throw ReadOnlyContextException("Cannot add command in read-only mode (e.g., during query processing)")
        }
        commands.add(cmd)
    }

    /**
     * Drains all accumulated commands and clears the list.
     */
    fun drainCommands(): List<WorkflowCommand> {
        val result = commands.toList()
        commands.clear()
        return result
    }

    /**
     * Checks if there are any pending commands.
     */
    fun hasCommands(): Boolean = commands.isNotEmpty()

    /**
     * Clears all pending operations.
     * Called on workflow eviction or completion.
     */
    fun clear() {
        // Cancel all pending timers (deferred-based)
        pendingTimers.values.forEach { it.cancel() }
        pendingTimers.clear()

        // Cancel all pending timers (continuation-based)
        pendingTimerContinuations.values.forEach { it.cancel() }
        pendingTimerContinuations.clear()

        // Clear all pending timeout callbacks (no need to cancel, just discard)
        pendingTimeoutCallbacks.clear()

        // Cancel all pending activities
        pendingActivities.values.forEach { it.resultDeferred.cancel() }
        pendingActivities.clear()

        // Clear all pending local activities
        pendingLocalActivities.clear()

        // Cancel all pending child workflows
        pendingChildWorkflows.values.forEach {
            it.startDeferred.cancel()
            it.executionDeferred.cancel()
        }
        pendingChildWorkflows.clear()

        // Cancel all pending conditions
        conditions.forEach { (_, deferred) -> deferred.cancel() }
        conditions.clear()

        // Clear patch tracking
        notifiedPatches.clear()
        patchMemo.clear()

        commands.clear()
    }
}

/**
 * Exception thrown when an activity fails.
 */
class ActivityFailureException(
    message: String?,
    val activityType: String = "",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception thrown when attempting to mutate workflow state in read-only mode.
 * This typically occurs during query processing, where modifications to workflow
 * state would violate deterministic replay guarantees.
 */
class ReadOnlyContextException(
    message: String,
) : RuntimeException(message)
