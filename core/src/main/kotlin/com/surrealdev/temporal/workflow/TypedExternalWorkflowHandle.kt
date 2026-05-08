package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.client.serializeArgs

/**
 * Wraps an [ExternalWorkflowHandle] for typed signal dispatch to another running workflow
 * execution. Used as the supertype of the per-`@Workflow`-class `ExternalHandle` nested class
 * synthesised by the compiler plugin (Stage 17.6).
 *
 * Call sites use the central reified API in `:compiler-plugin-runtime`:
 *
 * ```
 * @Workflow class Other {
 *     @WorkflowRun suspend fun run(): Unit { … }
 *     @Signal("ping") fun ping(payload: String) { … }
 * }
 *
 * @Workflow class Caller {
 *     @WorkflowRun suspend fun run() {
 *         val other: Other = externalHandle<Other>("other-workflow-id")
 *         other.ping("hello")            // typed @Signal — dispatches over the wire
 *     }
 * }
 * ```
 *
 * **Signal-only**: external workflows can't be queried/updated/awaited from another workflow
 * (cross-workflow synchronous RPC isn't a Temporal primitive). The plugin generates only
 * `@Signal` wrappers — `@Query`/`@Update`/`@WorkflowRun` are skipped, mirroring the existing
 * ChildHandle scope decision.
 */
interface TypedExternalWorkflowHandle {
    @InternalTemporalApi val handle: ExternalWorkflowHandle

    val workflowId: String get() = handle.workflowId
    val runId: String? get() = handle.runId
    val namespace: String get() = handle.namespace

    /**
     * Cancel the external workflow execution. Suspend (matches [ExternalWorkflowHandle.cancel],
     * which differs from [ChildWorkflowHandle.cancel] — child cancel is non-suspend).
     */
    suspend fun cancel(reason: String = "")
}

@PublishedApi
@OptIn(InternalTemporalApi::class)
internal suspend fun typedExternalCancelImpl(
    handle: ExternalWorkflowHandle,
    reason: String,
) = handle.cancel(reason)

/**
 * Compiler-plugin helper: indirection for `WorkflowContext.getExternalWorkflowHandle(...)`.
 * Lets the IR rewriter call a top-level function rather than build an extension-receiver
 * call expression for a `WorkflowContext` member.
 */
@PublishedApi
@OptIn(InternalTemporalApi::class)
internal fun externalHandleGet(
    workflowContext: WorkflowContext,
    workflowId: String,
    runId: String?,
): ExternalWorkflowHandle = workflowContext.getExternalWorkflowHandle(workflowId, runId)

/**
 * Compiler-plugin helper: typed `@Signal` wrapper for `<UserClass>.ExternalHandle`. Mirrors
 * `signalChildTyped` but works against [ExternalWorkflowHandle].
 *
 * Convention: `argTypesAndValues` is laid out as `[KType, Any?, KType, Any?, ...]` in even/odd
 * positions. Empty vararg → no payloads.
 */
@PublishedApi
@OptIn(InternalTemporalApi::class)
internal suspend fun signalExternalTyped(
    handle: ExternalWorkflowHandle,
    signalName: String,
    argTypesAndValues: Array<out Any?>,
) {
    val payloads = serializeArgs(handle.serializer, argTypesAndValues)
    handle.signalWithPayloads(signalName, payloads)
}
