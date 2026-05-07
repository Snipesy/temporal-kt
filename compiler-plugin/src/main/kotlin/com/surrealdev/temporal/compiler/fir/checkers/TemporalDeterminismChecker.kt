package com.surrealdev.temporal.compiler.fir.checkers

import com.surrealdev.temporal.compiler.fir.diagnostics.TemporalDiagnostics
import com.surrealdev.temporal.compiler.shared.DeterminismRulesConfig
import com.surrealdev.temporal.compiler.shared.DeterminismRulesLoader
import com.surrealdev.temporal.compiler.shared.RuleMatch
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val WORKFLOW_ANNOTATION_CLASS_ID =
    ClassId.topLevel(FqName("com.surrealdev.temporal.annotation.Workflow"))

private val TEMPORAL_DSL_PACKAGE = FqName("com.surrealdev.temporal.dsl")
private val ACTIVITY_DSL_NAME = Name.identifier("activity")

private fun FirFunctionCall.dslCalleeName(): Name? {
    val callable = toResolvedCallableSymbol() ?: return null
    val callableId = callable.callableId ?: return null
    if (callableId.packageName != TEMPORAL_DSL_PACKAGE) return null
    return callableId.callableName
}

/**
 * FIR-time determinism checker. Reports [TemporalDiagnostics.TEMPORAL_NONDETERMINISTIC_CALL]
 * on calls inside `@Workflow`-annotated classes that violate rules from `determinism-rules.json`.
 *
 * **Transitive same-file taint:** Helper functions defined in the same file that are called from
 * `@Workflow` member bodies are also checked. The algorithm:
 *
 * 1. Find every `@Workflow`-annotated class in the file; seed all its member functions/properties
 *    as tainted.
 * 2. Build a same-file callable index (`FirCallableSymbol → FirDeclaration`) covering top-level
 *    functions/properties and class members at any depth.
 * 3. BFS: walk each tainted declaration's body looking for `FirFunctionCall` / `FirQualifiedAccessExpression`.
 *    Whenever the resolved callee is in the same-file index, mark it tainted and enqueue it. Loop
 *    until no new declarations are added.
 * 4. Apply determinism rules to every `FirQualifiedAccessExpression` inside any tainted declaration,
 *    reporting on each match.
 *
 * Cross-file taint is intentionally out of scope — the prior IR validator was also same-file.
 */
object TemporalDeterminismFileChecker : FirFileChecker(MppCheckerKind.Common) {
    private val rulesConfig: DeterminismRulesConfig by lazy { DeterminismRulesLoader.load() }

    @OptIn(DirectDeclarationsAccess::class)
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val workflowClasses =
            declaration.declarations
                .filterIsInstance<FirRegularClass>()
                .filter { it.symbol.hasAnnotation(WORKFLOW_ANNOTATION_CLASS_ID, context.session) }
        if (workflowClasses.isEmpty()) return

        val sameFileCallables = collectSameFileCallables(declaration)
        val seedFunctions = workflowClasses.flatMap { it.collectFunctionLikeMembers() }
        val tainted = bfsTaint(seedFunctions, sameFileCallables, context.session)

