package com.surrealdev.temporal.compiler.ir

import com.surrealdev.temporal.compiler.fir.TemporalChildCompanionKey
import com.surrealdev.temporal.compiler.fir.TemporalChildSignalKey
import com.surrealdev.temporal.compiler.fir.TemporalCompanionKey
import com.surrealdev.temporal.compiler.fir.TemporalExternalCompanionKey
import com.surrealdev.temporal.compiler.fir.TemporalExternalSignalKey
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
import org.jetbrains.kotlin.ir.expressions.IrCall
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
import org.jetbrains.kotlin.ir.types.typeWith
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

    private val typedResultImplFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("typedResultImpl")))
            .firstOrNull()
            ?: error("typedResultImpl runtime helper not on classpath")
    }

    private val typedChildResultImplFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("typedChildResultImpl")),
            ).firstOrNull()
            ?: error("typedChildResultImpl runtime helper not on classpath")
    }

    private val typedChildAwaitStartImplFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("typedChildAwaitStartImpl")),
            ).firstOrNull()
            ?: error("typedChildAwaitStartImpl runtime helper not on classpath")
    }

    private val typedChildCancelImplFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("typedChildCancelImpl")),
            ).firstOrNull()
            ?: error("typedChildCancelImpl runtime helper not on classpath")
    }

    /** `kotlin.time.Duration.Companion.INFINITE` — used as the default value of `result(timeout)`. */
    private val durationInfiniteGetter: IrSimpleFunctionSymbol by lazy {
        val prop =
            durationCompanionClass.owner.declarations
                .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrProperty>()
                .firstOrNull { it.name.asString() == "INFINITE" }
                ?: error("kotlin.time.Duration.Companion.INFINITE not found — stdlib mismatch")
        prop.getter?.symbol
            ?: error("kotlin.time.Duration.Companion.INFINITE has no getter — stdlib mismatch")
    }

    private val durationCompanionClass: IrClassSymbol by lazy {
        finder.findClass(ClassId(FqName("kotlin.time"), FqName("Duration.Companion"), false))
            ?: error("kotlin.time.Duration.Companion not found — stdlib may be stripped")
    }

    private val anyClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("kotlin.Any")))
            ?: error("kotlin.Any not found")
    }

    // Child workflow runtime symbols.

    private val typedChildHandleClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("com.surrealdev.temporal.workflow.TypedChildWorkflowHandle")))
            ?: error("TypedChildWorkflowHandle class not on classpath — :core dependency missing")
    }

    private val childWorkflowOptionsClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("com.surrealdev.temporal.workflow.ChildWorkflowOptions")))
            ?: error("ChildWorkflowOptions class not on classpath")
    }

    private val childWorkflowOptionsCtor: IrConstructorSymbol by lazy {
        childWorkflowOptionsClass.constructors
            .firstOrNull { ctor ->
                val regularParams = ctor.owner.parameters.filter { p -> p.kind == IrParameterKind.Regular }
                regularParams.isEmpty() || regularParams.all { it.defaultValue != null }
            }
            ?: error("ChildWorkflowOptions has no usable no-arg constructor")
    }

    private val startChildWorkflowGetHandleFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("startChildWorkflowGetHandle")),
            ).firstOrNull()
            ?: error("startChildWorkflowGetHandle runtime helper not on classpath")
    }

    private val signalChildTypedFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("signalChildTyped")),
            ).firstOrNull()
            ?: error("signalChildTyped runtime helper not on classpath")
    }

    // ---- Stage 17.6: external workflow surface ------------------------------------------------

    private val externalWorkflowHandleClass: IrClassSymbol by lazy {
        finder.findClass(ClassId.topLevel(FqName("com.surrealdev.temporal.workflow.ExternalWorkflowHandle")))
            ?: error("ExternalWorkflowHandle class not on classpath — :core dependency missing")
    }

    private val externalHandleGetFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("externalHandleGet")),
            ).firstOrNull()
            ?: error("externalHandleGet runtime helper not on classpath")
    }

    private val signalExternalTypedFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("signalExternalTyped")),
            ).firstOrNull()
            ?: error("signalExternalTyped runtime helper not on classpath")
    }

    private val typedExternalCancelImplFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("typedExternalCancelImpl")),
            ).firstOrNull()
            ?: error("typedExternalCancelImpl runtime helper not on classpath")
    }

    /**
     * Top-level `suspend fun workflow(): WorkflowContext` in `com.surrealdev.temporal.workflow`,
     * defined in `WorkflowContextExtensions.kt`. Returns the current workflow context from
     * `coroutineContext[WorkflowContext]`; throws `IllegalStateException` if called outside a
     * workflow execution. The IR body filler emits a call to this from `startChild`'s body to
     * keep call sites context-free (`Foo.startChild(arg)` instead of `Foo.startChild(this, arg)`).
     */
    private val workflowGetterFn: IrSimpleFunctionSymbol by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.workflow"), Name.identifier("workflow")),
            ).firstOrNull {
                it.owner.parameters.isEmpty() ||
                    it.owner.parameters.all { p -> p.kind != IrParameterKind.Regular }
            }
            ?: error("workflow() helper not on classpath")
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
                TemporalChildCompanionKey -> fillChildFunctionIfMatch(declaration)
                TemporalChildSignalKey -> fillChildSignalWrapper(declaration)
                TemporalExternalCompanionKey -> fillExternalFunctionIfMatch(declaration)
                TemporalExternalSignalKey -> fillExternalSignalWrapper(declaration)
            }
            return super.visitSimpleFunction(declaration)
        }

        override fun visitConstructor(declaration: IrConstructor): IrStatement {
            val origin = declaration.origin
            val pluginKey = (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey
            when (pluginKey) {
                TemporalCompanionKey -> fillConstructorIfMatch(declaration)
                TemporalChildCompanionKey -> fillChildConstructorIfMatch(declaration)
                TemporalExternalCompanionKey -> fillExternalConstructorIfMatch(declaration)
            }
            return super.visitConstructor(declaration)
        }
    }

    private val workflowRunAnnotationFqn = FqName("com.surrealdev.temporal.annotation.WorkflowRun")

    private fun fillFunctionIfMatch(function: IrSimpleFunction) {
        regularParam(function, "options")?.let { fillOptionsDefault(it) }
        regularParam(function, "timeout")?.let { fillTimeoutDefault(it) }
        when (function.name.asString()) {
            "start" -> fillStart(function)
            "handle" -> fillHandleMethod(function)
            "result" -> fillResultOverride(function)
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

    private fun fillChildFunctionIfMatch(function: IrSimpleFunction) {
        regularParam(function, "options")?.let { fillChildOptionsDefault(it) }
        regularParam(function, "reason")?.let { fillChildCancelReasonDefault(it) }
        when (function.name.asString()) {
            "startChild" -> fillStartChild(function)
            "result" -> fillChildResultOverride(function)
            "awaitStart" -> fillChildAwaitStartOverride(function)
            "cancel" -> fillChildCancelOverride(function)
        }
    }

    private fun fillChildConstructorIfMatch(constructor: IrConstructor) {
        val parent = constructor.parentClassOrNull ?: return
        if (parent.name.asString() != "ChildHandle") return
        if (parent.parentClassOrNull?.let(::isWorkflowClass) != true) return
        fillChildHandleConstructor(constructor, parent)
    }

    private fun fillExternalFunctionIfMatch(function: IrSimpleFunction) {
        // ExternalHandle's `cancel(reason)` defaults to "" (matches the interface default).
        regularParam(function, "reason")?.let { fillExternalCancelReasonDefault(it) }
        regularParam(function, "runId")?.let { fillRunIdNullDefault(it) }
        when (function.name.asString()) {
            "cancel" -> fillExternalCancelOverride(function)
            "external" -> fillExternal(function)
        }
    }

    private fun fillRunIdNullDefault(param: IrValueParameter) {
        if (param.defaultValue != null) return
        val so = param.startOffset
        val eo = param.endOffset
        param.defaultValue =
            pluginContext.irFactory.createExpressionBody(so, eo, nullExpr(so, eo))
    }

    /**
     * Body of `Foo.Companion.external(workflowId, runId)`:
     * `Foo.ExternalHandle(externalHandleGet(workflow(), workflowId, runId))`.
     *
     * Reaches the ambient [WorkflowContext] via the `workflow()` runtime helper, mirrors
     * [fillStartChild]. Only callable from inside a `@WorkflowRun` method body — `workflow()`
     * throws if there's no current workflow context.
     */
    private fun fillExternal(function: IrSimpleFunction) {
        val parentCompanion = function.parentClassOrNull ?: return
        val workflowClass = parentCompanion.parentClassOrNull ?: return
        val externalHandleClass = findExternalHandleClass(workflowClass) ?: return
        val externalHandleCtor = externalHandleClass.primaryConstructorOrNull() ?: return

        val workflowIdParam = regularParam(function, "workflowId") ?: return
        val runIdParam = regularParam(function, "runId") ?: return

        val so = function.startOffset
        val eo = function.endOffset

        val workflowCtxCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = workflowGetterFn.owner.returnType,
                symbol = workflowGetterFn,
                typeArgumentsCount = 0,
            )

        val getCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = externalHandleGetFn.owner.returnType,
                symbol = externalHandleGetFn,
                typeArgumentsCount = 0,
            )
        getCall.arguments[0] = workflowCtxCall
        getCall.arguments[1] = IrGetValueImpl(so, eo, workflowIdParam.symbol)
        getCall.arguments[2] = IrGetValueImpl(so, eo, runIdParam.symbol)

        val ctorCall =
            TemporalIrApi.newConstructorCall(
                startOffset = so,
                endOffset = eo,
                type = externalHandleClass.defaultType,
                symbol = externalHandleCtor.symbol,
                typeArgumentsCount = 0,
            )
        ctorCall.arguments[0] = getCall

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, ctorCall)),
            )
    }

    private fun fillExternalConstructorIfMatch(constructor: IrConstructor) {
        val parent = constructor.parentClassOrNull ?: return
        if (parent.name.asString() != "ExternalHandle") return
        if (parent.parentClassOrNull?.let(::isWorkflowClass) != true) return
        fillExternalHandleConstructor(constructor, parent)
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
     * Build constructor body for `<UserClass>.Handle`:
     * ```
     * super<UserClass>()    // user class is auto-opened by status transformer
     * <instance-init>       // runs property initializers, which read ctor params
     * ```
     * The user's @Workflow class must have a no-arg (or all-defaulted) constructor — this is
     * already required by Temporal's worker-side workflow instantiation. Property initializers
     * for `handle` and `resultType` are filled in [fillBackingPropertyInitializers].
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

        fillBackingPropertyInitializers(handleClass, handleParam, resultTypeParam)

        val so = constructor.startOffset
        val eo = constructor.endOffset

        val delegating = anyDelegatingCall(so, eo)

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

    /** `super<Any>()` — Handle/ChildHandle/ExternalHandle inherit only from interfaces. */
    private fun anyDelegatingCall(
        startOffset: Int,
        endOffset: Int,
    ): IrDelegatingConstructorCallImpl {
        val anyCtor =
            anyClass.constructors.firstOrNull()
                ?: error("kotlin.Any has no constructor")
        return IrDelegatingConstructorCallImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = pluginContext.irBuiltIns.unitType,
            symbol = anyCtor,
            typeArgumentsCount = 0,
        )
    }

    /**
     * Set the initializer expression on the synthesised `handle` and `resultType` properties so
     * the constructor's instance-init phase assigns them from the matching constructor parameters.
     * Both properties were created by FIR with `withGeneratedDefaultInitializer()`, leaving the
     * IR layer to provide the actual initializer.
     */
    private fun fillBackingPropertyInitializers(
        handleClass: IrClass,
        handleParam: IrValueParameter,
        resultTypeParam: IrValueParameter,
    ) {
        val handleProp =
            handleClass.declarations
                .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrProperty>()
                .firstOrNull { it.name.asString() == "handle" }
        val resultTypeProp =
            handleClass.declarations
                .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrProperty>()
                .firstOrNull { it.name.asString() == "resultType" }
        handleProp?.let { setPropertyInitializerToParam(it, handleParam) }
        resultTypeProp?.let { setPropertyInitializerToParam(it, resultTypeParam) }
    }

    private fun setPropertyInitializerToParam(
        property: org.jetbrains.kotlin.ir.declarations.IrProperty,
        param: IrValueParameter,
    ) {
        val field = property.backingField ?: return
        val so = field.startOffset
        val eo = field.endOffset
        field.initializer =
            pluginContext.irFactory.createExpressionBody(so, eo, IrGetValueImpl(so, eo, param.symbol))
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

    /** Default value `ChildWorkflowOptions()` for the `options` parameter on `startChild`. */
    private fun fillChildOptionsDefault(param: IrValueParameter) {
        val ctorCall =
            TemporalIrApi.newConstructorCall(
                startOffset = param.startOffset,
                endOffset = param.endOffset,
                type = childWorkflowOptionsClass.defaultType,
                symbol = childWorkflowOptionsCtor,
            )
        param.defaultValue =
            pluginContext.irFactory.createExpressionBody(param.startOffset, param.endOffset, ctorCall)
    }

    /**
     * Fill the body of `<UserClass>.Companion.startChild(arg, options)`. Calls
     * `workflow()` to obtain the current `WorkflowContext` from coroutineContext, then delegates
     * to `startChildWorkflowGetHandle`. Result wrapped in `<UserClass>.ChildHandle`.
     */
    private fun fillStartChild(function: IrSimpleFunction) {
        val parentCompanion = function.parentClassOrNull ?: return
        val workflowClass = parentCompanion.parentClassOrNull ?: return
        val childHandleClass = findChildHandleClass(workflowClass) ?: return
        val childHandleCtor = childHandleClass.primaryConstructorOrNull() ?: return
        val workflowTypeName = readWorkflowTypeName(workflowClass) ?: workflowClass.name.asString()

        val argParam = regularParam(function, "arg")
        val optionsParam = regularParam(function, "options") ?: return

        val resultType = function.returnTypeResultArg() ?: pluginContext.irBuiltIns.anyNType
        val so = function.startOffset
        val eo = function.endOffset

        val workflowCtxCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = workflowGetterFn.owner.returnType,
                symbol = workflowGetterFn,
                typeArgumentsCount = 0,
            )

        val startCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = startChildWorkflowGetHandleFn.owner.returnType,
                symbol = startChildWorkflowGetHandleFn,
                typeArgumentsCount = 0,
            )
        startCall.arguments[0] = workflowCtxCall
        startCall.arguments[1] = stringConst(workflowTypeName, so, eo)
        startCall.arguments[2] = argParam?.let { IrGetValueImpl(so, eo, it.symbol) } ?: nullExpr(so, eo)
        startCall.arguments[3] = argParam?.let { typeFromClassCall(it.type, so, eo) } ?: nullExpr(so, eo)
        startCall.arguments[4] = IrGetValueImpl(so, eo, optionsParam.symbol)

        val ctorCall =
            buildChildHandleCtorCall(childHandleCtor, function.returnType, resultType, startCall, so, eo)

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, ctorCall)),
            )
    }

    /**
     * Build `<UserClass>.ChildHandle<R>(handleArg, typeFromClass(R::class))`. Mirror of
     * [buildHandleCtorCall] for the child variant.
     */
    private fun buildChildHandleCtorCall(
        childHandleCtor: IrConstructor,
        childHandleType: IrType,
        resultIrType: IrType,
        handleArg: IrExpression,
        startOffset: Int,
        endOffset: Int,
    ): IrConstructorCall {
        val ctorCall =
            TemporalIrApi.newConstructorCall(
                startOffset = startOffset,
                endOffset = endOffset,
                type = childHandleType,
                symbol = childHandleCtor.symbol,
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
     * super<TypedChildWorkflowHandle>(handle, resultType)
     * <instance-init>
     * ```
     */
    private fun fillChildHandleConstructor(
        constructor: IrConstructor,
        childHandleClass: IrClass,
    ) {
        val handleParam =
            constructor.parameters.firstOrNull { it.kind == IrParameterKind.Regular && it.name.asString() == "handle" }
                ?: return
        val resultTypeParam =
            constructor.parameters.firstOrNull {
                it.kind == IrParameterKind.Regular && it.name.asString() == "resultType"
            }
                ?: return

        fillBackingPropertyInitializers(childHandleClass, handleParam, resultTypeParam)

        val so = constructor.startOffset
        val eo = constructor.endOffset

        val delegating = anyDelegatingCall(so, eo)
        val initCall =
            IrInstanceInitializerCallImpl(
                startOffset = so,
                endOffset = eo,
                classSymbol = childHandleClass.symbol,
                type = pluginContext.irBuiltIns.unitType,
            )

        constructor.body =
            pluginContext.irFactory.createBlockBody(so, eo, listOf(delegating, initCall))
    }

    /**
     * Fill the body of a synthesised `ChildHandle.<wrapperName>(...)` Unit-returning signal
     * wrapper. Mirror of [fillSignalWrapper] but routes through `signalChildTyped` (which
     * dispatches on the child's `signalWithPayloads` API on the workflow side).
     */
    private fun fillChildSignalWrapper(function: IrSimpleFunction) {
        val childHandleClass = function.parentClassOrNull ?: return
        val workflowClass = childHandleClass.parentClassOrNull ?: return
        val handlerName =
            readHandlerWireName(workflowClass, function.name.asString(), signalAnnotationFqn)
                ?: function.name.asString()
        val so = function.startOffset
        val eo = function.endOffset

        val handleField = childHandleField(function, childHandleClass) ?: return
        val argsArray = buildArgTypesAndValuesArray(function, so, eo)

        val signalCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = pluginContext.irBuiltIns.unitType,
                symbol = signalChildTypedFn,
                typeArgumentsCount = 0,
            )
        signalCall.arguments[0] = handleField
        signalCall.arguments[1] = stringConst(handlerName, so, eo)
        signalCall.arguments[2] = argsArray

        function.body =
            pluginContext.irFactory.createBlockBody(so, eo, listOf(signalCall))
    }

    /**
     * Returns an IR expression for `this.handle` on a `ChildHandle` instance — the `handle`
     * property is inherited from `TypedChildWorkflowHandle`. Mirror of [handleField].
     */
    private fun childHandleField(
        function: IrSimpleFunction,
        childHandleClass: IrClass,
    ): IrExpression? {
        val dispatchReceiver = function.dispatchReceiverParameter ?: return null
        val handleProperty =
            typedChildHandleClass.owner.declarations
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

    private fun findChildHandleClass(workflowClass: IrClass): IrClass? =
        workflowClass.declarations
            .filterIsInstance<IrClass>()
            .firstOrNull { it.name.asString() == "ChildHandle" }

    private fun findExternalHandleClass(workflowClass: IrClass): IrClass? =
        workflowClass.declarations
            .filterIsInstance<IrClass>()
            .firstOrNull { it.name.asString() == "ExternalHandle" }

    /**
     * Reference `this.resultType` via the synthesised property's getter.
     */
    private fun resultTypeField(
        function: IrSimpleFunction,
        handleClass: IrClass,
    ): IrExpression? {
        val dispatchReceiver = function.dispatchReceiverParameter ?: return null
        val resultTypeProperty =
            handleClass.declarations
                .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrProperty>()
                .firstOrNull { it.name.asString() == "resultType" }
                ?: return null
        val getter = resultTypeProperty.getter ?: return null
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

    /** Body of `Handle.result(timeout)`: `typedResultImpl(this.handle, this.resultType, timeout)`. */
    private fun fillResultOverride(function: IrSimpleFunction) {
        val handleClass = function.parentClassOrNull ?: return
        if (handleClass.name.asString() != "Handle") return
        val workflowClass = handleClass.parentClassOrNull ?: return
        if (!isWorkflowClass(workflowClass)) return
        val rType = function.returnType
        val timeoutParam = regularParam(function, "timeout") ?: return
        val handleExpr = handleField(function, handleClass) ?: return
        val resultTypeExpr = resultTypeField(function, handleClass) ?: return
        val so = function.startOffset
        val eo = function.endOffset

        val call =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = rType,
                symbol = typedResultImplFn,
                typeArgumentsCount = 1,
            )
        call.typeArguments[0] = rType
        call.arguments[0] = handleExpr
        call.arguments[1] = resultTypeExpr
        call.arguments[2] = IrGetValueImpl(so, eo, timeoutParam.symbol)

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, call)),
            )
    }

    /** Body of `ChildHandle.result()`: `typedChildResultImpl(this.handle, this.resultType)`. */
    private fun fillChildResultOverride(function: IrSimpleFunction) {
        val childHandleClass = function.parentClassOrNull ?: return
        if (childHandleClass.name.asString() != "ChildHandle") return
        val workflowClass = childHandleClass.parentClassOrNull ?: return
        if (!isWorkflowClass(workflowClass)) return
        val rType = function.returnType
        val handleExpr = childHandleField(function, childHandleClass) ?: return
        val resultTypeExpr = resultTypeField(function, childHandleClass) ?: return
        val so = function.startOffset
        val eo = function.endOffset

        val call =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = rType,
                symbol = typedChildResultImplFn,
                typeArgumentsCount = 1,
            )
        call.typeArguments[0] = rType
        call.arguments[0] = handleExpr
        call.arguments[1] = resultTypeExpr

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, call)),
            )
    }

    /** Body of `ChildHandle.awaitStart()`: `typedChildAwaitStartImpl(this.handle)`. */
    private fun fillChildAwaitStartOverride(function: IrSimpleFunction) {
        val childHandleClass = function.parentClassOrNull ?: return
        if (childHandleClass.name.asString() != "ChildHandle") return
        val workflowClass = childHandleClass.parentClassOrNull ?: return
        if (!isWorkflowClass(workflowClass)) return
        val handleExpr = childHandleField(function, childHandleClass) ?: return
        val so = function.startOffset
        val eo = function.endOffset

        val call =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = typedChildAwaitStartImplFn.owner.returnType,
                symbol = typedChildAwaitStartImplFn,
                typeArgumentsCount = 0,
            )
        call.arguments[0] = handleExpr

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(IrReturnImpl(so, eo, pluginContext.irBuiltIns.nothingType, function.symbol, call)),
            )
    }

    /** Body of `ChildHandle.cancel(reason)`: `typedChildCancelImpl(this.handle, reason)`. */
    private fun fillChildCancelOverride(function: IrSimpleFunction) {
        val childHandleClass = function.parentClassOrNull ?: return
        if (childHandleClass.name.asString() != "ChildHandle") return
        val workflowClass = childHandleClass.parentClassOrNull ?: return
        if (!isWorkflowClass(workflowClass)) return
        val reasonParam = regularParam(function, "reason") ?: return
        val handleExpr = childHandleField(function, childHandleClass) ?: return
        val so = function.startOffset
        val eo = function.endOffset

        val call =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = pluginContext.irBuiltIns.unitType,
                symbol = typedChildCancelImplFn,
                typeArgumentsCount = 0,
            )
        call.arguments[0] = handleExpr
        call.arguments[1] = IrGetValueImpl(so, eo, reasonParam.symbol)

        function.body =
            pluginContext.irFactory.createBlockBody(
                so,
                eo,
                listOf(call),
            )
    }

    /** Default value `kotlin.time.Duration.INFINITE` for the `timeout` parameter on `result`. */
    private fun fillTimeoutDefault(param: IrValueParameter) {
        val getter = durationInfiniteGetter
        val companionClass = durationCompanionClass
        val so = param.startOffset
        val eo = param.endOffset
        val getCompanion =
            org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl(
                startOffset = so,
                endOffset = eo,
                type = companionClass.defaultType,
                symbol = companionClass,
            )
        val call =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = getter.owner.returnType,
                symbol = getter,
                typeArgumentsCount = 0,
            )
        call.arguments[0] = getCompanion
        param.defaultValue =
            pluginContext.irFactory.createExpressionBody(so, eo, call)
    }

    /** Default value `"Cancelled by parent workflow"` for the `reason` parameter on `cancel`. */
    private fun fillChildCancelReasonDefault(param: IrValueParameter) {
        val so = param.startOffset
        val eo = param.endOffset
        param.defaultValue =
            pluginContext.irFactory.createExpressionBody(so, eo, stringConst("Cancelled by parent workflow", so, eo))
    }

    // ---- Stage 17.6: ExternalHandle fillers ---------------------------------------------------

    /**
     * Constructor body for `<UserClass>.ExternalHandle`:
     * ```
     * super<UserClass>()
     * <instance-init>   // runs property initializer for `handle` (only)
     * ```
     */
    private fun fillExternalHandleConstructor(
        constructor: IrConstructor,
        externalHandleClass: IrClass,
    ) {
        val handleParam =
            constructor.parameters.firstOrNull {
                it.kind == IrParameterKind.Regular && it.name.asString() == "handle"
            } ?: return

        // Only `handle` — no resultType on ExternalHandle.
        val handleProp =
            externalHandleClass.declarations
                .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrProperty>()
                .firstOrNull { it.name.asString() == "handle" }
        handleProp?.let { setPropertyInitializerToParam(it, handleParam) }

        val so = constructor.startOffset
        val eo = constructor.endOffset

        val delegating = anyDelegatingCall(so, eo)
        val initCall =
            IrInstanceInitializerCallImpl(
                startOffset = so,
                endOffset = eo,
                classSymbol = externalHandleClass.symbol,
                type = pluginContext.irBuiltIns.unitType,
            )
        constructor.body =
            pluginContext.irFactory.createBlockBody(so, eo, listOf(delegating, initCall))
    }

    /** Body of `ExternalHandle.cancel(reason)`: `typedExternalCancelImpl(this.handle, reason)`. Suspend. */
    private fun fillExternalCancelOverride(function: IrSimpleFunction) {
        val externalHandleClass = function.parentClassOrNull ?: return
        if (externalHandleClass.name.asString() != "ExternalHandle") return
        val workflowClass = externalHandleClass.parentClassOrNull ?: return
        if (!isWorkflowClass(workflowClass)) return
        val reasonParam = regularParam(function, "reason") ?: return
        val handleExpr = externalHandleField(function, externalHandleClass) ?: return
        val so = function.startOffset
        val eo = function.endOffset

        val call =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = pluginContext.irBuiltIns.unitType,
                symbol = typedExternalCancelImplFn,
                typeArgumentsCount = 0,
            )
        call.arguments[0] = handleExpr
        call.arguments[1] = IrGetValueImpl(so, eo, reasonParam.symbol)

        function.body =
            pluginContext.irFactory.createBlockBody(so, eo, listOf(call))
    }

    /** Body of `ExternalHandle.<signal>(...)`: `signalExternalTyped(this.handle, "<wireName>", argsArray)`. */
    private fun fillExternalSignalWrapper(function: IrSimpleFunction) {
        val externalHandleClass = function.parentClassOrNull ?: return
        val workflowClass = externalHandleClass.parentClassOrNull ?: return
        val handlerName =
            readHandlerWireName(workflowClass, function.name.asString(), signalAnnotationFqn)
                ?: function.name.asString()
        val so = function.startOffset
        val eo = function.endOffset

        val handleField = externalHandleField(function, externalHandleClass) ?: return
        val argsArray = buildArgTypesAndValuesArray(function, so, eo)

        val signalCall =
            IrCallImpl(
                startOffset = so,
                endOffset = eo,
                type = pluginContext.irBuiltIns.unitType,
                symbol = signalExternalTypedFn,
                typeArgumentsCount = 0,
            )
        signalCall.arguments[0] = handleField
        signalCall.arguments[1] = stringConst(handlerName, so, eo)
        signalCall.arguments[2] = argsArray

        function.body =
            pluginContext.irFactory.createBlockBody(so, eo, listOf(signalCall))
    }

    /** Default value `""` for the `reason` parameter on `ExternalHandle.cancel`. */
    private fun fillExternalCancelReasonDefault(param: IrValueParameter) {
        val so = param.startOffset
        val eo = param.endOffset
        param.defaultValue =
            pluginContext.irFactory.createExpressionBody(so, eo, stringConst("", so, eo))
    }

    /** Reference `this.handle` (the `ExternalWorkflowHandle` property) via the synthesised getter. */
    private fun externalHandleField(
        function: IrSimpleFunction,
        externalHandleClass: IrClass,
    ): IrExpression? {
        val dispatchReceiver = function.dispatchReceiverParameter ?: return null
        val handleProperty =
            externalHandleClass.declarations
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

}
