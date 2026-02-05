package com.surrealdev.temporal.common.exceptions

/**
 * Exception thrown when a workflow appears to be deadlocked.
 *
 * This typically indicates an infinite loop or blocking call in workflow code
 * that doesn't yield to the Temporal scheduler.
 *
 * The exception captures the stuck thread's stack trace at the time of detection,
 * which is included in the message and sent to the Temporal server for debugging.
 */
class WorkflowDeadlockException(
    private val baseMessage: String,
    /**
     * The name of the thread that triggered the deadlock detection.
     */
    val threadName: String? = null,
    /**
     * Stack trace of the stuck thread at the time of detection.
     * May differ from actual stuck location if there's delay between detection and capture.
     */
    val threadStackTrace: Array<StackTraceElement>? = null,
    /**
     * Timestamp when the deadlock was detected (timeout expired).
     */
    val detectionTimestamp: Long = System.currentTimeMillis(),
    /**
     * Timestamp when the thread's stack trace was captured.
     * May differ from detectionTimestamp if there's delay in capturing.
     */
    val stackCaptureTimestamp: Long = System.currentTimeMillis(),
) : TemporalCancellationException(baseMessage) {
    override val message: String
        get() =
            buildString {
                append(baseMessage)
                append(" {detectionTimestamp=")
                append(detectionTimestamp)
                append(", stackCaptureTimestamp=")
                append(stackCaptureTimestamp)
                append("}")

                if (!threadStackTrace.isNullOrEmpty()) {
                    append("\n\n")
                    append(threadName ?: "workflow-thread")
                    append("\n")
                    for (element in threadStackTrace) {
                        append("\tat ")
                        append(element)
                        append("\n")
                    }
                }
            }

    companion object {
        /**
         * Creates a WorkflowDeadlockException by capturing the stack trace from the given thread.
         *
         * @param thread The workflow thread that appears to be deadlocked
         * @param timeoutMs The deadlock detection timeout that was exceeded
         * @param detectionTimestamp When the deadlock was detected
         */
        fun fromThread(
            thread: Thread,
            timeoutMs: Long,
            detectionTimestamp: Long = System.currentTimeMillis(),
        ): WorkflowDeadlockException {
            val stackCaptureTimestamp = System.currentTimeMillis()
            val stackTrace = thread.stackTrace

            return WorkflowDeadlockException(
                baseMessage =
                    "Workflow didn't yield within ${timeoutMs}ms. " +
                        "Check for infinite loops or blocking calls without Workflow.runInterruptible().",
                threadName = thread.name,
                threadStackTrace = stackTrace,
                detectionTimestamp = detectionTimestamp,
                stackCaptureTimestamp = stackCaptureTimestamp,
            )
        }
    }
}