        for (decl in tainted) {
            scanForViolations(decl, context, reporter)
        }
    }

    @OptIn(DirectDeclarationsAccess::class)
    private fun collectSameFileCallables(file: FirFile): Map<FirCallableSymbol<*>, FirDeclaration> {
        val map = mutableMapOf<FirCallableSymbol<*>, FirDeclaration>()

        fun visit(declarations: List<FirDeclaration>) {
            for (d in declarations) {
                when (d) {
                    is FirNamedFunction -> {
                        map[d.symbol] = d
                    }

                    is FirProperty -> {
                        map[d.symbol] = d
                    }

                    is FirRegularClass -> {
                        visit(d.declarations)
                    }

                    else -> {}
                }
            }
        }
        visit(file.declarations)
        return map
    }

    @OptIn(DirectDeclarationsAccess::class)
    private fun FirRegularClass.collectFunctionLikeMembers(): List<FirDeclaration> {
        val result = mutableListOf<FirDeclaration>()
        for (member in declarations) {
            when (member) {
                is FirNamedFunction, is FirProperty -> {
                    result += member
                }

                is FirRegularClass -> {
                    result += member.collectFunctionLikeMembers()
                }

                else -> {}
            }
        }
        return result
    }

    private fun bfsTaint(
        seed: List<FirDeclaration>,
        sameFileCallables: Map<FirCallableSymbol<*>, FirDeclaration>,
        session: FirSession,
    ): Set<FirDeclaration> {
        val tainted = LinkedHashSet<FirDeclaration>()
        val queue = ArrayDeque<FirDeclaration>()
        for (s in seed) if (tainted.add(s)) queue += s

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.accept(
                object : FirVisitorVoid() {
                    override fun visitElement(element: FirElement) {
                        if (element is FirFunctionCall && element.dslCalleeName() == ACTIVITY_DSL_NAME) {
                            // Skip activity lambda bodies — non-determinism is allowed there.
                            return
                        }
                        if (element is FirQualifiedAccessExpression) {
                            val callee = element.toResolvedCallableSymbol()
                            val target = callee?.let { sameFileCallables[it] }
                            if (target != null && tainted.add(target)) queue += target
                        }
                        element.acceptChildren(this)
                    }
                },
                null,
            )
        }
        return tainted
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun scanForViolations(
        declaration: FirDeclaration,
        contextRef: CheckerContext,
        reporterRef: DiagnosticReporter,
    ) {
        declaration.accept(
            object : FirVisitorVoid() {
                // FirPropertyAccessExpression / FirFunctionCall both extend FirQualifiedAccessExpression,
                // but FirVisitorVoid dispatches them to their specific visit* methods which default to
                // visitElement (NOT visitQualifiedAccessExpression). Catch them via visitElement for completeness.
                override fun visitElement(element: FirElement) {
                    if (element is FirFunctionCall && element.dslCalleeName() == ACTIVITY_DSL_NAME) {
                        // Don't recurse into activity lambdas — their bodies may legitimately be
                        // non-deterministic. The activity() call itself is uninteresting (it's
                        // package-level DSL, no rule matches).
                        return
                    }
                    if (element is FirQualifiedAccessExpression) {
                        applyRules(element, contextRef, reporterRef)
                    }
                    element.acceptChildren(this)
                }
            },
            null,
        )
    }

    private fun applyRules(
        expression: FirQualifiedAccessExpression,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        val callable = expression.toResolvedCallableSymbol() ?: return
        val callableId = callable.callableId ?: return
        val calleeFqName = callableId.asSingleFqName().asString()
        val calleeName = callableId.callableName.asString()

        val dispatchReceiverFqn =
            expression.dispatchReceiver
                ?.resolvedType
                ?.classId
                ?.asSingleFqName()
                ?.asString()
        val extensionReceiverFqn =
            expression.extensionReceiver
                ?.resolvedType
                ?.classId
                ?.asSingleFqName()
                ?.asString()

        for (rule in rulesConfig.rules) {
            if (matches(rule.match, expression, calleeFqName, calleeName, dispatchReceiverFqn, extensionReceiverFqn)) {
                reporter.reportOn(
                    expression.source,
                    TemporalDiagnostics.TEMPORAL_NONDETERMINISTIC_CALL,
                    rule.name,
                    context = context,
                )
                return
            }
        }
    }

    private fun matches(
        match: RuleMatch,
        expression: FirQualifiedAccessExpression,
        calleeFqName: String,
        calleeName: String,
        dispatchReceiverFqn: String?,
        extensionReceiverFqn: String?,
    ): Boolean {
        if (match.function != null) {
            if (calleeFqName != match.function) return false
            if (match.argumentTypes != null) {
                return matchesArgumentTypes(expression, match.argumentTypes)
            }
            return true
        }
        if (match.parameterKind != null && match.type != null) {
            val receiverFqn =
                when (match.parameterKind) {
                    "DispatchReceiver" -> dispatchReceiverFqn
                    "ExtensionReceiver" -> extensionReceiverFqn
                    else -> null
                } ?: return false
            if (receiverFqn != match.type) return false
            if (match.functionPattern != null) {
                val patternStripped =
                    match.functionPattern
                        .removePrefix("<get-")
                        .removePrefix("<set-")
                        .removeSuffix(">")
                return calleeName == match.functionPattern || calleeName == patternStripped
            }
            return true
        }
        return false
    }

    private fun matchesArgumentTypes(
        expression: FirQualifiedAccessExpression,
        argumentTypes: List<String>,
    ): Boolean {
        val call = expression as? FirFunctionCall ?: return false
        for (arg in call.argumentList.arguments) {
            val argFqn =
                arg.resolvedType.classId
                    ?.asSingleFqName()
                    ?.asString() ?: continue
            if (argumentTypes.any { argFqn.contains(it) || argFqn == it }) return true
        }
        return false
    }
}
