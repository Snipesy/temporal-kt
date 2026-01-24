package com.surrealdev.temporal.workflow.internal

import coresdk.workflow_commands.WorkflowCommands
import kotlinx.coroutines.CancellableContinuation
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

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

    createAndAddTimerCommand(seq, delayMillis)
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
 */
internal fun WorkflowExecutor.createAndAddTimerCommand(
    seq: Int,
    delayMillis: Long,
) {
    val duration = delayMillis.milliseconds
    val javaDuration = duration.toJavaDuration()
    val protoDuration =
        com.google.protobuf.Duration
            .newBuilder()
            .setSeconds(javaDuration.seconds)
            .setNanos(javaDuration.nano)
            .build()

    val command =
        WorkflowCommands.WorkflowCommand
            .newBuilder()
            .setStartTimer(
                WorkflowCommands.StartTimer
                    .newBuilder()
                    .setSeq(seq)
                    .setStartToFireTimeout(protoDuration),
            ).build()

    state.addCommand(command)
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
