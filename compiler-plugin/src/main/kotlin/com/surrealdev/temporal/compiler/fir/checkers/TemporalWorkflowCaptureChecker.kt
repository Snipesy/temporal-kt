package com.surrealdev.temporal.compiler.fir.checkers

import com.surrealdev.temporal.compiler.fir.diagnostics.TemporalDiagnostics
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Reports [TemporalDiagnostics.TEMPORAL_WORKFLOW_LAMBDA_CAPTURES_NOT_SUPPORTED] when a
 * `workflow("Name") { ... }` lambda references a value declared *outside* itself (capture).
 *
 * Detection: walk the lambda body for `FirQualifiedAccessExpression`s whose resolved callable
 * symbol is **not** declared inside the lambda or any enclosing class/file/package symbol that we
 * accept as global. We treat package-level functions, top-level objects, classes, and member
 * functions of those as "static" (allowed); local variables and value parameters of the
 * declaring scope are flagged.
 *
 * Limitation: does not currently distinguish "local" from "package-static" via a precise scoping
 * walk; instead we approximate by checking whether the resolved symbol is a [FirValueParameterSymbol]
 * or a property whose containing scope is a function. Tighten if false-positives appear.
 */
class TemporalWorkflowCaptureChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (expression.calleeReference.name != WORKFLOW_NAME) return
        val resolved =
            expression.toResolvedCallableSymbol()
                ?: return
        val callableId = resolved.callableId ?: return
        if (callableId.packageName != TEMPORAL_DSL_PACKAGE) return

        val lambdaArg = expression.argumentList.arguments.lastOrNull() ?: return
        val lambdaExpr = (lambdaArg as? FirAnonymousFunctionExpression) ?: return
        val lambdaSymbol = lambdaExpr.anonymousFunction.symbol

        val visitor = CaptureFinder(lambdaSymbol)
        lambdaExpr.anonymousFunction.body?.accept(visitor)

        for (captureSource in visitor.capturedSources) {
            reporter.reportOn(captureSource, TemporalDiagnostics.TEMPORAL_WORKFLOW_LAMBDA_CAPTURES_NOT_SUPPORTED)
        }
    }

    private companion object {
        private val WORKFLOW_NAME = Name.identifier("workflow")
        private val TEMPORAL_DSL_PACKAGE = FqName("com.surrealdev.temporal.dsl")
    }

    private class CaptureFinder(
        private val lambdaSymbol: FirAnonymousFunctionSymbol,
    ) : FirVisitorVoid() {
        val capturedSources = mutableListOf<org.jetbrains.kotlin.AbstractKtSourceElement?>()

        override fun visitElement(element: FirElement) {
            if (element is FirQualifiedAccessExpression) {
                val symbol = element.toResolvedCallableSymbol()
                if (symbol != null && isCapture(symbol)) {
                    capturedSources += element.source
                }
            }
            element.acceptChildren(this)
        }

        /**
         * A symbol is a capture iff it is a value parameter of an *enclosing* function (not the
         * lambda itself or a nested lambda). Package-level / member functions and properties are
         * permissible references.
         */
        private fun isCapture(symbol: FirCallableSymbol<*>): Boolean {
            if (symbol !is FirValueParameterSymbol) return false
            // The lambda's own value parameters are not captures.
            // FirValueParameterSymbol exposes its owner via `fir.containingDeclarationSymbol`.
            @OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)
            val containing = symbol.fir.containingDeclarationSymbol
            if (containing == lambdaSymbol) return false
            return true
        }
    }
}
