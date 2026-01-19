package com.surrealdev.temporal.activity.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.activity.ActivityCancelledException
import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.SerializationException
import com.surrealdev.temporal.serialization.typeInfoOf
import coresdk.CoreInterface
import coresdk.activityTaskCompletion
import coresdk.activity_result.activityExecutionResult
import coresdk.activity_result.failure
import coresdk.activity_result.success
import coresdk.activity_task.ActivityTaskOuterClass
import io.temporal.api.common.v1.Payload
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.reflect.full.callSuspend

/**
 * Dispatches activity tasks to registered activity implementations.
 *
 * This dispatcher handles:
 * - Looking up activity methods by type
 * - Deserializing input arguments
 * - Invoking the activity method with proper context
 * - Serializing the result or failure
 * - Enforcing concurrency limits via semaphore
 */
internal class ActivityDispatcher(
    private val registry: ActivityRegistry,
    private val serializer: PayloadSerializer,
    private val taskQueue: String,
    maxConcurrent: Int,
    private val heartbeatFn: suspend (ByteArray, ByteArray?) -> Unit = { _, _ -> },
) {
    private val semaphore = Semaphore(maxConcurrent)

    /**
     * Dispatches an activity task to the appropriate activity implementation.
     *
     * @param task The activity task to dispatch
     * @return The activity task completion to send back to the server
     */
    suspend fun dispatch(task: ActivityTaskOuterClass.ActivityTask): CoreInterface.ActivityTaskCompletion =
        semaphore.withPermit {
            dispatchInternal(task)
        }

    private suspend fun dispatchInternal(
        task: ActivityTaskOuterClass.ActivityTask,
    ): CoreInterface.ActivityTaskCompletion {
        val taskToken = task.taskToken

        // Handle cancel variant
        if (task.hasCancel()) {
            // For cancellation, we return a cancelled completion
            return buildCancelledCompletion(taskToken)
        }

        // Handle start variant
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
        val context =
            ActivityContextImpl(
                start = start,
                taskToken = taskToken,
                taskQueue = taskQueue,
                serializer = serializer,
                heartbeatFn = heartbeatFn,
            )

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
        } catch (e: Exception) {
            buildFailureCompletion(taskToken, e)
        }
    }

    private fun deserializeArguments(
        payloads: List<Payload>,
        parameterTypes: List<kotlin.reflect.KType>,
    ): Array<Any?> {
        if (payloads.size != parameterTypes.size) {
            throw SerializationException(
                "Argument count mismatch: expected ${parameterTypes.size}, got ${payloads.size}",
            )
        }

        return payloads
            .zip(parameterTypes)
            .map { (payload, type) ->
                serializer.deserialize(typeInfoOf(type), payload)
            }.toTypedArray()
    }

    private suspend fun invokeMethod(
        methodInfo: ActivityMethodInfo,
        context: ActivityContext,
        args: Array<Any?>,
    ): Any? {
        val method = methodInfo.method

        return if (methodInfo.hasContextReceiver) {
            // Method has ActivityContext as extension receiver
            if (methodInfo.isSuspend) {
                method.callSuspend(methodInfo.instance, context, *args)
            } else {
                method.call(methodInfo.instance, context, *args)
            }
        } else {
            // Method does not use context receiver
            if (methodInfo.isSuspend) {
                method.callSuspend(methodInfo.instance, *args)
            } else {
                method.call(methodInfo.instance, *args)
            }
        }
    }

    private fun buildSuccessCompletion(
        taskToken: ByteString,
        result: Any?,
        returnType: kotlin.reflect.KType,
    ): CoreInterface.ActivityTaskCompletion {
        val resultPayload =
            if (result == Unit || returnType.classifier == Unit::class) {
                // For Unit return type, we don't serialize the result
                Payload.getDefaultInstance()
            } else {
                serializer.serialize(typeInfoOf(returnType), result)
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
        val failure =
            Failure
                .newBuilder()
                .setMessage(exception.message ?: exception::class.simpleName ?: "Unknown error")
                .setStackTrace(exception.stackTraceToString())
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
}
