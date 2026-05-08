package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.client.deserializeWithKType
import com.surrealdev.temporal.client.serializeArgs
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import kotlin.reflect.KType

/**
 * A [ChildWorkflowHandle] wrapper that knows the child workflow's typed return type at
 * construction time. Used as the supertype of the per-`@Workflow`-class `ChildHandle` nested
 * class synthesised by the compiler plugin.
 *
 * Call sites use the central reified API in `:compiler-plugin-runtime`:
 *
 * ```
 * @Workflow class Foo { @WorkflowRun suspend fun WorkflowContext.run(arg: String): Int = … }
 *
 * @Workflow class Parent {
 *     @WorkflowRun suspend fun WorkflowContext.run(): Int {
 *         val child: Foo.ChildHandle<Int> = startChildWorkflow<Foo>("hi")
 *         child.cancel("done")          // typed @Signal wrapper
 *         return child.result()         // no <Int> ceremony
 *     }
 * }
 * ```
 *
 * **Why no query/update**: per [ChildWorkflowHandle]'s docs, query and update aren't supported
 * on child workflows from within workflow code (synchronous RPC breaks workflow determinism).
 * The compiler plugin honors that by skipping `@Query`/`@Update` wrappers when generating
 * `<UserClass>.ChildHandle`.
 */
interface TypedChildWorkflowHandle<out R> {
    @InternalTemporalApi val handle: ChildWorkflowHandle

    @InternalTemporalApi val resultType: KType

    val workflowId: String get() = handle.workflowId
    val firstExecutionRunId: String? get() = handle.firstExecutionRunId

    suspend fun awaitStart(): String

    suspend fun result(): R

    fun cancel(reason: String = "Cancelled by parent workflow")
}

@PublishedApi
@OptIn(InternalTemporalApi::class)
internal suspend fun typedChildAwaitStartImpl(handle: ChildWorkflowHandle): String = handle.awaitStart()

@PublishedApi
@OptIn(InternalTemporalApi::class)
@Suppress("UNCHECKED_CAST")
internal suspend fun <R> typedChildResultImpl(
    handle: ChildWorkflowHandle,
    resultType: KType,
): R {
    val payload: TemporalPayload? = handle.resultPayload()
    return deserializeWithKType(payload, resultType, handle.serializer) as R
}

@PublishedApi
@OptIn(InternalTemporalApi::class)
internal fun typedChildCancelImpl(
    handle: ChildWorkflowHandle,
    reason: String,
) = handle.cancel(reason)

/**
 * Compiler-plugin helper: starts a child workflow and returns the raw [ChildWorkflowHandle].
 * The plugin wraps the return value in a per-workflow `<UserClass>.ChildHandle` (which extends
 * [TypedChildWorkflowHandle]) at the call site.
 *
 * Mirrors `startWorkflowGetHandle` in `TypedWorkflowHandle.kt` but goes through the
 * [WorkflowContext.startChildWorkflowWithPayloads] entry point and produces a child handle.
 *
 * `null` [argType] means the user's `@WorkflowRun` method takes no value parameters; the
 * child workflow is started with empty payloads.
 */
@PublishedApi
@OptIn(InternalTemporalApi::class)
internal suspend fun startChildWorkflowGetHandle(
    workflowContext: WorkflowContext,
    workflowType: String,
    arg: Any?,
    argType: KType?,
    options: ChildWorkflowOptions,
): ChildWorkflowHandle {
    val payloads =
        if (argType == null) {
            TemporalPayloads.EMPTY
        } else {
            val payload = workflowContext.serializer.serialize(argType, arg)
            TemporalPayloads.of(listOf(payload))
        }
    return workflowContext.startChildWorkflowWithPayloads(
        workflowType = workflowType,
        args = payloads,
        options = options,
    )
}

/**
 * Compiler-plugin helper: typed `@Signal` wrapper for `<UserClass>.ChildHandle`. Mirrors
 * `signalTyped` in `TypedWorkflowHandle.kt` but works against [ChildWorkflowHandle] (which
 * exposes `signalWithPayloads` via its [WorkflowHandleBase] supertype but, by design, no
 * `queryWithPayloads` / `updateWithPayloads`).
 *
 * Convention: `argTypesAndValues` is laid out as `[KType, Any?, KType, Any?, ...]` in even/odd
 * positions. Empty vararg → no payloads.
 */
@PublishedApi
@OptIn(InternalTemporalApi::class)
internal suspend fun signalChildTyped(
    handle: ChildWorkflowHandle,
    signalName: String,
    argTypesAndValues: Array<out Any?>,
) {
    val payloads = serializeArgs(handle.serializer, argTypesAndValues)
    handle.signalWithPayloads(signalName, payloads)
}
