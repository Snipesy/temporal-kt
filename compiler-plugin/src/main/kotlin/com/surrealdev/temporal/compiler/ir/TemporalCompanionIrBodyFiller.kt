package com.surrealdev.temporal.compiler.ir

import com.surrealdev.temporal.compiler.fir.TemporalCompanionKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/**
 * Fills bodies of FIR-synthesised companion functions (Stage 8.5).
 *
 * For each function with origin `IrDeclarationOrigin.GeneratedByPlugin(TemporalCompanionKey)`,
 * matches by simple name and emits the right body:
 *
 * - `start(client, taskQueue, [arg], options): TypedWorkflowHandle<R>`
 *   → `return startTypedWorkflow(client, "WorkflowType", taskQueue, newWorkflowId(),
 *           arg, typeFromClass(ArgT::class) | null, options, typeFromClass(R::class))`
 *
 * - `execute(client, taskQueue, [arg], options): R`
 *   → `return start(client, taskQueue, [arg], options).result()`
 *
 * - `options` parameter default value → `WorkflowStartOptions()`
 *
 * The workflow type name comes from the parent class's `@Workflow("name")` annotation argument
 * (with the user's class simple name as fallback).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class TemporalCompanionIrBodyFiller(
    private val pluginContext: IrPluginContext,
) {
    private val workflowAnnotationFqn = FqName("com.surrealdev.temporal.annotation.Workflow")

    private val finder by lazy { pluginContext.finderForBuiltins() }

    // Lazy because not every compilation has @Workflow classes — the IR pipeline runs the body
    // filler unconditionally, but the lookups are only needed when an actual companion body
    // needs filling. Eagerly resolving in the constructor would fail compilation of any module
    // without :core on its classpath, including unrelated test fixtures.
    private val typedHandleClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("com.surrealdev.temporal.client.TypedWorkflowHandle")))
            ?: error("TypedWorkflowHandle class not on classpath — :core dependency missing")
    }

    private val workflowStartOptionsClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("com.surrealdev.temporal.client.WorkflowStartOptions")))
            ?: error("WorkflowStartOptions class not on classpath")
    }

    private val startTypedWorkflowFn: IrSimpleFunctionSymbol by lazy {
        finder.findFunctions(
            CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("startTypedWorkflow")),
        ).firstOrNull()
            ?: error("startTypedWorkflow runtime helper not on classpath")
    }

    private val newWorkflowIdFn: IrSimpleFunctionSymbol by lazy {
        finder.findFunctions(
            CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("newWorkflowId")),
        ).firstOrNull()
            ?: error("newWorkflowId runtime helper not on classpath")
    }

    private val typeFromClassFn: IrSimpleFunctionSymbol by lazy {
        finder.findFunctions(
            CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("typeFromClass")),
        ).firstOrNull()
            ?: error("typeFromClass runtime helper not on classpath")
    }

    private val resultFn: IrSimpleFunctionSymbol by lazy {
        typedHandleClass.functions.firstOrNull { it.owner.name.asString() == "result" }
            ?: error("TypedWorkflowHandle.result not found")
    }

    private val workflowStartOptionsCtor by lazy {
        workflowStartOptionsClass.constructors
            .firstOrNull { ctor ->
                val regularParams = ctor.owner.parameters.filter { p -> p.kind == IrParameterKind.Regular }
                regularParams.isEmpty() || regularParams.all { it.defaultValue != null }
            }
            ?: error("WorkflowStartOptions has no usable no-arg constructor")
    }

    private val kClassClassSymbol: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("kotlin.reflect.KClass")))
            ?: error("kotlin.reflect.KClass not found")
    }

    fun lower(moduleFragment: IrModuleFragment) {
        moduleFragment.transform(BodyFiller(), null)
    }

    private inner class BodyFiller : IrElementTransformerVoid() {
        override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
            val origin = declaration.origin
            val pluginKey = (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey
            if (pluginKey == TemporalCompanionKey) {
                fillIfMatch(declaration)
            }
            return super.visitSimpleFunction(declaration)
        }
    }

    private fun fillIfMatch(function: IrSimpleFunction) {
        val name = function.name.asString()
        // Default value for `options` parameter — applied to every plugin-generated function
        // that has it (FIR builder produced a `Stub` body in its place).
        regularParam(function, "options")?.let { fillOptionsDefault(it) }
        when (name) {
            "start" -> fillStart(function)
            "execute" -> fillExecute(function)
            // Constructor body is produced by `createDefaultPrivateConstructor` already.
        }
    }

    private fun regularParam(function: IrSimpleFunction, paramName: String): IrValueParameter? =
        function.parameters.firstOrNull { it.kind == IrParameterKind.Regular && it.name.asString() == paramName }

    private fun fillOptionsDefault(param: IrValueParameter) {
        // Always overwrite — FIR generator's stub default (`throw Throwable("Stub")`) needs
        // replacing whether or not it was set.
        val ctorCall =
            IrConstructorCallImpl(
                startOffset = param.startOffset,
                endOffset = param.endOffset,
                type = workflowStartOptionsClass.defaultType,
                symbol = workflowStartOptionsCtor,
                typeArgumentsCount = 0,
                constructorTypeArgumentsCount = 0,
            )
        param.defaultValue =
            pluginContext.irFactory.createExpressionBody(param.startOffset, param.endOffset, ctorCall)
    }

    private fun fillStart(function: IrSimpleFunction) {
        val parentCompanion = function.parentClassOrNull ?: return
        val workflowClass = parentCompanion.parentClassOrNull ?: return
        val workflowTypeName = readWorkflowTypeName(workflowClass) ?: workflowClass.name.asString()

        val clientParam = regularParam(function, "client") ?: return
        val taskQueueParam = regularParam(function, "taskQueue") ?: return
        val argParam = regularParam(function, "arg")
        val optionsParam = regularParam(function, "options") ?: return

        val resultType = extractResultType(function.returnType) ?: pluginContext.irBuiltIns.anyNType
        val so = function.startOffset
        val eo = function.endOffset

        val startCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = function.returnType,
                symbol = startTypedWorkflowFn,
                typeArgumentsCount = 0,
            )
        // Signature: client, workflowType, taskQueue, workflowId, arg, argType, options, resultType
        startCall.arguments[0] = IrGetValueImpl(so, eo, clientParam.symbol)
        startCall.arguments[1] = stringConst(workflowTypeName, so, eo)
        startCall.arguments[2] = IrGetValueImpl(so, eo, taskQueueParam.symbol)
        startCall.arguments[3] = newWorkflowIdCall(so, eo)
        startCall.arguments[4] = argParam?.let { IrGetValueImpl(so, eo, it.symbol) } ?: nullExpr(so, eo)
        startCall.arguments[5] = argParam?.let { typeFromClassCall(it.type, so, eo) } ?: nullExpr(so, eo)
        startCall.arguments[6] = IrGetValueImpl(so, eo, optionsParam.symbol)
        startCall.arguments[7] = typeFromClassCall(resultType, so, eo)

        function.body =
            pluginContext.irFactory.createBlockBody(
                so, eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, startCall)),
            )
    }

    private fun fillExecute(function: IrSimpleFunction) {
        val parentCompanion = function.parentClassOrNull ?: return
        val startSymbol =
            parentCompanion.functions.firstOrNull { it.name.asString() == "start" }?.symbol ?: return

        val clientParam = regularParam(function, "client") ?: return
        val taskQueueParam = regularParam(function, "taskQueue") ?: return
        val argParam = regularParam(function, "arg")
        val optionsParam = regularParam(function, "options") ?: return

        val so = function.startOffset
        val eo = function.endOffset

        val typedHandleType = startSymbol.owner.returnType
        val startCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = typedHandleType,
                symbol = startSymbol,
                typeArgumentsCount = 0,
            )
        // arguments[0] is the dispatch receiver — pass execute's own dispatch receiver since
        // both functions live on the same companion.
        val dispatchReceiver = function.dispatchReceiverParameter
            ?: error("Companion-generated function must have dispatch receiver")
        startCall.arguments[0] = IrGetValueImpl(so, eo, dispatchReceiver.symbol)
        startCall.arguments[1] = IrGetValueImpl(so, eo, clientParam.symbol)
        startCall.arguments[2] = IrGetValueImpl(so, eo, taskQueueParam.symbol)
        var idx = 3
        if (argParam != null) {
            startCall.arguments[idx++] = IrGetValueImpl(so, eo, argParam.symbol)
        }
        startCall.arguments[idx] = IrGetValueImpl(so, eo, optionsParam.symbol)

        val resultCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = function.returnType,
                symbol = resultFn,
                typeArgumentsCount = 0,
            )
        resultCall.arguments[0] = startCall

        function.body =
            pluginContext.irFactory.createBlockBody(
                so, eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, resultCall)),
            )
    }

    /** Read the user-supplied `name` argument from `@Workflow("name")`. */
    private fun readWorkflowTypeName(klass: IrClass): String? {
        for (annotation in klass.annotations) {
            if (annotation.symbol.owner.parentClassOrNull?.kotlinFqName != workflowAnnotationFqn) continue
            // Annotation's first regular argument is `name` — use positional access.
            val firstArg = annotation.arguments.firstOrNull() ?: continue
            val constValue = (firstArg as? IrConst)?.value as? String
            if (!constValue.isNullOrBlank()) return constValue
        }
        return null
    }

    /** Extract `R` from `TypedWorkflowHandle<R>`. */
    private fun extractResultType(typedHandleType: IrType): IrType? {
        val args = (typedHandleType as? IrSimpleType)?.arguments ?: return null
        return (args.firstOrNull() as? IrTypeProjection)?.type
    }

    private fun typeFromClassCall(forType: IrType, start: Int, end: Int): IrExpression {
        val classRef = classRefOf(forType, start, end) ?: return nullExpr(start, end)
        val call =
            IrCallImpl(
                startOffset = start,
                endOffset = end,
                type = typeFromClassFn.owner.returnType,
                symbol = typeFromClassFn,
                typeArgumentsCount = 0,
            )
        call.arguments[0] = classRef
        return call
    }

    private fun classRefOf(type: IrType, start: Int, end: Int): IrExpression? {
        val classifier = type.classifierOrNull as? IrClassSymbol ?: return null
        val kclassType =
            IrSimpleTypeImpl(
                classifier = kClassClassSymbol,
                nullability = SimpleTypeNullability.NOT_SPECIFIED,
                arguments = listOf(makeTypeProjection(classifier.starProjectedType, Variance.INVARIANT)),
                annotations = emptyList(),
            )
        return IrClassReferenceImpl(
            startOffset = start,
            endOffset = end,
            type = kclassType,
            symbol = classifier,
            classType = classifier.starProjectedType,
        )
    }

    private fun newWorkflowIdCall(start: Int, end: Int): IrExpression =
        IrCallImpl(
            startOffset = start,
            endOffset = end,
            type = pluginContext.irBuiltIns.stringType,
            symbol = newWorkflowIdFn,
            typeArgumentsCount = 0,
        )

    private fun stringConst(value: String, start: Int, end: Int): IrExpression =
        IrConstImpl(start, end, pluginContext.irBuiltIns.stringType, IrConstKind.String, value)

    private fun nullExpr(start: Int, end: Int): IrExpression =
        IrConstImpl(start, end, pluginContext.irBuiltIns.anyNType, IrConstKind.Null, null)
}
