package com.surrealdev.temporal.compiler.fir

import com.surrealdev.temporal.compiler.fir.checkers.TemporalDeterminismFileChecker
import com.surrealdev.temporal.compiler.fir.checkers.TemporalTaskQueueChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class TemporalFirAdditionalCheckersExtension(
    session: FirSession,
    private val knownTaskQueues: Set<String>,
) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers: DeclarationCheckers =
        object : DeclarationCheckers() {
            override val fileCheckers: Set<FirFileChecker> = setOf(TemporalDeterminismFileChecker)
        }

    override val expressionCheckers: ExpressionCheckers =
        object : ExpressionCheckers() {
            override val functionCallCheckers: Set<FirFunctionCallChecker> =
                setOf(TemporalTaskQueueChecker(knownTaskQueues))
        }
}
