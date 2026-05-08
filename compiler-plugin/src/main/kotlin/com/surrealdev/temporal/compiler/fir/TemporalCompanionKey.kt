package com.surrealdev.temporal.compiler.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey

/**
 * Origin key for every declaration the [TemporalFirCompanionGenerator] synthesises:
 * - the companion object (when none was user-written)
 * - its `start(...)` and `execute(...)` member functions
 * - its private no-arg constructor (only when the companion itself is plugin-synthesised)
 *
 * The IR body filler matches on this key to know which declarations need its bodies populated.
 */
object TemporalCompanionKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalCompanionKey"
}

/** Origin key for typed `@Signal` wrapper methods on `<UserClass>.Handle<R>`. */
object TemporalSignalKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalSignalKey"
}

/** Origin key for typed `@Query` wrapper methods on `<UserClass>.Handle<R>`. */
object TemporalQueryKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalQueryKey"
}

/** Origin key for typed `@Update` wrapper methods on `<UserClass>.Handle<R>`. */
object TemporalUpdateKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalUpdateKey"
}

/**
 * Origin key for the child-workflow surface synthesised on each `@Workflow` class:
 * - the companion's `startChild(arg)` extension method (with `WorkflowContext` extension receiver)
 * - the nested `<UserClass>.ChildHandle<R>` class itself
 * - that class's internal constructor `(handle, resultType)`.
 *
 * Distinct from [TemporalCompanionKey] so the IR body filler can dispatch to
 * `startChildWorkflowGetHandle` runtime helper instead of `startWorkflowGetHandle`.
 */
object TemporalChildCompanionKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalChildCompanionKey"
}

/**
 * Origin key for typed `@Signal` wrapper methods on `<UserClass>.ChildHandle<R>`. Distinct from
 * [TemporalSignalKey] so the IR filler routes to `signalChildTyped` (which calls
 * [com.surrealdev.temporal.workflow.ChildWorkflowHandle]'s signal API) rather than
 * `signalTyped` (which calls [com.surrealdev.temporal.client.WorkflowHandle]'s).
 *
 * No corresponding `TemporalChildQueryKey` / `TemporalChildUpdateKey` exist: per
 * `ChildWorkflowHandle`'s docs, query/update aren't supported on child workflows from within
 * workflow code (synchronous RPC breaks determinism). The FIR generator simply doesn't emit
 * those wrappers on `ChildHandle`.
 */
object TemporalChildSignalKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalChildSignalKey"
}

/**
 * Origin key for the external-workflow surface synthesised on each `@Workflow` class:
 * - the nested `<UserClass>.ExternalHandle` class itself
 * - that class's internal constructor `(handle: ExternalWorkflowHandle)`
 * - the synthesised `cancel(reason)` override (suspend) and `handle` property override.
 *
 * Distinct from [TemporalChildCompanionKey] so the IR body filler dispatches to
 * `externalHandleGet` / `typedExternalCancelImpl` runtime helpers rather than the child variants.
 */
object TemporalExternalCompanionKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalExternalCompanionKey"
}

/**
 * Origin key for typed `@Signal` wrapper methods on `<UserClass>.ExternalHandle`. Distinct from
 * [TemporalChildSignalKey] so the IR filler routes to `signalExternalTyped` instead of
 * `signalChildTyped`.
 *
 * No `Query`/`Update`/`WorkflowRun` wrappers on ExternalHandle: cross-workflow synchronous RPC
 * isn't a Temporal primitive — only signals make sense across workflow boundaries.
 */
object TemporalExternalSignalKey : GeneratedDeclarationKey() {
    override fun toString(): String = "TemporalExternalSignalKey"
}
