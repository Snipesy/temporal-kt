package com.surrealdev.temporal.client

import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize

// Note: Signal extensions are now in WorkflowHandleBaseExtensions.kt
// and work for both WorkflowHandle and ChildWorkflowHandle.

/**
 * Queries the workflow and returns the result of type [T].
 *
 * @param queryType The type of the query to perform.
 * @return The result of the query.
 */
suspend inline fun <reified T> WorkflowHandle.query(queryType: String): T {
    val outputPayloads = this.queryWithPayloads(queryType, TemporalPayloads.EMPTY)
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.size == 1) {
        "Expected exactly one payload in query result, but got ${outputPayloads.size}"
    }

    return this.serializer.deserialize<T>(outputPayloads[0])
}

/**
 * Queries the workflow with a single argument of type [A] and returns the result of type [R].
 *
 * @param queryType The type of the query to perform.
 * @param arg The argument to send with the query.
 * @return The result of the query.
 */
suspend inline fun <reified A, reified T> WorkflowHandle.query(
    queryType: String,
    arg: A,
): T {
    val payload = this.serializer.serialize(arg)
    val outputPayloads = this.queryWithPayloads(queryType, TemporalPayloads.of(listOf(payload)))
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.size == 1) {
        "Expected exactly one payload in query result, but got ${outputPayloads.size}"
    }
    return this.serializer.deserialize<T>(outputPayloads[0])
}

/**
 * Sends an update to the workflow with a single argument and waits for the result.
 *
 * Updates are like signals but return a value. They also support
 * validation before the update is accepted.
 *
 * @param updateName The name of the update to send.
 * @param arg The argument to pass with the update.
 * @return The update result.
 */
suspend inline fun <reified A, reified T> WorkflowHandle.update(
    updateName: String,
    arg: A,
): T {
    val payload = this.serializer.serialize(arg)
    val outputPayloads = this.updateWithPayloads(updateName, TemporalPayloads.of(listOf(payload)))
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.size == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.size}"
    }
    return this.serializer.deserialize<T>(outputPayloads[0])
}

/**
 * Sends an update to the workflow with two arguments and waits for the result.
 *
 * Updates are like signals but return a value. They also support
 * validation before the update is accepted.
 *
 * @param updateName The name of the update to send.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @return The update result.
 */
suspend inline fun <reified A1, reified A2, reified T> WorkflowHandle.update(
    updateName: String,
    arg1: A1,
    arg2: A2,
): T {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val outputPayloads = this.updateWithPayloads(updateName, TemporalPayloads.of(listOf(payload1, payload2)))
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.size == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.size}"
    }
    return this.serializer.deserialize<T>(outputPayloads[0])
}

/**
 * Sends an update to the workflow with three arguments and waits for the result.
 *
 * Updates are like signals but return a value. They also support
 * validation before the update is accepted.
 *
 * @param updateName The name of the update to send.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param arg3 The third argument.
 * @return The update result.
 */
suspend inline fun <reified A1, reified A2, reified A3, reified T> WorkflowHandle.update(
    updateName: String,
    arg1: A1,
    arg2: A2,
    arg3: A3,
): T {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payload3 = this.serializer.serialize(arg3)
    val outputPayloads = this.updateWithPayloads(updateName, TemporalPayloads.of(listOf(payload1, payload2, payload3)))
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.size == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.size}"
    }
    return this.serializer.deserialize<T>(outputPayloads[0])
}

/**
 * Sends an update to the workflow with four arguments and waits for the result.
 *
 * Updates are like signals but return a value. They also support
 * validation before the update is accepted.
 *
 * @param updateName The name of the update to send.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param arg3 The third argument.
 * @param arg4 The fourth argument.
 * @return The update result.
 */
suspend inline fun <reified A1, reified A2, reified A3, reified A4, reified T> WorkflowHandle.update(
    updateName: String,
    arg1: A1,
    arg2: A2,
    arg3: A3,
    arg4: A4,
): T {
    val payload1 = this.serializer.serialize(arg1)
    val payload2 = this.serializer.serialize(arg2)
    val payload3 = this.serializer.serialize(arg3)
    val payload4 = this.serializer.serialize(arg4)
    val outputPayloads =
        this.updateWithPayloads(
            updateName,
            TemporalPayloads.of(listOf(payload1, payload2, payload3, payload4)),
        )
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.size == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.size}"
    }
    return this.serializer.deserialize<T>(outputPayloads[0])
}

/**
 * Sends an update to the workflow and waits for the result.
 *
 * Updates are like signals but return a value. They also support
 * validation before the update is accepted.
 **
 * @param updateName The name of the update to send.
 * @return The update result.
 */
suspend inline fun <reified T> WorkflowHandle.update(updateName: String): T {
    val outputPayloads = this.updateWithPayloads(updateName, TemporalPayloads.EMPTY)
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.size == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.size}"
    }
    return this.serializer.deserialize<T>(outputPayloads[0])
}
