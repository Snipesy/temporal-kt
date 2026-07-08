package com.surrealdev.temporal.workflow.internal

import coresdk.workflow_commands.WorkflowCommands
import kotlinx.coroutines.CancellableContinuation
import kotlin.time.Duration.Companion.milliseconds

/*
 * Extension functions for managing workflow timers in WorkflowExecutor.
 */

/**
 * Schedules a durable timer for a continuation.
 *
 * This is called by the [WorkflowCoroutineDispatcher] when [kotlinx.coroutines.delay]
 * is used in a workflow. It creates a proper durable timer command and registers
 * the continuation to be resumed when the timer fires.
 *
 * @param delayMillis The delay in milliseconds
 * @param continuation The continuation to resume when the timer fires
 */
internal fun WorkflowExecutor.scheduleTimerForContinuation(
    delayMillis: Long,
    continuation: CancellableContinuation<Unit>,
) {
    // Handle zero or negative delay - resume immediately
    if (delayMillis <= 0) {
        logger.debug("Timer with zero/negative delay ({}ms), resuming immediately", delayMillis)
        workflowDispatcher.dispatch(continuation.context) {
            continuation.resumeWith(Result.success(Unit))
        }
        return
    }

    val seq = state.nextSeq()
    logger.debug("Scheduling timer: seq={}, delay={}ms", seq, delayMillis)

    createAndAddTimerCommand(seq, delayMillis)
    state.registerTimerContinuation(seq, continuation)

    // If the delaying coroutine is cancelled before the timer fires, tell core to
    // cancel the server-side timer. Teardown paths (terminal completion, eviction)
    // clear the registry before cancelling, so no command is emitted there.
    continuation.invokeOnCancellation {
        if (!state.workflowCompleted && state.removeTimerContinuation(seq)) {
            createAndAddCancelTimerCommand(seq)
        }
    }
}

/**
 * Schedules a durable timer for a timeout callback.
 *
 * This is called by the [WorkflowCoroutineDispatcher] when [kotlinx.coroutines.withTimeout]
 * is used in a workflow. It creates a proper durable timer command and registers
 * the callback to be executed when the timer fires.
 *
 * @param delayMillis The delay in milliseconds
 * @param block The callback to execute when the timer fires
 * @return A handle that can be used to cancel the timeout
 */
internal fun WorkflowExecutor.scheduleTimeoutCallbackTimer(
    delayMillis: Long,
    block: Runnable,
): kotlinx.coroutines.DisposableHandle {
    // Consume the one-shot summary handoff (set by awaitCondition's timeout path)
    // even on the immediate-execution path, so it can never leak to a later timer
    val summary = state.consumePendingTimerSummary()

    // Handle zero or negative delay - execute immediately
    if (delayMillis <= 0) {
        logger.debug("Timeout with zero/negative delay ({}ms), executing immediately", delayMillis)
        workflowDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
            block.run()
        }
        return kotlinx.coroutines.DisposableHandle { }
    }

    val seq = state.nextSeq()
    logger.debug("Scheduling timeout callback: seq={}, delay={}ms", seq, delayMillis)

    createAndAddTimerCommand(seq, delayMillis, summary)
    state.registerTimeoutCallback(seq, block)

    // Return a handle that can cancel the timeout
    return kotlinx.coroutines.DisposableHandle {
        if (state.cancelTimeoutCallback(seq)) {
            createAndAddCancelTimerCommand(seq)
        }
    }
}

/**
 * Creates a StartTimer command and adds it to the workflow state.
 *
 * @param seq The sequence number for the timer
 * @param delayMillis The delay in milliseconds
 * @param summary Optional UI-facing summary, carried as user metadata on the command
 */
internal fun WorkflowExecutor.createAndAddTimerCommand(
    seq: Int,
    delayMillis: Long,
    summary: String? = null,
) {
    val protoDuration = delayMillis.milliseconds.toProtoDuration()

    val commandBuilder =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setStartTimer(
                WorkflowCommands.StartTimer
                    .newBuilder()
                    .setSeq(seq)
                    .setStartToFireTimeout(protoDuration),
            )

    summary?.let { commandBuilder.setUserMetadata(buildUserMetadata(serializer, it)) }

    state.addCommand(commandBuilder.build())
}

/**
 * Creates a CancelTimer command and adds it to the workflow state.
 *
 * @param seq The sequence number of the timer to cancel
 */
internal fun WorkflowExecutor.createAndAddCancelTimerCommand(seq: Int) {
    val cancelCommand =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setCancelTimer(
                WorkflowCommands.CancelTimer
                    .newBuilder()
                    .setSeq(seq),
            ).build()
    state.addCommand(cancelCommand)
}
