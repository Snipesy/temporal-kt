package com.surrealdev.temporal.compiler.ir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/**
 * Stage 8.6: lift inline `activity("name") { ... }` calls inside `@WorkflowRun` methods.
 *
 * Three passes per `@Workflow`-annotated class:
 *
 * 1. **Discover** — walk the class's `@WorkflowRun` method body for `IrCall`s to
 *    `com.surrealdev.temporal.dsl.activity` (the receiver-bound DSL stub). For each, capture the
 *    activity name string + the lambda's `IrSimpleFunction`.
 *
 * 2. **Lift + register** — for each captured activity, synthesise a top-level
 *    `fun __<WorkflowClass>_<activityName>(): R` annotated `@Activity("name")` whose body is a
 *    deep copy of the lambda's body. Add it to the workflow class's `IrFile`. Then synthesise a
 *    method `__registerInlineActivities(builder: TaskQueueBuilder)` on the workflow class's
 *    companion that calls `builder.registerActivityFunction("name", ::__<class>_<name>)` for
 *    each lifted activity.
 *
 * 3. **Rewrite** — replace each original `activity("name") { ... }` call with a call to
 *    `startActivityTyped(workflowContext, "name", arg, argType, resultType)` (the runtime helper
 *    that goes through Temporal's standard activity dispatch).
 *
 * Limitations (v1):
 * - No-arg activities only (the DSL stub doesn't support arg yet).
 * - The lifted activity function is `static` (top-level) and may not capture workflow-instance
 *   state.
 * - Default activity options: `startToCloseTimeout = 1.minute` (set by the runtime helper).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class TemporalInlineActivityLowering(
    private val pluginContext: IrPluginContext,
) {
    private val finder = pluginContext.finderForBuiltins()

    private val workflowAnnotationFqn = FqName("com.surrealdev.temporal.annotation.Workflow")
    private val workflowRunAnnotationFqn = FqName("com.surrealdev.temporal.annotation.WorkflowRun")
    private val activityAnnotationClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.annotation.Activity"))
    private val activityDslFqn = FqName("com.surrealdev.temporal.dsl.activity")

    private val workflowContextClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.workflow.WorkflowContext"))
    private val taskQueueBuilderClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.application.TaskQueueBuilder"))

    private val activityAnnotationCtor by lazy {
        finder.findConstructors(activityAnnotationClassId).firstOrNull()
            ?: error("@Activity annotation constructor not found on classpath")
    }

    private val startActivityTypedFn by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("startActivityTyped")),
            ).firstOrNull()
            ?: error("startActivityTyped runtime helper not on classpath")
    }

    private val typeFromClassFn by lazy {
        finder
            .findFunctions(
                CallableId(FqName("com.surrealdev.temporal.client"), Name.identifier("typeFromClass")),
            ).firstOrNull()
            ?: error("typeFromClass runtime helper not on classpath")
    }

    private val workflowContextClass by lazy {
        finder.findClass(workflowContextClassId)
            ?: error("WorkflowContext class not on classpath")
    }

    private val taskQueueBuilderClass by lazy {
        finder.findClass(taskQueueBuilderClassId)
            ?: error("TaskQueueBuilder class not on classpath")
    }

    private val registerActivityFn by lazy {
        taskQueueBuilderClass.functions
            .firstOrNull { it.owner.name.asString() == "registerActivityFunction" }
            ?: error("TaskQueueBuilder.registerActivityFunction not found")
    }

    private val kFunctionClass by lazy {
        finder.findClass(ClassId.topLevel(FqName("kotlin.reflect.KFunction")))
            ?: error("kotlin.reflect.KFunction not found")
    }

    fun lower(moduleFragment: IrModuleFragment) {
        // Collect first — we cannot mutate file.declarations while walking it (CME).
        val workflowClasses = mutableListOf<IrClass>()
        moduleFragment.acceptChildrenVoid(
            object : IrVisitorVoid() {
                override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitClass(declaration: IrClass) {
                    if (declaration.hasAnnotation(workflowAnnotationFqn)) {
                        workflowClasses += declaration
                    }
                    super.visitClass(declaration)
                }
            },
        )
        for (cls in workflowClasses) processWorkflowClass(cls)
    }

    private data class InlineActivity(
        val name: String,
        val callsite: IrCall,
        val lambdaFn: IrSimpleFunction,
        val resultType: IrType,
    )

    private fun processWorkflowClass(workflowClass: IrClass) {
        val workflowRunMethod =
            workflowClass.functions.firstOrNull { it.hasAnnotation(workflowRunAnnotationFqn) }
                ?: return

        val activities = mutableListOf<InlineActivity>()
        workflowRunMethod.body?.acceptChildrenVoid(
            object : IrVisitorVoid() {
                override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitCall(expression: IrCall) {
                    val fqn = expression.symbol.owner.kotlinFqName
                    if (fqn == activityDslFqn) {
                        captureActivity(expression)?.let { activities += it }
                    }
                    super.visitCall(expression)
                }

                private fun captureActivity(call: IrCall): InlineActivity? {
                    // Args: [extension receiver (WorkflowContext), name, body]
                    val nameArg =
                        call.arguments.firstOrNull { it is IrConst && it.kind == IrConstKind.String }
                            as? IrConst ?: return null
                    val name = nameArg.value as? String ?: return null
                    val bodyArg = call.arguments.firstNotNullOfOrNull { it as? IrFunctionExpression } ?: return null
                    val lambda = bodyArg.function
                    return InlineActivity(name, call, lambda, lambda.returnType)
                }
            },
        )

        if (activities.isEmpty()) return

        val workflowFile = workflowClass.fileOrNull ?: return
        val workflowSimpleName = workflowClass.name.asString()

        // Pass 2a: lift each lambda body to a top-level @Activity function.
        val liftedFns = mutableMapOf<String, IrSimpleFunctionSymbol>()
        for (activity in activities) {
            val lifted = liftActivity(workflowFile, workflowSimpleName, activity)
            workflowFile.declarations.add(lifted)
            liftedFns[activity.name] = lifted.symbol
            // Make the function visible to Kotlin reflection (`::__Test_workflowActivity`),
            // not just JVM bytecode. Without this, `KFunction` references via `::funcName` fail
            // with `KotlinReflectionInternalError: ... not resolved in file class ...`.
            pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(lifted)
        }

        // Pass 2b: synthesise __registerInlineActivities on companion object.
        val companion = workflowClass.declarations.filterIsInstance<IrClass>().firstOrNull { it.isCompanion }
        if (companion != null) {
            val regMethod = buildRegistrationMethod(companion, liftedFns)
            companion.declarations.add(regMethod)
            pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(regMethod)
        }

        // Pass 3: rewrite each `activity(...)` call site.
        val rewriter = CallsiteRewriter(activities, workflowRunMethod)
        workflowRunMethod.body?.transform(rewriter, null)
    }

    /**
     * Lift the activity's lambda by **adopting** its [IrSimpleFunction] directly as the
     * top-level lifted function — rename, restamp visibility/modality, set the file as parent,
     * attach `@Activity("name")`. The lambda body's `IrReturn`s already target the lambda's
     * own symbol; renaming the lambda makes that symbol belong to a top-level function with a
     * legal name (`__<WorkflowClass>_<activityName>`), so the JVM codegen sees a local return
     * (no `$$$$$NON_LOCAL_RETURN$$$$$.<anonymous>` sentinel).
     *
     * Building a fresh function and copying the lambda body produces bytecode where
     * `returnTargetSymbol.owner.name` resolves back to the lambda's `Name.special("<anonymous>")`
     * regardless of what `IrReturnImpl` field we set — the JVM-side mapping uses the lambda's
     * original IrFunction identity. Adopting the lambda sidesteps that.
     */
    private fun liftActivity(
        file: IrFile,
        workflowName: String,
        activity: InlineActivity,
    ): IrSimpleFunction {
        val lifted = activity.lambdaFn
        lifted.name = Name.identifier("__${workflowName}_${activity.name}")
        lifted.visibility = DescriptorVisibilities.PUBLIC
        lifted.modality = Modality.FINAL
        lifted.parent = file
        lifted.origin = ORIGIN
        lifted.annotations =
            listOf(buildActivityAnnotation(activity.name, lifted.startOffset, lifted.endOffset))

        // Walk the adopted body and rewrite each `IrReturn` so it targets `lifted.symbol`
        // explicitly. Even though the lambda's IrReturns ALREADY targeted the lambda's symbol —
        // and that IrSimpleFunction object is now `lifted` — the JVM codegen's
        // `methodSignatureMapper.mapFunctionName(returnTargetSymbol.owner)` was producing
        // `<anonymous>` (the lambda's pre-rename name). Rebuilding the IrReturn nodes ensures
        // the codegen sees a fresh return whose target name resolves correctly.
        lifted.body?.transform(
            object : IrElementTransformerVoid() {
                override fun visitReturn(expression: org.jetbrains.kotlin.ir.expressions.IrReturn): IrExpression {
                    val transformed = super.visitReturn(expression)
                    if (transformed !is org.jetbrains.kotlin.ir.expressions.IrReturn) return transformed
                    return IrReturnImpl(
                        startOffset = transformed.startOffset,
                        endOffset = transformed.endOffset,
                        type = transformed.type,
                        returnTargetSymbol = lifted.symbol,
                        value = transformed.value,
                    )
                }
            },
            null,
        )

        return lifted
    }

    private fun buildActivityAnnotation(
        activityName: String,
        startOffset: Int,
        endOffset: Int,
    ): IrConstructorCallImpl {
        val annotationClassType = (activityAnnotationCtor.owner.parent as IrClass).defaultType
        val annotationCall =
            IrConstructorCallImpl(
                startOffset = startOffset,
                endOffset = endOffset,
                type = annotationClassType,
                symbol = activityAnnotationCtor,
                typeArgumentsCount = 0,
                constructorTypeArgumentsCount = 0,
            )
        annotationCall.arguments[0] = stringConst(activityName, startOffset, endOffset)
        return annotationCall
    }

    private fun buildRegistrationMethod(
        companion: IrClass,
        liftedFns: Map<String, IrSimpleFunctionSymbol>,
    ): IrSimpleFunction {
        val method =
            pluginContext.irFactory.buildFun {
                name = Name.identifier("__registerInlineActivities")
                returnType = pluginContext.irBuiltIns.unitType
                modality = Modality.FINAL
                visibility = DescriptorVisibilities.PUBLIC
                origin = ORIGIN
            }
        method.parent = companion

        // Dispatch receiver (companion-object `this`) and the explicit `builder` parameter.
        // Use `buildValueParameter` directly — `addValueParameter` (the inline IrFunction extension)
        // forcibly overwrites `kind = IrParameterKind.Regular` AFTER the builder runs, which would
        // demote the dispatch receiver to a regular parameter and produce malformed bytecode.
        val dispatchParam =
            buildValueParameter(method) {
                kind = IrParameterKind.DispatchReceiver
                name = Name.special("<this>")
                type = companion.defaultType
            }
        val builderParam =
            buildValueParameter(method) {
                kind = IrParameterKind.Regular
                name = Name.identifier("builder")
                type = taskQueueBuilderClass.defaultType
            }
        method.parameters = listOf(dispatchParam, builderParam)

        // Body: builder.registerActivityFunction("name", ::__<workflow>_<activity>) for each
        val statements =
            liftedFns.map { (activityName, fnSymbol) ->
                val call =
                    IrCallImpl(
                        startOffset = method.startOffset,
                        endOffset = method.endOffset,
                        type = pluginContext.irBuiltIns.unitType,
                        symbol = registerActivityFn,
                        typeArgumentsCount = 0,
                    )
                // arguments: [dispatch=builder, activityType, function]
                call.arguments[0] = IrGetValueImpl(method.startOffset, method.endOffset, builderParam.symbol)
                call.arguments[1] = stringConst(activityName, method.startOffset, method.endOffset)
                call.arguments[2] = functionRef(fnSymbol, method.startOffset, method.endOffset)
                call
            }
        method.body =
            pluginContext.irFactory.createBlockBody(method.startOffset, method.endOffset, statements)

        return method
    }

    private fun functionRef(
        symbol: IrSimpleFunctionSymbol,
        start: Int,
        end: Int,
    ): IrExpression {
        // KFunction0<R> for no-arg lifted activity, KFunction1<A, R> if it had an arg.
        // For v1 — no-arg only — type is KFunction<R>.
        val kFunctionType =
            IrSimpleTypeImpl(
                classifier = kFunctionClass,
                nullability = SimpleTypeNullability.NOT_SPECIFIED,
                arguments = listOf(makeTypeProjection(symbol.owner.returnType, Variance.OUT_VARIANCE)),
                annotations = emptyList(),
            )
        return IrFunctionReferenceImpl(
            startOffset = start,
            endOffset = end,
            type = kFunctionType,
            symbol = symbol,
            typeArgumentsCount = 0,
            reflectionTarget = symbol,
        )
    }

    private inner class CallsiteRewriter(
        private val activities: List<InlineActivity>,
        private val workflowRunMethod: IrSimpleFunction,
    ) : IrElementTransformerVoid() {
        override fun visitCall(expression: IrCall): IrExpression {
            val transformed = super.visitCall(expression)
            if (transformed !is IrCall) return transformed
            val match = activities.firstOrNull { it.callsite === expression } ?: return transformed
            return rewriteToStartActivity(match)
        }

        private fun rewriteToStartActivity(activity: InlineActivity): IrExpression {
            val so = activity.callsite.startOffset
            val eo = activity.callsite.endOffset

            // Locate the WorkflowContext extension receiver of the enclosing @WorkflowRun method.
            val workflowContextParam =
                workflowRunMethod.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
                    ?: error("@WorkflowRun method must have WorkflowContext extension receiver")

            val call =
                IrCallImpl(
                    startOffset = so,
                    endOffset = eo,
                    type = activity.resultType,
                    symbol = startActivityTypedFn,
                    typeArgumentsCount = 0,
                )
            // startActivityTyped(workflowContext, activityType, arg, argType, resultType, startToCloseMs=default)
            call.arguments[0] = IrGetValueImpl(so, eo, workflowContextParam.symbol)
            call.arguments[1] = stringConst(activity.name, so, eo)
            call.arguments[2] = nullExpr(so, eo) // arg — no-arg only in v1
            call.arguments[3] = nullExpr(so, eo) // argType
            call.arguments[4] = typeFromClassCall(activity.resultType, so, eo) // resultType
            // arg 5 (startToCloseMs) defaults — leave unset; default value applied by IR
            return call
        }
    }

    private fun typeFromClassCall(
        forType: IrType,
        start: Int,
        end: Int,
    ): IrExpression {
        val classifier = forType.classifierOrNull as? IrClassSymbol ?: return nullExpr(start, end)
        val kclassType =
            IrSimpleTypeImpl(
                classifier =
                    finder.findClass(ClassId.topLevel(FqName("kotlin.reflect.KClass")))
                        ?: error("KClass not found"),
                nullability = SimpleTypeNullability.NOT_SPECIFIED,
                arguments = listOf(makeTypeProjection(classifier.starProjectedType, Variance.INVARIANT)),
                annotations = emptyList(),
            )
        val classRef =
            IrClassReferenceImpl(
                startOffset = start,
                endOffset = end,
                type = kclassType,
                symbol = classifier,
                classType = classifier.starProjectedType,
            )
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

    private fun stringConst(
        value: String,
        start: Int,
        end: Int,
    ): IrExpression = IrConstImpl(start, end, pluginContext.irBuiltIns.stringType, IrConstKind.String, value)

    private fun nullExpr(
        start: Int,
        end: Int,
    ): IrExpression = IrConstImpl(start, end, pluginContext.irBuiltIns.anyNType, IrConstKind.Null, null)

    private val ORIGIN: IrDeclarationOrigin = IrDeclarationOrigin.GeneratedByPlugin(InlineActivityKey)

    private object InlineActivityKey : GeneratedDeclarationKey()
}

private val IrClass.fileOrNull: IrFile?
    get() {
        var p: Any? = this
        while (p != null) {
            if (p is IrFile) return p
            p = (p as? org.jetbrains.kotlin.ir.declarations.IrDeclaration)?.parent
        }
        return null
    }
