package com.surrealdev.temporal.compiler.fir

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getAnnotationWithResolvedArgumentsByClassId
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.scopes.processAllFunctions
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance

/**
 * Augments every `@com.surrealdev.temporal.annotation.Workflow`-annotated class with:
 *
 * 1. A synthesised `companion object` exposing typed `start(...)` and `handle(...)` helpers.
 * 2. A nested `Handle` class extending [TypedWorkflowHandle][com.surrealdev.temporal.client.TypedWorkflowHandle]
 *    with an `@PublishedApi internal` constructor — only callable from generated code.
 *
 * Shape:
 *
 * ```
 * @Workflow("Foo")
 * class Foo {
 *     @WorkflowRun suspend fun WorkflowContext.run(arg: A): R = ...
 *
 *     // synthesised:
 *     class Handle @PublishedApi internal constructor(
 *         handle: WorkflowHandle,
 *         resultType: KType,
 *     ) : TypedWorkflowHandle<R>(handle, resultType)
 *
 *     companion object {
 *         suspend fun start(client, taskQueue, arg, options): Foo.Handle
 *         fun handle(client, workflowId, runId): Foo.Handle
 *     }
 * }
 * ```
 *
 * Bodies are stubbed at FIR (`withGeneratedDefaultBody()`) and filled by the IR body filler.
 *
 * **Companion handling:** if the user wrote a `companion object`, the generator augments it
 * (adds `start`/`handle`) without crashing the compiler's duplicate-companion check.
 *
 * **Phase ordering:**
 * - [getNestedClassifiersNames] runs at COMPANION_GENERATION — uses `predicateBasedProvider` (safe)
 *   not `hasAnnotation` (which forward-resolves to TYPES and crashes LL-FIR's lazy contract).
 * - [getCallableNamesForClass] may run at SUPERTYPES — return fixed names; defer type reading.
 * - [generateFunctions] runs at STATUS (past TYPES) — safe to read user method types.
 */
