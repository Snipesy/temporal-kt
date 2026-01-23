package com.surrealdev.temporal.client

import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.serialization.serialize
import io.temporal.api.common.v1.Payloads

/**
 * Sends a signal to the workflow with a single argument of type [T].
 *
 * @param signalName The name of the signal to send.
 * @param arg The argument to send with the signal.
 */
suspend inline fun <R, reified T> WorkflowHandle<R>.signal(
    signalName: String,
    arg: T,
) {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))
    this.signalWithPayloads(
        signalName,
        payloadsBuilder.build(),
    )
}

/**
 * Sends a signal to the workflow with no arguments.
 *
 * @param signalName The name of the signal to send.
 */
suspend fun <R> WorkflowHandle<R>.signal(signalName: String) {
    val emptyPayloads = Payloads.newBuilder().build()
    this.signalWithPayloads(
        signalName,
        emptyPayloads,
    )
}

/**
 * Queries the workflow and returns the result of type [T].
 *
 * @param queryType The type of the query to perform.
 * @return The result of the query.
 */
suspend inline fun <R, reified T> WorkflowHandle<R>.query(queryType: String): T {
    val emptyPayloads = Payloads.newBuilder().build()
    val outputPayloads = this.queryWithPayloads(queryType, emptyPayloads)
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload couunt
    require(outputPayloads.payloadsCount == 1) {
        "Expected exactly one payload in query result, but got ${outputPayloads.payloadsCount}"
    }

    return this.serializer.deserialize<T>(outputPayloads.getPayloads(0))
}

/**
 * Queries the workflow with a single argument of type [A] and returns the result of type [R].
 *
 * @param queryType The type of the query to perform.
 * @param arg The argument to send with the query.
 * @return The result of the query.
 */
suspend inline fun <R, reified A, reified T> WorkflowHandle<R>.query(
    queryType: String,
    arg: A,
): T {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))
    val outputPayloads = this.queryWithPayloads(queryType, payloadsBuilder.build())
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload couunt
    require(outputPayloads.payloadsCount == 1) {
        "Expected exactly one payload in query result, but got ${outputPayloads.payloadsCount}"
    }
    return this.serializer.deserialize<T>(outputPayloads.getPayloads(0))
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
suspend inline fun <R, reified A, reified T> WorkflowHandle<R>.update(
    updateName: String,
    arg: A,
): T {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg))
    val outputPayloads = this.updateWithPayloads(updateName, payloadsBuilder.build())
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.payloadsCount == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.payloadsCount}"
    }
    return this.serializer.deserialize<T>(outputPayloads.getPayloads(0))
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
suspend inline fun <R, reified A1, reified A2, reified T> WorkflowHandle<R>.update(
    updateName: String,
    arg1: A1,
    arg2: A2,
): T {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    val outputPayloads = this.updateWithPayloads(updateName, payloadsBuilder.build())
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.payloadsCount == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.payloadsCount}"
    }
    return this.serializer.deserialize<T>(outputPayloads.getPayloads(0))
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
suspend inline fun <R, reified A1, reified A2, reified A3, reified T> WorkflowHandle<R>.update(
    updateName: String,
    arg1: A1,
    arg2: A2,
    arg3: A3,
): T {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))
    val outputPayloads = this.updateWithPayloads(updateName, payloadsBuilder.build())
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.payloadsCount == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.payloadsCount}"
    }
    return this.serializer.deserialize<T>(outputPayloads.getPayloads(0))
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
suspend inline fun <R, reified A1, reified A2, reified A3, reified A4, reified T> WorkflowHandle<R>.update(
    updateName: String,
    arg1: A1,
    arg2: A2,
    arg3: A3,
    arg4: A4,
): T {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg4))
    val outputPayloads = this.updateWithPayloads(updateName, payloadsBuilder.build())
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload count
    require(outputPayloads.payloadsCount == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.payloadsCount}"
    }
    return this.serializer.deserialize<T>(outputPayloads.getPayloads(0))
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
suspend inline fun <R, reified T> WorkflowHandle<R>.update(updateName: String): T {
    val emptyPayloads = Payloads.newBuilder().build()
    val outputPayloads = this.updateWithPayloads(updateName, emptyPayloads)
    if (T::class == Unit::class) {
        return Unit as T
    }
    // assert only 1 payload couunt
    require(outputPayloads.payloadsCount == 1) {
        "Expected exactly one payload in update result, but got ${outputPayloads.payloadsCount}"
    }
    return this.serializer.deserialize<T>(outputPayloads.getPayloads(0))
}
