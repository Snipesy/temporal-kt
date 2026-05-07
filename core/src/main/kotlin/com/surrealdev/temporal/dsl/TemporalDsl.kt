// Declarative-DSL stubs for the Temporal compiler plugin.
//
// Inline DSL support is intentionally limited to activities declared from workflow code:
//
// - `@WorkflowRun suspend fun WorkflowContext.run() { activity("Bar") { ... } }`
//
// **The compiler plugin transforms this call at IR-gen time.** The body is lifted into a real
// Temporal-managed activity function and the call site is rewritten to standard activity dispatch.
// Without the plugin enabled this stub throws at runtime — the compiler plugin must be active.

package com.surrealdev.temporal.dsl

import com.surrealdev.temporal.workflow.WorkflowContext

private const val PLUGIN_REQUIRED =
    "Temporal DSL call reached at runtime — the compiler plugin must be enabled and " +
        "the call must be in a WorkflowContext.activity(...) scope the plugin recognises."

/**
 * Declares an activity callable from inside a `@WorkflowRun` method body, where
 * `WorkflowContext` is the receiver.
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
    @Suppress("UNUSED_PARAMETER", "UNUSED_EXPRESSION")
    name
    @Suppress("UNUSED_EXPRESSION")
    body
    throw UnsupportedOperationException(PLUGIN_REQUIRED)
}