class TemporalFirCompanionGenerator(
    session: FirSession,
) : FirDeclarationGenerationExtension(session) {
    private val workflowAnnotationClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.annotation.Workflow"))
    private val workflowRunAnnotationClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.annotation.WorkflowRun"))
    private val signalAnnotationClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.annotation.Signal"))
    private val queryAnnotationClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.annotation.Query"))
    private val updateAnnotationClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.annotation.Update"))
    private val temporalClientClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.client.TemporalClient"))
    private val workflowStartOptionsClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.client.WorkflowStartOptions"))
    private val typedWorkflowHandleClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.client.TypedWorkflowHandle"))
    private val workflowHandleClassId =
        ClassId.topLevel(FqName("com.surrealdev.temporal.client.WorkflowHandle"))
    private val kTypeClassId =
        ClassId.topLevel(FqName("kotlin.reflect.KType"))

    private val stringClassId = ClassId.topLevel(FqName("kotlin.String"))

    private val handleClassName = Name.identifier("Handle")
    private val startName = Name.identifier("start")
    private val handleMethodName = Name.identifier("handle")
    private val clientParamName = Name.identifier("client")
    private val taskQueueParamName = Name.identifier("taskQueue")
    private val argParamName = Name.identifier("arg")
    private val optionsParamName = Name.identifier("options")
    private val workflowIdParamName = Name.identifier("workflowId")
    private val runIdParamName = Name.identifier("runId")
    private val handleCtorParamName = Name.identifier("handle")
    private val resultTypeCtorParamName = Name.identifier("resultType")

    private companion object {
        private val WORKFLOW_PREDICATE =
            LookupPredicate.create {
                annotated(FqName("com.surrealdev.temporal.annotation.Workflow"))
            }
    }

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(WORKFLOW_PREDICATE)
    }

    override fun getNestedClassifiersNames(
        classSymbol: FirClassSymbol<*>,
        context: NestedClassGenerationContext,
    ): Set<Name> {
        if (!session.predicateBasedProvider.matches(WORKFLOW_PREDICATE, classSymbol)) return emptySet()
        val existingNested = context.declaredScope?.getClassifierNames().orEmpty()
        return buildSet {
            // Companion: skip if user already wrote one (would crash duplicate-companion check).
            if (SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT !in existingNested) {
                add(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
            }
            // Handle: also skip if user happened to write a `class Handle` themselves (rare).
            if (handleClassName !in existingNested) {
                add(handleClassName)
            }
        }
    }

    override fun generateNestedClassLikeDeclaration(
        owner: FirClassSymbol<*>,
        name: Name,
        context: NestedClassGenerationContext,
    ): FirClassLikeSymbol<*>? {
        if (!session.predicateBasedProvider.matches(WORKFLOW_PREDICATE, owner)) return null
        return when (name) {
            SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT -> {
                createCompanionObject(owner, TemporalCompanionKey).symbol
            }

            handleClassName -> {
                createNestedClass(owner, handleClassName, TemporalCompanionKey, ClassKind.CLASS) {
                    modality = Modality.OPEN
                    // `Handle<out R> : TypedWorkflowHandle<R>` — R propagates from the workflow's
                    // `@WorkflowRun` return type when the companion's `start()` method specifies it
                    // at STATUS phase. Without this, `Handle.result()` would be locked to `Any?`.
                    typeParameter(Name.identifier("R"), Variance.OUT_VARIANCE)
                    superType { typeParams ->
                        typedWorkflowHandleClassId.createConeType(
                            session,
                            arrayOf(typeParams[0].toConeType()),
                        )
                    }
                }.symbol
            }

            else -> {
                null
            }
        }
    }

    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext,
    ): Set<Name> {
        if (isWorkflowCompanion(classSymbol)) {
            val isPluginGenerated =
                (classSymbol.origin as? FirDeclarationOrigin.Plugin)?.key == TemporalCompanionKey
            return buildSet {
                add(startName)
                add(handleMethodName)
                if (isPluginGenerated) add(SpecialNames.INIT)
            }
        }
        if (isWorkflowHandle(classSymbol)) {
            return buildSet {
                add(SpecialNames.INIT)
                // Enumerate `@Signal` / `@Query` / `@Update` methods on the outer workflow class
                // and project their Kotlin names onto Handle. This callback may run at SUPERTYPES,
                // but reading method NAMES (not parameter types) via `processAllFunctions` is safe
                // — names are available as soon as the user's class is parsed.
                val outer = classSymbol.classId.outerClassId
                val outerSymbol =
                    outer?.let { session.symbolProvider.getClassLikeSymbolByClassId(it) }
                        as? FirRegularClassSymbol
                if (outerSymbol != null) {
                    addAll(enumerateHandlerNames(outerSymbol))
                }
            }
        }
        return emptySet()
    }

    /**
     * Walk [outerSymbol]'s declared member scope for methods annotated with `@Signal`, `@Query`,
     * or `@Update` (excluding `@UpdateValidator` and `dynamic = true` handlers — see plan).
     * Returns each handler's Kotlin method name.
     */
    private fun enumerateHandlerNames(outerSymbol: FirRegularClassSymbol): Set<Name> {
        val names = mutableSetOf<Name>()
        val scope = outerSymbol.declaredMemberScope(session, memberRequiredPhase = FirResolvePhase.TYPES)
        scope.processAllFunctions { funcSymbol ->
            val annotationKind = handlerKindOf(funcSymbol) ?: return@processAllFunctions
            if (annotationKind == HandlerKind.NONE) return@processAllFunctions
            if (isDynamicHandler(funcSymbol, annotationKind)) return@processAllFunctions
            names += funcSymbol.name
        }
        return names
    }

    private enum class HandlerKind { SIGNAL, QUERY, UPDATE, NONE }

    private fun handlerKindOf(funcSymbol: FirNamedFunctionSymbol): HandlerKind? {
        return when {
            funcSymbol.hasAnnotation(signalAnnotationClassId, session) -> HandlerKind.SIGNAL
            funcSymbol.hasAnnotation(queryAnnotationClassId, session) -> HandlerKind.QUERY
            funcSymbol.hasAnnotation(updateAnnotationClassId, session) -> HandlerKind.UPDATE
            else -> null
        }
    }

    /**
     * `@Signal(dynamic = true)` etc. handlers receive the wire name as their first parameter and
     * cannot be wrapped as typed dispatchers. Read the boolean argument from the annotation.
     */
    private fun isDynamicHandler(funcSymbol: FirNamedFunctionSymbol, kind: HandlerKind): Boolean {
        val classId = annotationClassIdFor(kind) ?: return false
        // Use the resolved-arguments accessor — annotation arguments are populated into
        // `argumentMapping.mapping` during ARGUMENTS_OF_ANNOTATIONS phase, and `findArgumentByName`
        // checks both the mapping (resolved case) and raw arguments (deserialized case).
        val annotation = funcSymbol.getAnnotationWithResolvedArgumentsByClassId(classId, session)
            ?: return false
        val expr = annotation.findArgumentByName(Name.identifier("dynamic")) ?: return false
        val literal = expr as? org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
        return literal?.value as? Boolean == true
    }

    private fun annotationClassIdFor(kind: HandlerKind): ClassId? =
        when (kind) {
            HandlerKind.SIGNAL -> signalAnnotationClassId
            HandlerKind.QUERY -> queryAnnotationClassId
            HandlerKind.UPDATE -> updateAnnotationClassId
            HandlerKind.NONE -> null
        }

    private fun readBooleanNamedArg(
        annotation: org.jetbrains.kotlin.fir.expressions.FirAnnotation,
        argName: String,
    ): Boolean? {
        val call = annotation as? org.jetbrains.kotlin.fir.expressions.FirAnnotationCall ?: return null
        val expr =
            call.argumentList.arguments.firstNotNullOfOrNull { arg ->
                val named = arg as? org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
                if (named?.name?.asString() == argName) named.expression else null
            }
        val literal = expr as? org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
        return literal?.value as? Boolean
    }

    private fun readStringNamedArg(
        annotation: org.jetbrains.kotlin.fir.expressions.FirAnnotation,
        argName: String,
    ): String? {
        val call = annotation as? org.jetbrains.kotlin.fir.expressions.FirAnnotationCall ?: return null
        val expr =
            call.argumentList.arguments.firstNotNullOfOrNull { arg ->
                when (arg) {
                    is org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression ->
                        if (arg.name.asString() == argName) arg.expression else null
                    // First positional argument on @Signal/@Query/@Update is `name`.
                    else -> if (argName == "name") {
                        arg.takeIf { it is org.jetbrains.kotlin.fir.expressions.FirLiteralExpression }
                    } else {
                        null
                    }
                }
            }
        val literal = expr as? org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
        return literal?.value as? String
    }

    /**
     * Read the wire name from the annotation's `name` argument. Falls back to the user's Kotlin
     * method name if the annotation has no explicit name (or `name = ""`).
     */
    private fun handlerWireName(funcSymbol: FirNamedFunctionSymbol, kind: HandlerKind): String {
        val classId = annotationClassIdFor(kind) ?: return funcSymbol.name.asString()
        val annotation =
            funcSymbol.getAnnotationWithResolvedArgumentsByClassId(classId, session)
                ?: return funcSymbol.name.asString()
        val expr = annotation.findArgumentByName(Name.identifier("name")) ?: return funcSymbol.name.asString()
        val literal = expr as? org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
        val value = literal?.value as? String
        return if (value.isNullOrEmpty()) funcSymbol.name.asString() else value
    }

    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        val owner = context.owner
        if (isWorkflowCompanion(owner)) {
            val isPluginGenerated = (owner.origin as? FirDeclarationOrigin.Plugin)?.key == TemporalCompanionKey
            if (!isPluginGenerated) return emptyList()
            return listOf(createDefaultPrivateConstructor(owner, TemporalCompanionKey).symbol)
        }
        if (isWorkflowHandle(owner)) {
            // Primary constructor: `internal constructor(handle: WorkflowHandle, resultType: KType)`
            // delegating to TypedWorkflowHandle's primary constructor. The delegating-call body
            // is filled by the IR pass since `generateDelegatedNoArgConstructorCall = false`
            // (parent has no no-arg ctor).
            val ctor =
                createConstructor(
                    owner = owner,
                    key = TemporalCompanionKey,
                    isPrimary = true,
                    generateDelegatedNoArgConstructorCall = false,
                ) {
                    visibility = Visibilities.Internal
                    valueParameter(handleCtorParamName, workflowHandleType())
                    valueParameter(resultTypeCtorParamName, kTypeType())
                }
            return listOf(ctor.symbol)
        }
        return emptyList()
    }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirNamedFunctionSymbol> {
        val owner = context?.owner ?: return emptyList()

        if (isWorkflowCompanion(owner)) {
            val ownerClassId = owner.classId.outerClassId ?: return emptyList()
            val ownerClassSymbol =
                session.symbolProvider.getClassLikeSymbolByClassId(ownerClassId)
                    as? FirRegularClassSymbol ?: return emptyList()
            val workflowRunSymbol = findWorkflowRunMethod(ownerClassSymbol) ?: return emptyList()
            val argParam: FirValueParameterSymbol? = workflowRunSymbol.valueParameterSymbols.firstOrNull()
            val resultType = workflowRunSymbol.resolvedReturnType
            val handleType = workflowHandleTypeFor(ownerClassId, resultType)
            return when (callableId.callableName) {
                startName -> listOf(buildStart(owner, handleType, argParam).symbol)
                handleMethodName -> listOf(buildHandleMethod(owner, handleType).symbol)
                else -> emptyList()
            }
        }

        if (isWorkflowHandle(owner)) {
            val outerClassId = owner.classId.outerClassId ?: return emptyList()
            val outerSymbol =
                session.symbolProvider.getClassLikeSymbolByClassId(outerClassId)
                    as? FirRegularClassSymbol ?: return emptyList()
            val handler = findHandlerMethod(outerSymbol, callableId.callableName) ?: return emptyList()
            val kind = handlerKindOf(handler) ?: return emptyList()
            if (kind == HandlerKind.NONE || isDynamicHandler(handler, kind)) return emptyList()
            return listOf(buildHandleWrapper(owner, handler, kind).symbol)
        }

        return emptyList()
    }

    /**
     * Find the user's handler method on [outerSymbol] by name. Used by `generateFunctions` for
     * Handle to look up the source-of-truth signature.
     */
    private fun findHandlerMethod(
        outerSymbol: FirRegularClassSymbol,
        name: Name,
    ): FirNamedFunctionSymbol? {
        val scope = outerSymbol.declaredMemberScope(session, memberRequiredPhase = FirResolvePhase.TYPES)
        var found: FirNamedFunctionSymbol? = null
        scope.processFunctionsByName(name) { funcSymbol ->
            if (found != null) return@processFunctionsByName
            if (handlerKindOf(funcSymbol) != null) found = funcSymbol
        }
        return found
    }

    /**
     * Build the typed wrapper on Handle for a `@Signal` / `@Query` / `@Update` handler.
     *
     * Signature mirrors the user's method: same value parameters; return type is `Unit` for
     * signals, the user's return type for queries/updates. Always `suspend` (dispatch goes
     * through suspend `signalWithPayloads` / `queryWithPayloads` / `updateWithPayloads`). The
     * extension receiver (`WorkflowContext`) on the user method is dropped — Handle is
     * client-side and has no `WorkflowContext`.
     */
    private fun buildHandleWrapper(
        handleSymbol: FirClassSymbol<*>,
        handlerSymbol: FirNamedFunctionSymbol,
        kind: HandlerKind,
    ): org.jetbrains.kotlin.fir.declarations.FirNamedFunction {
        val wrapperReturnType =
            when (kind) {
                HandlerKind.SIGNAL -> session.builtinTypes.unitType.coneType
                HandlerKind.QUERY, HandlerKind.UPDATE -> handlerSymbol.resolvedReturnType
                HandlerKind.NONE -> session.builtinTypes.unitType.coneType
            }
        val key =
            when (kind) {
                HandlerKind.SIGNAL -> TemporalSignalKey
                HandlerKind.QUERY -> TemporalQueryKey
                HandlerKind.UPDATE -> TemporalUpdateKey
                HandlerKind.NONE -> TemporalCompanionKey
            }
        return createMemberFunction(
            owner = handleSymbol,
            key = key,
            name = handlerSymbol.name,
            returnType = wrapperReturnType,
        ) {
            status { isSuspend = true }
            for (param in handlerSymbol.valueParameterSymbols) {
                valueParameter(param.name, param.resolvedReturnType)
            }
            withGeneratedDefaultBody()
        }
    }

    private fun isWorkflowCompanion(classSymbol: FirClassSymbol<*>): Boolean {
        val classId = classSymbol.classId
        if (classId.shortClassName != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) return false
        val outer = classId.outerClassId ?: return false
        val outerSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(outer) as? FirRegularClassSymbol
                ?: return false
        return session.predicateBasedProvider.matches(WORKFLOW_PREDICATE, outerSymbol)
    }

    private fun isWorkflowHandle(classSymbol: FirClassSymbol<*>): Boolean {
        val classId = classSymbol.classId
        if (classId.shortClassName != handleClassName) return false
        val outer = classId.outerClassId ?: return false
        val outerSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(outer) as? FirRegularClassSymbol
                ?: return false
        return session.predicateBasedProvider.matches(WORKFLOW_PREDICATE, outerSymbol)
    }

    private fun findWorkflowRunMethod(ownerSymbol: FirRegularClassSymbol): FirNamedFunctionSymbol? {
        val scope = ownerSymbol.declaredMemberScope(session, memberRequiredPhase = FirResolvePhase.TYPES)
        var found: FirNamedFunctionSymbol? = null
        scope.processAllFunctions { funcSymbol ->
            if (found != null) return@processAllFunctions
            if (funcSymbol.hasAnnotation(workflowRunAnnotationClassId, session)) {
                found = funcSymbol
            }
        }
        return found
    }

    /** `suspend fun start(client, taskQueue[, arg], options): <Workflow>.Handle` */
    private fun buildStart(
        companionSymbol: FirClassSymbol<*>,
        handleType: ConeKotlinType,
        argParam: FirValueParameterSymbol?,
    ) = createMemberFunction(
        owner = companionSymbol,
        key = TemporalCompanionKey,
        name = startName,
        returnType = handleType,
    ) {
        status { isSuspend = true }
        valueParameter(clientParamName, temporalClientType())
        valueParameter(taskQueueParamName, session.builtinTypes.stringType.coneType)
        if (argParam != null) {
            valueParameter(argParamName, argParam.resolvedReturnType)
        }
        valueParameter(optionsParamName, workflowStartOptionsType(), hasDefaultValue = true)
        withGeneratedDefaultBody()
    }

    /** `fun handle(client, workflowId, runId): <Workflow>.Handle` */
    private fun buildHandleMethod(
        companionSymbol: FirClassSymbol<*>,
        handleType: ConeKotlinType,
    ) = createMemberFunction(
        owner = companionSymbol,
        key = TemporalCompanionKey,
        name = handleMethodName,
        returnType = handleType,
    ) {
        valueParameter(clientParamName, temporalClientType())
        valueParameter(workflowIdParamName, session.builtinTypes.stringType.coneType)
        valueParameter(
            runIdParamName,
            stringClassId.createConeType(session, emptyArray(), nullable = true),
            hasDefaultValue = true,
        )
        withGeneratedDefaultBody()
    }

    private fun workflowHandleTypeFor(
        workflowClassId: ClassId,
        resultType: ConeKotlinType,
    ): ConeKotlinType {
        val handleClassId = workflowClassId.createNestedClassId(handleClassName)
        return handleClassId.createConeType(session, arrayOf(resultType))
    }

    private fun temporalClientType(): ConeKotlinType = temporalClientClassId.createConeType(session, emptyArray())

    private fun workflowStartOptionsType(): ConeKotlinType =
        workflowStartOptionsClassId.createConeType(session, emptyArray())

    private fun workflowHandleType(): ConeKotlinType = workflowHandleClassId.createConeType(session, emptyArray())

    private fun kTypeType(): ConeKotlinType = kTypeClassId.createConeType(session, emptyArray())
}
