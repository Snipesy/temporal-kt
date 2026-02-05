package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.client.WorkflowHandle
import com.surrealdev.temporal.serialization.deserialize
import io.temporal.api.common.v1.Payload
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
 * @throws ActivityException if the activity failed
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
 * @throws ChildWorkflowException if the child workflow failed
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
 * @throws WorkflowException if the workflow failed
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
    payload: Payload?,
    serializer: com.surrealdev.temporal.serialization.PayloadSerializer,
): R {
    val returnType = typeOf<R>()

    return if (payload == null || payload == Payload.getDefaultInstance() || payload.data.isEmpty) {
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
