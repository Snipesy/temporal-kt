package com.surrealdev.temporal.activity.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.activity.ActivityCancelledException
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.internal.ZombieEvictionManager
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.callSuspend

/**
 * Tracks a running activity for cancellation purposes.
 *
 * @property job The coroutine job executing the activity
 * @property context The activity context (for marking cancellation)
 * @property virtualThread The virtual thread running this activity (for forced interruption)
 */
internal data class RunningActivity(
    val job: Job,
    val context: ActivityContextImpl,
    val virtualThread: ActivityVirtualThread? = null,
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
    /**
     * Grace period in milliseconds to wait for an activity thread to terminate after interrupt.
     * If the thread doesn't terminate within this time, it's considered a zombie.
     */
    private val terminationGracePeriodMs: Long = 60_000L,
    /**
     * Maximum number of zombie threads before forcing worker shutdown.
     * Set to 0 to disable (not recommended).
     */
    private val maxZombieCount: Int = 10,
    /**
     * Callback invoked when a fatal error occurs (e.g., zombie threshold exceeded).
     * This allows the application to gracefully shut down instead of calling System.exit().
     */
    private val onFatalError: (suspend () -> Unit)? = null,
    /**
     * Maximum number of retry attempts for zombie eviction before giving up.
     */
    private val maxZombieRetries: Int = 100,
    /**
     * Interval between zombie eviction retry attempts.
     */
    private val zombieRetryIntervalMs: Long = 5_000L,
    /**
     * Timeout for waiting on zombie eviction jobs during shutdown.
     */
    private val zombieEvictionShutdownTimeoutMs: Long = 30_000L,
) {
    private val semaphore = Semaphore(maxConcurrent)
    private val runningActivities = ConcurrentHashMap<ByteString, RunningActivity>()

    private val logger = LoggerFactory.getLogger(ActivityDispatcher::class.java)

    /** Manages zombie thread detection and eviction. */
    private val zombieManager =
        ZombieEvictionManager(
            logger = logger,
            taskQueue = taskQueue,
            terminationGracePeriodMs = terminationGracePeriodMs,
            maxZombieCount = maxZombieCount,
            maxZombieRetries = maxZombieRetries,
            zombieRetryIntervalMs = zombieRetryIntervalMs,
            evictionShutdownTimeoutMs = zombieEvictionShutdownTimeoutMs,
            onFatalError = onFatalError,
            errorCodePrefix = "TKT12",
            entityType = "activity",
        )

    /**
     * Dispatches a Cancel task to signal cancellation to a running activity.
     *
     * This method handles cancel tasks only and returns immediately.
     * For start tasks, use [dispatchStart] instead.
     *
     * @param task The cancel task to dispatch (must have hasCancel() == true)
     */
    fun dispatchCancel(task: ActivityTaskOuterClass.ActivityTask) {
        require(task.hasCancel()) { "dispatchCancel requires a Cancel task" }
        handleCancelTask(task.taskToken, task.cancel)
    }

    /**
     * Dispatches a Start task to execute an activity on a virtual thread.
     *
     * IMPORTANT: This must be called from within a coroutine that represents the
     * activity's execution context. The current coroutine's Job is used for
     * cancellation tracking.
     *
     * @param task The start task to dispatch (must have hasStart() == true)
     * @param virtualThread The virtual thread running this activity (for cancellation support)
     * @return The activity task completion
     */
    internal suspend fun dispatchStart(
        task: ActivityTaskOuterClass.ActivityTask,
        virtualThread: ActivityVirtualThread,
    ): CoreInterface.ActivityTaskCompletion {
        require(task.hasStart()) { "dispatchStart requires a Start task" }

        // Get the current coroutine's Job for cancellation tracking
        val currentJob =
            currentCoroutineContext()[Job]
                ?: error("dispatchStart must be called from a coroutine with a Job")

        // Acquire semaphore permit for concurrency control
        return semaphore.withPermit {
            dispatchStartTask(task, currentJob, virtualThread)
        }
    }

    /**
     * Dispatches a Start task without virtual thread tracking.
     *
     * This is intended for test harnesses that don't use virtual threads.
     * Thread-based cancellation won't work - only coroutine cancellation is supported.
     *
     * @param task The start task to dispatch (must have hasStart() == true)
     * @return The activity task completion
     */
    @InternalTemporalApi
    suspend fun dispatchForTest(task: ActivityTaskOuterClass.ActivityTask): CoreInterface.ActivityTaskCompletion {
        require(task.hasStart()) { "dispatchForTest requires a Start task" }

        val currentJob =
            currentCoroutineContext()[Job]
                ?: error("dispatchForTest must be called from a coroutine with a Job")

        return semaphore.withPermit {
            dispatchStartTask(task, currentJob, virtualThread = null)
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
            logger.debug("Cancel task received for unknown activity (already completed?): {}", taskToken)
            return
        }

        logger.info("Cancelling activity with token {}, reason: {}", taskToken, cancel.reason)

        // Map proto reason to sealed class exception
        val exception = mapCancelReason(cancel.reason)

        // Mark the context as cancelled so heartbeat checks will throw
        running.context.markCancelled(exception)

        // Cancel the coroutine job - this will cause CancellationException
        // which gets caught and converted to cancelled completion
        running.job.cancel(CancellationException("Activity cancelled: ${cancel.reason}"))

        // Interrupt the virtual thread if present and check for zombies
        running.virtualThread?.let { vt ->
            val thread = vt.getThread()
            if (thread.isAlive) {
                logger.debug("Interrupting activity virtual thread for token {}", taskToken)
                thread.interrupt()

                // Check async if thread responded to interrupt
                val activityType = running.context.info.activityType
                val activityId = running.context.info.activityId
                launchZombieCheck(vt, activityType, activityId)
            }
        }
    }

    /**
     * Launches an async job to terminate an activity thread and monitor for zombies.
     * This method does NOT block - it launches a job that handles termination asynchronously.
     */
    private fun launchZombieCheck(
        virtualThread: ActivityVirtualThread,
        activityType: String,
        activityId: String,
    ) {
        // Use thread ID for stable zombie identification (prevents duplicate jobs for same thread)
        val zombieId = "activity-${virtualThread.getThread().threadId()}"

        zombieManager.launchEviction(
            zombieId = zombieId,
            entityId = activityId,
            entityName = activityType,
            terminateFn = { imm -> virtualThread.terminate(immediate = imm) },
            interruptFn = { virtualThread.interruptThread() },
            isAliveFn = { virtualThread.isAlive() },
            awaitTerminationFn = { timeout -> virtualThread.awaitTermination(timeout) },
            immediate = true, // Activities always use immediate termination on cancel
        )
    }

    /**
     * Maps the proto ActivityCancelReason to the appropriate sealed class exception.
     */
    private fun mapCancelReason(reason: ActivityTaskOuterClass.ActivityCancelReason): ActivityCancelledException =
        when (reason) {
            ActivityTaskOuterClass.ActivityCancelReason.NOT_FOUND -> {
                ActivityCancelledException.NotFound()
            }

            ActivityTaskOuterClass.ActivityCancelReason.CANCELLED -> {
                ActivityCancelledException.Cancelled()
            }

            ActivityTaskOuterClass.ActivityCancelReason.TIMED_OUT -> {
                ActivityCancelledException.TimedOut()
            }

            ActivityTaskOuterClass.ActivityCancelReason.WORKER_SHUTDOWN -> {
                ActivityCancelledException.WorkerShutdown()
            }

            ActivityTaskOuterClass.ActivityCancelReason.PAUSED -> {
                ActivityCancelledException.Paused()
            }

            ActivityTaskOuterClass.ActivityCancelReason.RESET -> {
                ActivityCancelledException.Reset()
            }

            ActivityTaskOuterClass.ActivityCancelReason.UNRECOGNIZED -> {
                ActivityCancelledException.Cancelled("Activity cancelled (unrecognized reason)")
            }
        }

    /**
     * Dispatches a Start task and tracks it for potential cancellation.
     */
    private suspend fun dispatchStartTask(
        task: ActivityTaskOuterClass.ActivityTask,
        currentJob: Job,
        virtualThread: ActivityVirtualThread?,
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
        val runningActivity = RunningActivity(job = currentJob, context = context, virtualThread = virtualThread)
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
            } catch (e: InterruptedException) {
                // Virtual thread was interrupted - treat as activity cancellation
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
        // Activities run on dedicated virtual threads, so blocking calls are fine.
        // Thread interruption is handled directly via ActivityVirtualThread.interrupt().
        return withContext(context) {
            if (methodInfo.hasContextReceiver) {
                // Method has ActivityContext as extension receiver
                if (methodInfo.isSuspend) {
                    method.callSuspend(methodInfo.instance, context, *args)
                } else {
                    method.call(methodInfo.instance, context, *args)
                }
            } else {
                // Method does not use context receiver
                // Can still access context via coroutineContext[ActivityContext]
                if (methodInfo.isSuspend) {
                    method.callSuspend(methodInfo.instance, *args)
                } else {
                    method.call(methodInfo.instance, *args)
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

    /**
     * Awaits completion of all zombie eviction jobs.
     * Called during worker shutdown to ensure proper cleanup.
     */
    suspend fun awaitZombieEviction() {
        zombieManager.logShutdownWarning()
        zombieManager.awaitAllEvictions()
    }

    /**
     * Gets the current count of zombie threads.
     */
    fun getZombieCount(): Int = zombieManager.getZombieCount()
}
