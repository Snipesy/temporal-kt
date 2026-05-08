package com.surrealdev.temporal.compiler.ir

import com.surrealdev.temporal.compiler.fir.TemporalCompanionKey
import com.surrealdev.temporal.compiler.fir.TemporalQueryKey
import com.surrealdev.temporal.compiler.fir.TemporalSignalKey
import com.surrealdev.temporal.compiler.fir.TemporalUpdateKey
import com.surrealdev.temporal.compiler.vs.TemporalIrApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
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
import org.jetbrains.kotlin.ir.util.defaultType
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
 * Fills bodies of FIR-synthesised companion + Handle declarations.
 *
 * For each FIR-generated declaration with `IrDeclarationOrigin.GeneratedByPlugin(TemporalCompanionKey)`:
 *
 * - **Companion `start(...)`**:
 *   ```
 *   suspend fun start(client, taskQueue, [arg], options): <UserClass>.Handle =
 *       <UserClass>.Handle(
 *           startWorkflowGetHandle(client, "<WorkflowType>", taskQueue, newWorkflowId(),
 *               arg, argType, options),
 *           typeFromClass(<R>::class),
 *       )
 *   ```
 *
 * - **Companion `handle(client, workflowId, runId)`**:
 *   ```
 *   fun handle(client, workflowId, runId): <UserClass>.Handle =
 *       <UserClass>.Handle(
 *           client.getWorkflowHandle(workflowId, runId),
 *           typeFromClass(<R>::class),
 *       )
 *   ```
 *
 * - **`<UserClass>.Handle.<init>(handle, resultType)`**:
 *   ```
 *   internal constructor(handle: WorkflowHandle, resultType: KType) :
 *       super(handle, resultType)
 *   ```
 *   Just a delegating call to `TypedWorkflowHandle`'s primary constructor + the standard
 *   instance-initializer marker.
 *
 * - **`options` parameter default value**: `WorkflowStartOptions()`.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class TemporalCompanionIrBodyFiller(
    private val pluginContext: IrPluginContext,
) {
    private val workflowAnnotationFqn = FqName("com.surrealdev.temporal.annotation.Workflow")

    private val finder by lazy { pluginContext.finderForBuiltins() }

    private val typedHandleClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("com.surrealdev.temporal.client.TypedWorkflowHandle")))
            ?: error("TypedWorkflowHandle class not on classpath — :core dependency missing")
    }

    private val typedHandleCtor: IrConstructorSymbol by lazy {
        typedHandleClass.constructors.firstOrNull()
            ?: error("TypedWorkflowHandle constructor not found")
    }

    private val workflowStartOptionsClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("com.surrealdev.temporal.client.WorkflowStartOptions")))
            ?: error("WorkflowStartOptions class not on classpath")
    }

    private val workflowStartOptionsCtor: IrConstructorSymbol by lazy {
        workflowStartOptionsClass.constructors
            .firstOrNull { ctor ->
                val regularParams = ctor.owner.parameters.filter { p -> p.kind == IrParameterKind.Regular }
                regularParams.isEmpty() || regularParams.all { it.defaultValue != null }
            }
            ?: error("WorkflowStartOptions has no usable no-arg constructor")
    }

    private val startWorkflowGetHandleFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("startWorkflowGetHandle")),
            ).firstOrNull()
            ?: error("startWorkflowGetHandle runtime helper not on classpath")
    }

    private val getWorkflowHandleFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("getWorkflowHandle")),
            ).firstOrNull()
            ?: error("getWorkflowHandle runtime helper not on classpath")
    }

    private val newWorkflowIdFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("newWorkflowId")),
            ).firstOrNull()
            ?: error("newWorkflowId runtime helper not on classpath")
    }

    private val typeFromClassFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("typeFromClass")),
            ).firstOrNull()
            ?: error("typeFromClass runtime helper not on classpath")
    }

    private val kClassClassSymbol: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("kotlin.reflect.KClass")))
            ?: error("kotlin.reflect.KClass not found")
    }

    private val signalTypedFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("signalTyped")))
            .firstOrNull()
            ?: error("signalTyped runtime helper not on classpath")
    }

    private val queryTypedFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("queryTyped")))
            .firstOrNull()
            ?: error("queryTyped runtime helper not on classpath")
    }

    private val updateTypedFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("updateTyped")))
            .firstOrNull()
            ?: error("updateTyped runtime helper not on classpath")
    }

    private val anyClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("kotlin.Any")))
            ?: error("kotlin.Any not found")
    }

    /** Annotation FQNs for `@Signal` / `@Query` / `@Update` — used to read wire names from user methods. */
    private val signalAnnotationFqn = FqName("com.surrealdev.temporal.annotation.Signal")
    private val queryAnnotationFqn = FqName("com.surrealdev.temporal.annotation.Query")
    private val updateAnnotationFqn = FqName("com.surrealdev.temporal.annotation.Update")

    fun lower(moduleFragment: IrModuleFragment) {
        moduleFragment.transform(BodyFiller(), null)
    }

    private inner class BodyFiller : IrElementTransformerVoid() {
        override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
            val origin = declaration.origin
            val pluginKey = (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey
            when (pluginKey) {
                TemporalCompanionKey -> fillFunctionIfMatch(declaration)
                TemporalSignalKey -> fillSignalWrapper(declaration)
                TemporalQueryKey -> fillQueryOrUpdateWrapper(declaration, queryTypedFn)
                TemporalUpdateKey -> fillQueryOrUpdateWrapper(declaration, updateTypedFn)
            }
            return super.visitSimpleFunction(declaration)
        }

        override fun visitConstructor(declaration: IrConstructor): IrStatement {
            val origin = declaration.origin
            val pluginKey = (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey
            if (pluginKey == TemporalCompanionKey) {
                fillConstructorIfMatch(declaration)
            }
            return super.visitConstructor(declaration)
        }
    }

    private fun fillFunctionIfMatch(function: IrSimpleFunction) {
        regularParam(function, "options")?.let { fillOptionsDefault(it) }
        when (function.name.asString()) {
            "start" -> fillStart(function)
            "handle" -> fillHandleMethod(function)
        }
    }

    private fun fillConstructorIfMatch(constructor: IrConstructor) {
        val parent = constructor.parentClassOrNull ?: return
        // Only fill the Handle class's primary constructor — companion's private ctor is
        // generated correctly by the FIR builder (delegating no-arg).
        if (parent.name.asString() != "Handle") return
        if (parent.parentClassOrNull?.let(::isWorkflowClass) != true) return
        fillHandleConstructor(constructor, parent)
    }

    private fun isWorkflowClass(klass: IrClass): Boolean =
        klass.annotations.any {
            it.symbol.owner.parentClassOrNull
                ?.kotlinFqName == workflowAnnotationFqn
        }

    private fun regularParam(
        function: IrSimpleFunction,
        paramName: String,
    ): IrValueParameter? =
        function.parameters.firstOrNull { it.kind == IrParameterKind.Regular && it.name.asString() == paramName }

    private fun fillOptionsDefault(param: IrValueParameter) {
        val ctorCall =
            TemporalIrApi.newConstructorCall(
                startOffset = param.startOffset,
                endOffset = param.endOffset,
                type = workflowStartOptionsClass.defaultType,
                symbol = workflowStartOptionsCtor,
            )
        param.defaultValue =
            pluginContext.irFactory.createExpressionBody(param.startOffset, param.endOffset, ctorCall)
    }

    private fun fillStart(function: IrSimpleFunction) {
        val parentCompanion = function.parentClassOrNull ?: return
        val workflowClass = parentCompanion.parentClassOrNull ?: return
        val handleClass = findHandleClass(workflowClass) ?: return
        val handleCtor = handleClass.primaryConstructorOrNull() ?: return
        val workflowTypeName = readWorkflowTypeName(workflowClass) ?: workflowClass.name.asString()

        val clientParam = regularParam(function, "client") ?: return
        val taskQueueParam = regularParam(function, "taskQueue") ?: return
        val argParam = regularParam(function, "arg")
        val optionsParam = regularParam(function, "options") ?: return

        val resultType = function.returnTypeResultArg() ?: pluginContext.irBuiltIns.anyNType
        val so = function.startOffset
        val eo = function.endOffset

        // startWorkflowGetHandle(client, "Test", taskQueue, newWorkflowId(), arg, argType, options)
        val startCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = startWorkflowGetHandleFn.owner.returnType,
                symbol = startWorkflowGetHandleFn,
                typeArgumentsCount = 0,
            )
        startCall.arguments[0] = IrGetValueImpl(so, eo, clientParam.symbol)
        startCall.arguments[1] = stringConst(workflowTypeName, so, eo)
        startCall.arguments[2] = IrGetValueImpl(so, eo, taskQueueParam.symbol)
        startCall.arguments[3] = newWorkflowIdCall(so, eo)
        startCall.arguments[4] = argParam?.let { IrGetValueImpl(so, eo, it.symbol) } ?: nullExpr(so, eo)
        startCall.arguments[5] = argParam?.let { typeFromClassCall(it.type, so, eo) } ?: nullExpr(so, eo)
        startCall.arguments[6] = IrGetValueImpl(so, eo, optionsParam.symbol)

        // <UserClass>.Handle<R>(startCall, typeFromClass(R::class))
        val handleCtorCall = buildHandleCtorCall(handleCtor, function.returnType, resultType, startCall, so, eo)

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, handleCtorCall)),
            )
    }

    private fun fillHandleMethod(function: IrSimpleFunction) {
        val parentCompanion = function.parentClassOrNull ?: return
        val workflowClass = parentCompanion.parentClassOrNull ?: return
        val handleClass = findHandleClass(workflowClass) ?: return
        val handleCtor = handleClass.primaryConstructorOrNull() ?: return

        val clientParam = regularParam(function, "client") ?: return
        val workflowIdParam = regularParam(function, "workflowId") ?: return
        val runIdParam = regularParam(function, "runId") ?: return

        // Default value for runId: null.
        if (runIdParam.defaultValue == null) {
            val nullDefault = nullExpr(runIdParam.startOffset, runIdParam.endOffset)
            runIdParam.defaultValue =
                pluginContext.irFactory.createExpressionBody(
                    runIdParam.startOffset,
                    runIdParam.endOffset,
                    nullDefault,
                )
        }

        val resultType = function.returnTypeResultArg() ?: pluginContext.irBuiltIns.anyNType
        val so = function.startOffset
        val eo = function.endOffset

        // client.getWorkflowHandle(workflowId, runId)
        val getHandleCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = getWorkflowHandleFn.owner.returnType,
                symbol = getWorkflowHandleFn,
                typeArgumentsCount = 0,
            )
        getHandleCall.arguments[0] = IrGetValueImpl(so, eo, clientParam.symbol)
        getHandleCall.arguments[1] = IrGetValueImpl(so, eo, workflowIdParam.symbol)
        getHandleCall.arguments[2] = IrGetValueImpl(so, eo, runIdParam.symbol)

        val handleCtorCall = buildHandleCtorCall(handleCtor, function.returnType, resultType, getHandleCall, so, eo)

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, handleCtorCall)),
            )
    }

    /**
     * Build `<UserClass>.Handle<R>(handleArg, typeFromClass(R::class))`.
     *
     * `handleType` is the function's return type — `Handle<R>` — used both as the result type
     * of the constructor call AND as the source for the type argument R.
     */
    private fun buildHandleCtorCall(
        handleCtor: IrConstructor,
        handleType: IrType,
        resultIrType: IrType,
        handleArg: IrExpression,
        startOffset: Int,
        endOffset: Int,
    ): IrConstructorCall {
        val ctorCall =
            TemporalIrApi.newConstructorCall(
                startOffset = startOffset,
                endOffset = endOffset,
                type = handleType,
                symbol = handleCtor.symbol,
                typeArgumentsCount = 1,
            )
        ctorCall.typeArguments[0] = resultIrType
        ctorCall.arguments[0] = handleArg
        ctorCall.arguments[1] = typeFromClassCall(resultIrType, startOffset, endOffset)
        return ctorCall
    }

    /**
     * Build:
     * ```
     * super<TypedWorkflowHandle>(handle, resultType)
     * <instance-init>
     * ```
     */
    private fun fillHandleConstructor(
        constructor: IrConstructor,
        handleClass: IrClass,
    ) {
        val handleParam =
            constructor.parameters.firstOrNull { it.kind == IrParameterKind.Regular && it.name.asString() == "handle" }
                ?: return
        val resultTypeParam =
            constructor.parameters.firstOrNull {
                it.kind == IrParameterKind.Regular &&
                    it.name.asString() == "resultType"
            }
                ?: return

        val so = constructor.startOffset
        val eo = constructor.endOffset

        val delegating =
            IrDelegatingConstructorCallImpl(
                startOffset = so,
                endOffset = eo,
                type = pluginContext.irBuiltIns.unitType,
                symbol = typedHandleCtor,
                typeArgumentsCount = 1,
            )
        // TypedWorkflowHandle<R> super call uses Handle's own R type parameter.
        val rTypeParam =
            handleClass.typeParameters.firstOrNull()
                ?: error("Handle class missing R type parameter")
        delegating.typeArguments[0] =
            IrSimpleTypeImpl(
                classifier = rTypeParam.symbol,
                nullability = SimpleTypeNullability.NOT_SPECIFIED,
                arguments = emptyList(),
                annotations = emptyList(),
            )
        delegating.arguments[0] = IrGetValueImpl(so, eo, handleParam.symbol)
        delegating.arguments[1] = IrGetValueImpl(so, eo, resultTypeParam.symbol)

        val initCall =
            IrInstanceInitializerCallImpl(
                startOffset = so,
                endOffset = eo,
                classSymbol = handleClass.symbol,
                type = pluginContext.irBuiltIns.unitType,
            )

        constructor.body =
            pluginContext.irFactory.createBlockBody(so, eo, listOf(delegating, initCall))
    }

    private fun findHandleClass(workflowClass: IrClass): IrClass? =
        workflowClass.declarations
            .filterIsInstance<IrClass>()
            .firstOrNull { it.name.asString() == "Handle" }

    private fun IrClass.primaryConstructorOrNull(): IrConstructor? =
        declarations.filterIsInstance<IrConstructor>().firstOrNull { it.isPrimary }

    private fun IrSimpleFunction.returnTypeResultArg(): IrType? {
        // `start` / `handle` return `<UserClass>.Handle<R>`. Extract R directly from the
        // function's return type — its first type argument is the substituted R (e.g. `String`
        // for a workflow returning `String`). Walking Handle's *declared* superTypes returns
        // the unsubstituted type parameter, which is useless at IR-construction time (typeFromClass
        // requires a real IrClassSymbol, not an IrTypeParameter).
        val rt = returnType as? IrSimpleType ?: return null
        return (rt.arguments.firstOrNull() as? IrTypeProjection)?.type
    }

    /** Read the user-supplied `name` argument from `@Workflow("name")`. */
    private fun readWorkflowTypeName(klass: IrClass): String? {
        for (annotation in klass.annotations) {
            if (annotation.symbol.owner.parentClassOrNull
                    ?.kotlinFqName != workflowAnnotationFqn
            ) {
                continue
            }
            val firstArg = annotation.arguments.firstOrNull() ?: continue
            val constValue = (firstArg as? IrConst)?.value as? String
            if (!constValue.isNullOrBlank()) return constValue
        }
        return null
    }

    private fun typeFromClassCall(
        forType: IrType,
        start: Int,
        end: Int,
    ): IrExpression {
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

    private fun classRefOf(
        type: IrType,
        start: Int,
        end: Int,
    ): IrExpression? {
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

    private fun newWorkflowIdCall(
        start: Int,
        end: Int,
    ): IrExpression =
        IrCallImpl(
            startOffset = start,
            endOffset = end,
            type = pluginContext.irBuiltIns.stringType,
            symbol = newWorkflowIdFn,
            typeArgumentsCount = 0,
        )

    private fun stringConst(
        value: String,
        start: Int,
        end: Int,
    ): IrExpression = IrConstImpl(start, end, pluginContext.irBuiltIns.stringType, IrConstKind.String, value)

    private fun nullExpr(
        start: Int,
        end: Int,
    ): IrExpression = IrConstImpl(start, end, pluginContext.irBuiltIns.anyNType, IrConstKind.Null, null)

    // ------------------------------------------------------------------------
    // Stage 9: typed @Signal / @Query / @Update wrapper bodies on Handle
    // ------------------------------------------------------------------------

    /**
     * Fill the body of a synthesised `Handle.<wrapperName>(...)` Unit-returning signal wrapper:
     * ```
     * suspend fun cancel(reason: String) {
     *     signalTyped(this.handle, "<wireName>", arrayOf(typeFromClass(String::class), reason))
     * }
     * ```
     */
    private fun fillSignalWrapper(function: IrSimpleFunction) {
        val handleClass = function.parentClassOrNull ?: return
        val workflowClass = handleClass.parentClassOrNull ?: return
        val handlerName =
            readHandlerWireName(workflowClass, function.name.asString(), signalAnnotationFqn)
                ?: function.name.asString()
        val so = function.startOffset
        val eo = function.endOffset

        val handleField = handleField(function, handleClass) ?: return

        val argsArray = buildArgTypesAndValuesArray(function, so, eo)

        val signalCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = pluginContext.irBuiltIns.unitType,
                symbol = signalTypedFn,
                typeArgumentsCount = 0,
            )
        signalCall.arguments[0] = handleField
        signalCall.arguments[1] = stringConst(handlerName, so, eo)
        signalCall.arguments[2] = argsArray

        function.body =
            pluginContext.irFactory.createBlockBody(so, eo, listOf(signalCall))
    }

    /**
     * Fill the body of a synthesised query/update wrapper:
     * ```
     * suspend fun status(): Int {
     *     return queryTyped(this.handle, "<wireName>", typeFromClass(Int::class), arrayOf(...)) as Int
     * }
     * ```
     */
    private fun fillQueryOrUpdateWrapper(
        function: IrSimpleFunction,
        runtimeFn: IrSimpleFunctionSymbol,
    ) {
        val handleClass = function.parentClassOrNull ?: return
        val workflowClass = handleClass.parentClassOrNull ?: return
        val annotationFqn =
            if (runtimeFn == queryTypedFn) queryAnnotationFqn else updateAnnotationFqn
        val handlerName =
            readHandlerWireName(workflowClass, function.name.asString(), annotationFqn)
                ?: function.name.asString()
        val so = function.startOffset
        val eo = function.endOffset

        val handleField = handleField(function, handleClass) ?: return
        val argsArray = buildArgTypesAndValuesArray(function, so, eo)
        val resultTypeExpr = typeFromClassCall(function.returnType, so, eo)

        val rtCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = pluginContext.irBuiltIns.anyNType,
                symbol = runtimeFn,
                typeArgumentsCount = 0,
            )
        rtCall.arguments[0] = handleField
        rtCall.arguments[1] = stringConst(handlerName, so, eo)
        rtCall.arguments[2] = resultTypeExpr
        rtCall.arguments[3] = argsArray

        // The runtime helper returns `Any?` — cast/coerce to the function's declared return type.
        // Use IrTypeOperatorCall(IMPLICIT_CAST) to narrow the result.
        val coerced =
            org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl(
                startOffset = so,
                endOffset = eo,
                type = function.returnType,
                operator = org.jetbrains.kotlin.ir.expressions.IrTypeOperator.IMPLICIT_CAST,
                typeOperand = function.returnType,
                argument = rtCall,
            )

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, coerced)),
            )
    }

    /**
     * Build `arrayOf(typeFromClass(P1::class), arg1, typeFromClass(P2::class), arg2, ...)`
     * for the function's regular parameters (excluding dispatch receiver).
     */
    private fun buildArgTypesAndValuesArray(
        function: IrSimpleFunction,
        start: Int,
        end: Int,
    ): IrExpression {
        val params = function.parameters.filter { it.kind == IrParameterKind.Regular }
        val anyNType = pluginContext.irBuiltIns.anyNType
        val varargElementType = anyNType
        val arrayType =
            IrSimpleTypeImpl(
                classifier = pluginContext.irBuiltIns.arrayClass,
                nullability = SimpleTypeNullability.NOT_SPECIFIED,
                arguments = listOf(makeTypeProjection(anyNType, Variance.OUT_VARIANCE)),
                annotations = emptyList(),
            )
        val varargElements = mutableListOf<org.jetbrains.kotlin.ir.expressions.IrVarargElement>()
        for (param in params) {
            varargElements += typeFromClassCall(param.type, start, end)
            varargElements += IrGetValueImpl(start, end, param.symbol)
        }
        return org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl(
            startOffset = start,
            endOffset = end,
            type = arrayType,
            varargElementType = varargElementType,
            elements = varargElements,
        )
    }

    /**
     * Returns an IR expression for `this.handle` (the inherited `WorkflowHandle` field on
     * `TypedWorkflowHandle`, accessible from any Handle subclass).
     */
    private fun handleField(
        function: IrSimpleFunction,
        handleClass: IrClass,
    ): IrExpression? {
        val dispatchReceiver = function.dispatchReceiverParameter ?: return null
        val handleProperty =
            typedHandleClass.owner.declarations
                .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrProperty>()
                .firstOrNull { it.name.asString() == "handle" }
                ?: return null
        val getter = handleProperty.getter ?: return null
        val so = function.startOffset
        val eo = function.endOffset
        val call =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = getter.returnType,
                symbol = getter.symbol,
                typeArgumentsCount = 0,
            )
        call.arguments[0] = IrGetValueImpl(so, eo, dispatchReceiver.symbol)
        return call
    }

    /**
     * Look up the wire name for a `@Signal` / `@Query` / `@Update` handler on the workflow
     * class. Falls back to the Kotlin method name if the annotation has no `name` argument.
     */
    private fun readHandlerWireName(
        workflowClass: IrClass,
        methodName: String,
        annotationFqn: FqName,
    ): String? {
        val handler =
            workflowClass.declarations
                .filterIsInstance<IrSimpleFunction>()
                .firstOrNull { it.name.asString() == methodName }
                ?: return null
        for (annotation in handler.annotations) {
            val annClass = annotation.symbol.owner.parentClassOrNull ?: continue
            if (annClass.kotlinFqName != annotationFqn) continue
            val firstArg = annotation.arguments.firstOrNull() ?: continue
            val constValue = (firstArg as? IrConst)?.value as? String
            if (!constValue.isNullOrBlank()) return constValue
        }
        return methodName
    }
}
