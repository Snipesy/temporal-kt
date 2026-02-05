package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.serialization.serialize
import io.temporal.api.common.v1.Payloads

/*
 * Extension functions for [WorkflowHandleBase] that provide type-safe signal operations.
 *
 * These extensions work for both client-side [com.surrealdev.temporal.client.WorkflowHandle]
 * and workflow-side [ChildWorkflowHandle].
 */

/**
 * Sends a signal to the workflow with a single argument of type [T].
 *
 * @param signalName The name of the signal to send.
 * @param arg The argument to send with the signal.
 */
suspend inline fun <reified T> WorkflowHandleBase.signal(
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
suspend fun WorkflowHandleBase.signal(signalName: String) {
    val emptyPayloads = Payloads.newBuilder().build()
    this.signalWithPayloads(
        signalName,
        emptyPayloads,
    )
}

/**
 * Sends a signal to the workflow with two arguments.
 *
 * @param signalName The name of the signal to send.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 */
suspend inline fun <reified T1, reified T2> WorkflowHandleBase.signal(
    signalName: String,
    arg1: T1,
    arg2: T2,
) {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    this.signalWithPayloads(
        signalName,
        payloadsBuilder.build(),
    )
}

/**
 * Sends a signal to the workflow with three arguments.
 *
 * @param signalName The name of the signal to send.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param arg3 The third argument.
 */
suspend inline fun <reified T1, reified T2, reified T3> WorkflowHandleBase.signal(
    signalName: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
) {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))
    this.signalWithPayloads(
        signalName,
        payloadsBuilder.build(),
    )
}

/**
 * Sends a signal to the workflow with four arguments.
 *
 * @param signalName The name of the signal to send.
 * @param arg1 The first argument.
 * @param arg2 The second argument.
 * @param arg3 The third argument.
 * @param arg4 The fourth argument.
 */
suspend inline fun <reified T1, reified T2, reified T3, reified T4> WorkflowHandleBase.signal(
    signalName: String,
    arg1: T1,
    arg2: T2,
    arg3: T3,
    arg4: T4,
) {
    val payloadsBuilder = Payloads.newBuilder()
    payloadsBuilder.addPayloads(this.serializer.serialize(arg1))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg2))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg3))
    payloadsBuilder.addPayloads(this.serializer.serialize(arg4))
    this.signalWithPayloads(
        signalName,
        payloadsBuilder.build(),
    )
}
