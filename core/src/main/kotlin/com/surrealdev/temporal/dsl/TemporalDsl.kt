// Declarative-DSL stubs for the Temporal compiler plugin.
//
// These are receiver-bound so they can ONLY be called from the right scope, eliminating the
// historical name collision with the runtime overloads `TaskQueueBuilder.workflow<T>()` (reified,
// no-arg list) and `WorkflowContext.startActivity<T>()` — Kotlin overload resolution picks the
// right one by argument shape:
//
// - `embeddedTemporal { taskQueue("q") { workflow("Foo") { ... } } }` — `workflow(name, body)` is
//   on TaskQueueBuilder, so it is callable inside any `taskQueue { ... }` lambda.
// - `@WorkflowRun suspend fun WorkflowContext.run() { activity("Bar") { ... } }` —
//   `activity(name, body)` is on WorkflowContext, so it is callable inside any function with a
//   WorkflowContext receiver (which is the contract for `@WorkflowRun` methods).
//
// **The compiler plugin transforms these calls at IR-gen time.** The bodies you write here are
// lifted into real Temporal-managed implementations (workflow classes / activity functions); the
// call sites are rewritten to standard registration / dispatch APIs. Without the plugin enabled
// these stubs throw at runtime — the compiler plugin must be active for these to work.

package com.surrealdev.temporal.dsl

import com.surrealdev.temporal.application.TaskQueueBuilder
import com.surrealdev.temporal.workflow.WorkflowContext

private const val PLUGIN_REQUIRED =
    "Temporal DSL call reached at runtime — the compiler plugin must be enabled and " +
        "the call must be in a scope the plugin recognises (taskQueue { workflow(...) } / " +
        "WorkflowContext.activity(...))."

/**
 * Declares a workflow inside a `taskQueue { ... }` block. The lambda's last expression becomes
 * the workflow's return value, and the lambda may call `activity(...)` because it has a
 * `WorkflowContext` receiver in scope.
 *
 * **Compiler-plugin transform (Stage 8.4, deferred):** the plugin synthesises an
 * `@Workflow`-annotated class at IR time, transplants the lambda body into its `@WorkflowRun`
 * method, and rewrites this call site to register the synthesised class on the surrounding
 * [TaskQueueBuilder] via `registerWorkflowClass`.
 */
@Suppress("unused")
fun <Return> TaskQueueBuilder.workflow(
    name: String,
    body: suspend WorkflowContext.() -> Return,
): Return {
    @Suppress("UNUSED_PARAMETER", "UNUSED_EXPRESSION") name
    @Suppress("UNUSED_EXPRESSION") body
    throw UnsupportedOperationException(PLUGIN_REQUIRED)
}

/**
 * Declares an activity callable from inside a `@WorkflowRun` method body or a `workflow(...) { }`
 * lambda body (both of which expose `WorkflowContext` as receiver).
 *
 * **Compiler-plugin transform (Stage 8.6):** the plugin lifts the lambda body to a top-level
 * `@Activity("name")` function, auto-registers it via the workflow class's companion hook, and
 * rewrites this call site to invoke through Temporal's standard activity dispatch
 * (`WorkflowContext.startActivity<R>(...).result()`). The activity therefore runs on the activity
 * worker (with retries, separate slot supplier, etc.) — NOT inline in the workflow.
 */
@Suppress("unused")
suspend fun <Return> WorkflowContext.activity(
    name: String,
    body: () -> Return,
): Return {
    @Suppress("UNUSED_PARAMETER", "UNUSED_EXPRESSION") name
    @Suppress("UNUSED_EXPRESSION") body
    throw UnsupportedOperationException(PLUGIN_REQUIRED)
}
