// Declarative-DSL stubs for the Temporal compiler plugin.
//
// Two flavors of inline activity, both declared from workflow code:
//
// - `@WorkflowRun suspend fun WorkflowContext.run() {`
//      `inlineActivity("Bar") { /* ActivityContext.() -> R */ }`         — dispatched on the
//                                                                          activity worker
//      `inlineLocalActivity("Baz") { /* ActivityContext.() -> R */ }`    — dispatched in-process
//                                                                          on the workflow worker
//   `}`
//
// **The compiler plugin transforms these calls at IR-gen time.** The lambda body is lifted into
// a real Temporal-managed `@Activity` function (with `ActivityContext` extension receiver) and
// the call site is rewritten to standard `startActivity*` / `startLocalActivity*` dispatch.
// Captures from the workflow body become activity arguments. Without the plugin enabled the
// stubs throw at runtime — the compiler plugin must be active.

package com.surrealdev.temporal.dsl

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.workflow.WorkflowContext

private const val PLUGIN_REQUIRED =
    "Temporal DSL call reached at runtime — the compiler plugin must be enabled and " +
        "the call must be inside a WorkflowContext-receiver scope the plugin recognises."

/**
 * Declares an activity callable from inside a `@WorkflowRun` method body. The lambda runs as
 * a real Temporal activity (separate dispatch, retry policy, slot accounting). Inside the
 * lambda, `this` is [ActivityContext] — call `heartbeat(...)`, check cancellation, etc.
 *
 * The plugin lifts the lambda to a top-level `suspend fun ActivityContext.__<class>_<name>(...): R`
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
 * short, deterministic side effects. Inside the lambda, `this` is [ActivityContext].
 *
 * The plugin lifts the lambda to a top-level `suspend fun ActivityContext.__<class>_<name>(...): R`
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
