package com.surrealdev.temporal.compiler.fir.checkers

import com.surrealdev.temporal.compiler.fir.diagnostics.TemporalDiagnostics
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression

/**
 * Reports [TemporalDiagnostics.TEMPORAL_UNKNOWN_TASK_QUEUE] when `withTaskQueue("name")` is called
 * with a name not in the configured [knownQueues] set. When [knownQueues] is empty (the default,
 * outside of test wiring or explicit user configuration), the check is disabled — no false
 * positives.
 *
 * Limitation: matching is by callee simple name (`"withTaskQueue"`), not by resolved FQN.
 * This will catch any function called `withTaskQueue` regardless of package. Refine to FQN-based
 * matching once the runtime DSL is wired.
 */
class TemporalTaskQueueChecker(
    private val knownQueues: Set<String>,
) : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (knownQueues.isEmpty()) return
        if (expression.calleeReference.name.asString() != "withTaskQueue") return

        val firstArg = expression.argumentList.arguments.firstOrNull() ?: return
        val literal = firstArg as? FirLiteralExpression ?: return
        val queueName = literal.value as? String ?: return

        if (queueName !in knownQueues) {
            reporter.reportOn(
                firstArg.source,
                TemporalDiagnostics.TEMPORAL_UNKNOWN_TASK_QUEUE,
                queueName,
            )
        }
    }
}
