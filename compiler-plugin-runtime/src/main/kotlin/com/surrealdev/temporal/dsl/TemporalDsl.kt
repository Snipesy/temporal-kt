// Declarative-DSL stubs for the Temporal compiler plugin.
//
// Two flavors of inline activity, both declared from workflow code:
//
// - `@WorkflowRun suspend fun run() {`
//      `workflow().inlineActivity("Bar") { /* this: ActivityContext */ info.activityId }`
//      `workflow().inlineLocalActivity("Baz") { /* this: ActivityContext */ heartbeat(x) }`
//   `}`
//
// The lambda is declared with an `ActivityContext` extension receiver so users can refer to
// `info`, `heartbeat(...)`, etc. directly via `this`. The compiler plugin lifts the lambda body
// to a top-level `@Activity` function with `ActivityContext` as a regular leading parameter (the
// SDK rejects extension-receiver activity methods). Captures from the workflow body become
// additional activity arguments. The call site is rewritten to standard `startActivity*` /
// `startLocalActivity*` dispatch. Without the plugin enabled the stubs throw at runtime — the
// compiler plugin must be active.

package com.surrealdev.temporal.dsl

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.workflow.WorkflowContext

private const val PLUGIN_REQUIRED =
    "Temporal DSL call reached at runtime — the compiler plugin must be enabled and " +
        "the call must be inside a WorkflowContext-receiver scope the plugin recognises."

/**
 * Declares an activity callable from inside a `@WorkflowRun` method body. The lambda runs as
 * a real Temporal activity (separate dispatch, retry policy, slot accounting). Inside the
 * lambda, `this` is the [ActivityContext] — call `info.activityId`, `heartbeat(...)`, check
 * cancellation, etc.
 *
 * The plugin lifts the lambda to a top-level `suspend fun __<class>_<name>(ctx: ActivityContext, ...): R`
 * annotated `@Activity("name")`, auto-registers it via the workflow class's companion hook,
 * and rewrites this call site to invoke through `WorkflowContext.startActivityWithPayloads`.
 */
@Suppress("unused")
suspend fun <Return> WorkflowContext.inlineActivity(
    name: String,
    body: suspend ActivityContext.() -> Return,
): Return {
    @Suppress("UNUSED_PARAMETER", "UNUSED_EXPRESSION")
    name
    @Suppress("UNUSED_EXPRESSION")
    body
    throw UnsupportedOperationException(PLUGIN_REQUIRED)
}

/**
 * Declares a **local** activity callable from inside a `@WorkflowRun` method body. The lambda
 * runs in-process on the workflow worker (no separate task queue dispatch) — use this for
 * short, deterministic side effects. Inside the lambda, `this` is the [ActivityContext].
 *
 * The plugin lifts the lambda to a top-level `suspend fun __<class>_<name>(ctx: ActivityContext, ...): R`
 * annotated `@Activity("name")`, auto-registers it via the workflow class's companion hook,
 * and rewrites this call site to invoke through `WorkflowContext.startLocalActivityWithPayloads`.
 */
@Suppress("unused")
suspend fun <Return> WorkflowContext.inlineLocalActivity(
    name: String,
    body: suspend ActivityContext.() -> Return,
): Return {
    @Suppress("UNUSED_PARAMETER", "UNUSED_EXPRESSION")
    name
    @Suppress("UNUSED_EXPRESSION")
    body
    throw UnsupportedOperationException(PLUGIN_REQUIRED)
}
