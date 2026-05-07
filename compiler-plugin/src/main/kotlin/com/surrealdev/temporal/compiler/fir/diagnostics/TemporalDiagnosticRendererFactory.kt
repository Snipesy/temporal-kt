package com.surrealdev.temporal.compiler.fir.diagnostics

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory

object TemporalDiagnosticRendererFactory : BaseDiagnosticRendererFactory() {
    // ktlint disable property-naming  -- `MAP` is the abstract uppercase property defined by BaseDiagnosticRendererFactory.
    @Suppress("ktlint:standard:property-naming")
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Temporal") { map ->
        map.put(
            factory = TemporalDiagnostics.TEMPORAL_NONDETERMINISTIC_CALL,
            message =
                "[TEMPORAL] Non-deterministic call in workflow code: {0}. " +
                    "See determinism-rules.json for details.",
            rendererA = null,
        )
        map.put(
            factory = TemporalDiagnostics.TEMPORAL_UNKNOWN_TASK_QUEUE,
            message = "[TEMPORAL] Unknown task queue: ''{0}''. Not declared in this module.",
            rendererA = null,
        )
    }
}
