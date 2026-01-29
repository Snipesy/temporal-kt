package com.surrealdev.temporal.activity.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.activity.ActivityCancelledException
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.SerializationException
import com.surrealdev.temporal.util.AttributeScope
import coresdk.CoreInterface
import coresdk.activityTaskCompletion
import coresdk.activity_result.activityExecutionResult
import coresdk.activity_result.failure
import coresdk.activity_result.success
import coresdk.activity_task.ActivityTaskOuterClass
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.reflect.full.callSuspend

/**
 * Tracks a running activity for cancellation purposes.
 *
 * @property job The coroutine job executing the activity
 * @property context The activity context (for marking cancellation)
 */
internal data class RunningActivity(
    val job: Job,
    val context: ActivityContextImpl,
)

/**
 * Dispatches activity tasks to registered activity implementations.
 *
 * This dispatcher handles:
 * - Looking up activity methods by type
 * - Deserializing input arguments
 * - Invoking the activity method with proper context
 * - Serializing the result or failure
 * - Enforcing concurrency limits via semaphore
 * - Tracking running activities for cancellation support
 */
@InternalTemporalApi
class ActivityDispatcher(
    private val registry: ActivityRegistry,
    private val serializer: PayloadSerializer,
    private val codec: PayloadCodec,
    private val taskQueue: String,
    maxConcurrent: Int,
    private val heartbeatFn: suspend (ByteArray, ByteArray?) -> Unit = { _, _ -> },
    /**
     * The task queue scope for hierarchical attribute lookup.
     * Its parentScope should be the application.
     */
    private val taskQueueScope: AttributeScope,
) {
    private val semaphore = Semaphore(maxConcurrent)
    private val runningActivities = ConcurrentHashMap<ByteString, RunningActivity>()

    companion object {
        private val logger = Logger.getLogger(ActivityDispatcher::class.java.name)
    }

    /**
     * Dispatches an activity task to the appropriate activity implementation.
     *
     * For Start tasks: Executes the activity and returns completion.
     * For Cancel tasks: Cancels the running activity (if found) and returns null.
     *
     * IMPORTANT: This must be called from within a coroutine that represents the
     * activity's execution context. The current coroutine's Job is used for
     * cancellation tracking.
     *
     * @param task The activity task to dispatch
     * @return The activity task completion, or null for Cancel tasks
     */
    suspend fun dispatch(task: ActivityTaskOuterClass.ActivityTask): CoreInterface.ActivityTaskCompletion? {
        val taskToken = task.taskToken

        // Handle cancel variant - does NOT acquire semaphore permit
        // Cancel tasks should be processed immediately to signal running activities
        if (task.hasCancel()) {
            handleCancelTask(taskToken, task.cancel)
            return null
        }

        // Get the current coroutine's Job for cancellation tracking
        val currentJob =
            currentCoroutineContext()[Job]
                ?: error("dispatch must be called from a coroutine with a Job")

        // Handle start variant - acquires semaphore permit for concurrency control
        return semaphore.withPermit {
            dispatchStartTask(task, currentJob)
        }
    }

    /**
     * Handles a Cancel task by finding and cancelling the running activity.
     *
     * The Cancel task has the same task_token as the Start task it's cancelling.
     * We look up the running activity, mark its context as cancelled, and cancel the job.
     */
    private fun handleCancelTask(
        taskToken: ByteString,
        cancel: ActivityTaskOuterClass.Cancel,
    ) {
        val running = runningActivities[taskToken]
        if (running == null) {
            // Activity not found - may have already completed
            // This is normal if the activity finished before cancellation arrived
            logger.fine { "Cancel task received for unknown activity (already completed?): $taskToken" }
            return
        }

        logger.info { "Cancelling activity with token $taskToken, reason: ${cancel.reason}" }

        // Map proto reason to sealed class exception
        val exception = mapCancelReason(cancel.reason)

        // Mark the context as cancelled so heartbeat checks will throw
        running.context.markCancelled(exception)

        // Cancel the coroutine job - this will cause CancellationException
        // which gets caught and converted to cancelled completion
        running.job.cancel(CancellationException("Activity cancelled: ${cancel.reason}"))
    }

    /**
     * Maps the proto ActivityCancelReason to the appropriate sealed class exception.
     */
    private fun mapCancelReason(reason: ActivityTaskOuterClass.ActivityCancelReason): ActivityCancelledException =
        when (reason) {
            ActivityTaskOuterClass.ActivityCancelReason.NOT_FOUND ->
                ActivityCancelledException.NotFound()
            ActivityTaskOuterClass.ActivityCancelReason.CANCELLED ->
                ActivityCancelledException.Cancelled()
            ActivityTaskOuterClass.ActivityCancelReason.TIMED_OUT ->
                ActivityCancelledException.TimedOut()
            ActivityTaskOuterClass.ActivityCancelReason.WORKER_SHUTDOWN ->
                ActivityCancelledException.WorkerShutdown()
            ActivityTaskOuterClass.ActivityCancelReason.PAUSED ->
                ActivityCancelledException.Paused()
            ActivityTaskOuterClass.ActivityCancelReason.RESET ->
                ActivityCancelledException.Reset()
            ActivityTaskOuterClass.ActivityCancelReason.UNRECOGNIZED ->
                ActivityCancelledException.Cancelled("Activity cancelled (unrecognized reason)")
        }

    /**
     * Dispatches a Start task and tracks it for potential cancellation.
     */
    private suspend fun dispatchStartTask(
        task: ActivityTaskOuterClass.ActivityTask,
        currentJob: Job,
    ): CoreInterface.ActivityTaskCompletion {
        val taskToken = task.taskToken

        // Handle invalid task
        if (!task.hasStart()) {
            return buildFailureCompletion(
                taskToken,
                "INVALID_TASK",
                "Activity task has neither start nor cancel variant",
            )
        }

        val start = task.start
        val activityType = start.activityType

        // Look up the activity method
        val methodInfo =
            registry.lookup(activityType)
                ?: return buildFailureCompletion(
                    taskToken,
                    "ACTIVITY_NOT_FOUND",
                    "Activity type not registered: $activityType",
                )

        // Create the activity context
        // The taskQueueScope provides hierarchical attribute lookup (taskQueue -> application)
        val context =
            ActivityContextImpl(
                start = start,
                taskToken = taskToken,
                taskQueue = taskQueue,
                serializer = serializer,
                heartbeatFn = heartbeatFn,
                parentScope = taskQueueScope,
                parentCoroutineContext = currentCoroutineContext(),
            )

        // Track this activity for cancellation
        val runningActivity = RunningActivity(job = currentJob, context = context)
        runningActivities[taskToken] = runningActivity

        try {
            // Deserialize arguments
            val args =
                try {
                    deserializeArguments(start.inputList, methodInfo.parameterTypes)
                } catch (e: SerializationException) {
                    return buildFailureCompletion(
                        taskToken,
                        "ARGUMENT_DESERIALIZATION_FAILED",
                        "Failed to deserialize activity arguments: ${e.message}",
                    )
                }

            // Invoke the activity method
            return try {
                val result = invokeMethod(methodInfo, context, args)
                buildSuccessCompletion(taskToken, result, methodInfo.returnType)
            } catch (e: ActivityCancelledException) {
                buildCancelledCompletion(taskToken)
            } catch (e: CancellationException) {
                // Coroutine was cancelled - treat as activity cancellation
                buildCancelledCompletion(taskToken)
            } catch (e: Exception) {
                buildFailureCompletion(taskToken, e)
            }
        } finally {
            // Always clean up tracking
            runningActivities.remove(taskToken)
        }
    }

    private suspend fun deserializeArguments(
        payloads: List<Payload>,
        parameterTypes: List<kotlin.reflect.KType>,
    ): Array<Any?> {
        if (payloads.size != parameterTypes.size) {
            throw SerializationException(
                "Argument count mismatch: expected ${parameterTypes.size}, got ${payloads.size}",
            )
        }

        // Decode payloads with codec first, then deserialize
        val decodedPayloads = codec.decode(payloads)
        return decodedPayloads
            .zip(parameterTypes)
            .map { (payload, type) ->
                serializer.deserialize(type, payload)
            }.toTypedArray()
    }

    private suspend fun invokeMethod(
        methodInfo: ActivityMethodInfo,
        context: ActivityContextImpl,
        args: Array<Any?>,
    ): Any? {
        val method = methodInfo.method

        // Run within a context that includes the ActivityContext
        // This allows activity code to access context via coroutineContext[ActivityContext]
        return withContext(context) {
            if (methodInfo.hasContextReceiver) {
                // Method has ActivityContext as extension receiver
                if (methodInfo.isSuspend) {
                    method.callSuspend(methodInfo.instance, context, *args)
                } else {
                    runInterruptible {
                        method.call(methodInfo.instance, context, *args)
                    }
                }
            } else {
                // Method does not use context receiver
                // Can still access context via coroutineContext[ActivityContext]
                if (methodInfo.isSuspend) {
                    method.callSuspend(methodInfo.instance, *args)
                } else {
                    runInterruptible {
                        method.call(methodInfo.instance, *args)
                    }
                }
            }
        }
    }

    private suspend fun buildSuccessCompletion(
        taskToken: ByteString,
        result: Any?,
        returnType: kotlin.reflect.KType,
    ): CoreInterface.ActivityTaskCompletion {
        val resultPayload =
            if (result == Unit || returnType.classifier == Unit::class) {
                // For Unit return type, we don't serialize the result
                Payload.getDefaultInstance()
            } else {
                // Serialize first, then encode with codec
                val serialized = serializer.serialize(returnType, result)
                codec.encode(listOf(serialized)).single()
            }

        return activityTaskCompletion {
            this.taskToken = taskToken
            this.result =
                activityExecutionResult {
                    completed =
                        success {
                            this.result = resultPayload
                        }
                }
        }
    }

    private fun buildFailureCompletion(
        taskToken: ByteString,
        exception: Exception,
    ): CoreInterface.ActivityTaskCompletion {
        // Unwrap InvocationTargetException from Kotlin/Java reflection
        val actualException = unwrapReflectionException(exception)
        val failure =
            Failure
                .newBuilder()
                .setMessage(actualException.message ?: actualException::class.simpleName ?: "Unknown error")
                .setStackTrace(actualException.stackTraceToString())
                .setSource("Kotlin")
                .build()

        return activityTaskCompletion {
            this.taskToken = taskToken
            this.result =
                activityExecutionResult {
                    failed =
                        failure {
                            this.failure = failure
                        }
                }
        }
    }

    private fun buildFailureCompletion(
        taskToken: ByteString,
        errorType: String,
        message: String,
    ): CoreInterface.ActivityTaskCompletion {
        val failure =
            Failure
                .newBuilder()
                .setMessage(message)
                .setSource("Kotlin")
                .build()

        return activityTaskCompletion {
            this.taskToken = taskToken
            this.result =
                activityExecutionResult {
                    failed =
                        failure {
                            this.failure = failure
                        }
                }
        }
    }

    private fun buildCancelledCompletion(taskToken: ByteString): CoreInterface.ActivityTaskCompletion =
        activityTaskCompletion {
            this.taskToken = taskToken
            this.result =
                activityExecutionResult {
                    cancelled = coresdk.activity_result.cancellation {}
                }
        }

    /**
     * Unwraps reflection-related exceptions to get the actual cause.
     *
     * When using Kotlin/Java reflection (method.call()), exceptions thrown by the
     * called method are wrapped in InvocationTargetException. This function
     * recursively unwraps these to find the actual exception.
     */
    private fun unwrapReflectionException(exception: Throwable): Throwable =
        when (exception) {
            is java.lang.reflect.InvocationTargetException -> {
                exception.cause?.let { unwrapReflectionException(it) } ?: exception
            }

            else -> {
                exception
            }
        }
}
