package com.surrealdev.temporal.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val WORKFLOW_DSL_FQN = FqName("com.surrealdev.temporal.dsl.workflow")
private val ACTIVITY_DSL_FQN = FqName("com.surrealdev.temporal.dsl.activity")

/**
 * Stage 8.1 fallback rewriter for orphaned DSL calls.
 *
 * The `compiler-plugin-runtime` `workflow(name, body)` / `activity(name, body)` stubs are no-op
 * placeholders so user code typechecks. Once Stages 8.4 (inline workflow synthesis) and 8.6 (inline
 * activity lifting) ship, the real transforms happen before this lowering and consume those calls.
 *
 * Anything still left as a raw `workflow(...)` / `activity(...)` call after the higher passes ran
 * is an *orphan* — e.g. used outside a `taskQueue { ... }` block, or used in a way the higher
 * passes don't recognise. We neutralise it so it doesn't actually invoke the no-op stub at runtime:
 *
 * - `Unit`-returning call → `Unit` reference. Silent no-op.
 * - Non-`Unit` call → `kotlin.error("...")`. `Nothing` is assignment-compatible anywhere; the
 *   message tells the user the call wasn't picked up by the plugin.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class TemporalDslLowering(
    private val pluginContext: IrPluginContext,
) : IrElementTransformerVoid() {
    private val unitType: IrType = pluginContext.irBuiltIns.unitType
    private val unitClass: IrClassSymbol = pluginContext.irBuiltIns.unitClass
    private val errorFunction: IrSimpleFunctionSymbol? =
        pluginContext.finderForBuiltins().findFunctions(KOTLIN_ERROR).firstOrNull()
    private val stringType: IrType = pluginContext.irBuiltIns.stringType

    fun lower(moduleFragment: IrModuleFragment) {
        moduleFragment.transformChildren(this, null)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val transformed = super.visitCall(expression)
        if (transformed !is IrCall) return transformed
        val fqn = transformed.symbol.owner.kotlinFqName
        if (fqn != WORKFLOW_DSL_FQN && fqn != ACTIVITY_DSL_FQN) return transformed

        return if (transformed.type == unitType) {
            IrGetObjectValueImpl(transformed.startOffset, transformed.endOffset, unitType, unitClass)
        } else {
            buildErrorCall(transformed, fqn) ?: transformed
        }
    }

    private fun buildErrorCall(
        original: IrCall,
        fqn: FqName,
    ): IrExpression? {
        val errorSym = errorFunction ?: return null
        val message =
            "Temporal DSL call '$fqn' was not lowered by the compiler plugin and " +
                "must not be invoked at runtime"
        val messageConst =
            IrConstImpl.string(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                type = stringType,
                value = message,
            )
        val errCall =
            IrCallImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                type = original.type,
                symbol = errorSym,
                typeArgumentsCount = 0,
            )
        errCall.arguments[0] = messageConst
        return errCall
    }

    private companion object {
        private val KOTLIN_ERROR = CallableId(FqName("kotlin"), Name.identifier("error"))
    }
}
