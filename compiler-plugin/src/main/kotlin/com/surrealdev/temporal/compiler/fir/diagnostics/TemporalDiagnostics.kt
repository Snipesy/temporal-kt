@file:Suppress("StructuralWrap")

package com.surrealdev.temporal.compiler.fir.diagnostics

import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
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

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = TemporalDiagnosticRendererFactory
}
