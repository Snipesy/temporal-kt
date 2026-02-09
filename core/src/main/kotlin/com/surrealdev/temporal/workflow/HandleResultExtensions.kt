package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.client.WorkflowHandle
import kotlin.reflect.typeOf
import kotlin.time.Duration

/**
 * Awaits the activity result and deserializes it to type R.
 *
 * This is a convenience extension that calls [ActivityHandle.resultPayload] and deserializes
 * the payload to the specified type.
 *
 * @param R The expected result type
 * @return The deserialized result
 * @throws com.surrealdev.temporal.common.exceptions.WorkflowActivityException if the activity failed
 */
suspend inline fun <reified R> ActivityHandle.result(): R {
    val payload = resultPayload()
    return deserializePayload<R>(payload, this.serializer)
}

/**
 * Awaits the child workflow result and deserializes it to type R.
 *
 * @param R The expected result type
 * @return The deserialized result
 * @throws com.surrealdev.temporal.common.exceptions.ChildWorkflowFailureException if the child workflow failed
 * @throws com.surrealdev.temporal.common.exceptions.ChildWorkflowCancelledException if the child workflow was cancelled
 * @throws com.surrealdev.temporal.common.exceptions.ChildWorkflowStartFailureException if the child workflow failed to start
 */
suspend inline fun <reified R> ChildWorkflowHandle.result(): R {
    val payload = resultPayload()
    return deserializePayload<R>(payload, this.serializer)
}

/**
 * Awaits the workflow result and deserializes it to type R.
 *
 * @param R The expected result type
 * @param timeout Maximum time to wait for the workflow to complete
 * @return The deserialized result
 * @throws com.surrealdev.temporal.common.exceptions.ClientWorkflowException if the workflow failed
 */
suspend inline fun <reified R> WorkflowHandle.result(timeout: Duration = Duration.INFINITE): R {
    val payload = resultPayload(timeout)
    return deserializePayload<R>(payload, serializer)
}

/**
 * Helper function to deserialize a payload.
 *
 * Handles null/empty payloads by returning Unit or null based on the expected type.
 */
@PublishedApi
internal inline fun <reified R> deserializePayload(
    payload: com.surrealdev.temporal.common.TemporalPayload?,
    serializer: com.surrealdev.temporal.serialization.PayloadSerializer,
): R {
    val returnType = typeOf<R>()

    return if (payload == null || payload.data.isEmpty()) {
        // Empty payload
        if (returnType.classifier == Unit::class) {
            Unit as R
        } else {
            null as R
        }
    } else {
        serializer.deserialize(returnType, payload) as R
    }
}
