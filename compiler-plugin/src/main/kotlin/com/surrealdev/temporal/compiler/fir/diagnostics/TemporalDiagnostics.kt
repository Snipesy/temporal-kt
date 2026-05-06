@file:Suppress("StructuralWrap")

package com.surrealdev.temporal.compiler.fir.diagnostics

import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtElement

/**
 * Diagnostics emitted by the Temporal compiler plugin.
 *
 * Use [KtElement] (not `PsiElement`) for source elements — using `PsiElement` causes IDE/test
 * problems per the kotlin-compiler-internal-test-framework conventions.
 */
object TemporalDiagnostics : KtDiagnosticsContainer() {
    /**
     * A call inside `@Workflow` code uses an API forbidden by determinism rules.
     * Argument: the rule name (e.g. `"GlobalScope usage"`).
     */
    val TEMPORAL_NONDETERMINISTIC_CALL by error1<KtElement, String>()

    /**
     * `withTaskQueue("name")` was called with a queue name not present in the plugin's
     * configured `KNOWN_TASK_QUEUES`. Argument: the offending queue name.
     */
    val TEMPORAL_UNKNOWN_TASK_QUEUE by error1<KtElement, String>()

    /**
     * A `workflow("Name") { ... }` lambda captures a value from its enclosing scope. Workflows
     * must be deterministic and re-entrant; the declaring scope runs once at registration but the
     * workflow body is re-executed from history on replay, so capturing module-init-time state
     * is semantically wrong.
     */
    val TEMPORAL_WORKFLOW_LAMBDA_CAPTURES_NOT_SUPPORTED by error0<KtElement>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = TemporalDiagnosticRendererFactory
}
