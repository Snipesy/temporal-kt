package com.surrealdev.temporal.compiler

import com.surrealdev.temporal.annotation.Workflow
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName

/**
 * Validates determinism requirements in workflow code by detecting forbidden patterns
 * defined in determinism-rules.json.
 *
 * Emits compiler errors when these patterns are detected in @Workflow annotated classes
 * or in functions reachable from workflow code.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class WorkflowDeterminismValidator(
    private val pluginContext: IrPluginContext,
) : IrVisitorVoid() {
    private val workflowAnnotationFqName = FqName(Workflow::class.qualifiedName!!)
    private var currentWorkflowClass: IrClass? = null
    private var insideWorkflowCode = false
    private var currentFile: IrFile? = null

    // For recursive call chain validation
    private val functionsToValidate = mutableSetOf<IrFunction>()
    private val validatedFunctions = mutableSetOf<IrFunction>()

    // Load rules from JSON
    private val rulesConfig = DeterminismRulesLoader.load()

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitFile(declaration: IrFile) {
        currentFile = declaration
        functionsToValidate.clear()

        // First pass: visit the file normally, collecting functions called from workflow code
        declaration.acceptChildrenVoid(this)

        // Second pass: recursively validate all functions reachable from workflow code
        while (functionsToValidate.isNotEmpty()) {
            val func = functionsToValidate.first()
            functionsToValidate.remove(func)
            if (func !in validatedFunctions) {
                validatedFunctions.add(func)
                // Validate this function as if it were workflow code
                insideWorkflowCode = true
                func.acceptChildrenVoid(this)
                insideWorkflowCode = false
            }
        }

        currentFile = null
    }

    override fun visitClass(declaration: IrClass) {
        val wasInWorkflow = insideWorkflowCode
        val previousClass = currentWorkflowClass

        if (declaration.hasAnnotation(workflowAnnotationFqName)) {
            insideWorkflowCode = true
            currentWorkflowClass = declaration
        }

        declaration.acceptChildrenVoid(this)

        insideWorkflowCode = wasInWorkflow
        currentWorkflowClass = previousClass
    }

    override fun visitFunction(declaration: IrFunction) {
        if (!insideWorkflowCode) {
            declaration.acceptChildrenVoid(this)
            return
        }

        declaration.acceptChildrenVoid(this)
    }

    override fun visitCall(expression: IrCall) {
        if (!insideWorkflowCode) {
            expression.acceptChildrenVoid(this)
            return
        }

        val callee = expression.symbol.owner
        val calleeFqName = callee.fqNameWhenAvailable?.asString()
        val calleeName = callee.name.asString()

        // Check each rule
        // TODO probably worth to sort these into maps for faster lookup
        for (rule in rulesConfig.rules) {
            val match = rule.match

            // Check function FQ name match
            if (match.function != null) {
                if (calleeFqName == match.function) {
                    // If argumentTypes is specified, check arguments
                    if (match.argumentTypes != null) {
                        if (checkArgumentTypes(expression, match.argumentTypes)) {
                            reportViolation(expression, rule)
                        }
                    } else {
                        reportViolation(expression, rule)
                    }
                    continue
                }
            }

            // Check parameter kind + type match
            if (match.parameterKind != null && match.type != null) {
                val paramKind = DeterminismRulesLoader.parseParameterKind(match.parameterKind)
                val paramIdx = callee.parameters.indexOfFirst { it.kind == paramKind }

                if (paramIdx >= 0 && paramIdx < expression.arguments.size) {
                    val arg = expression.arguments[paramIdx]
                    val argType =
                        arg
                            ?.type
                            ?.getClass()
                            ?.kotlinFqName
                            ?.asString()

                    if (argType == match.type) {
                        // If functionPattern is specified, also check function name
                        if (match.functionPattern != null) {
                            if (calleeName == match.functionPattern) {
                                reportViolation(expression, rule)
                            }
                        } else {
                            reportViolation(expression, rule)
                        }
                    }
                }
            }
        }

        // Queue called functions for recursive validation
        if (shouldTrackForValidation(callee)) {
            functionsToValidate.add(callee)
        }

        expression.acceptChildrenVoid(this)
    }

    /**
     * Check if any regular argument has one of the specified types.
     */
    private fun checkArgumentTypes(
        expression: IrCall,
        argumentTypes: List<String>,
    ): Boolean {
        val callee = expression.symbol.owner

        for ((index, param) in callee.parameters.withIndex()) {
            if (param.kind == IrParameterKind.Regular && index < expression.arguments.size) {
                val arg = expression.arguments[index]
                val argType =
                    arg
                        ?.type
                        ?.getClass()
                        ?.kotlinFqName
                        ?.asString() ?: continue

                if (argumentTypes.any { argType.contains(it) || argType == it }) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Determines if a function should be tracked for recursive validation.
     * Skip functions already validated or queued to avoid infinite recursion.
     */
    private fun shouldTrackForValidation(func: IrFunction): Boolean =
        func !in validatedFunctions && func !in functionsToValidate

    private fun reportViolation(
        expression: IrCall,
        rule: DeterminismRule,
    ) {
        val file = currentFile ?: error("No current file tracked")
        val line = file.fileEntry.getLineNumber(expression.startOffset) + 1
        val filePath = file.fileEntry.name
        val errorMessage = rule.error ?: rulesConfig.defaultError

        error(
            "[TEMPORAL] Workflow determinism violation at $filePath:$line\n" +
                "  ${rule.name}: $errorMessage",
        )
    }
}
