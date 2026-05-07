package com.surrealdev.temporal.compiler.fir

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
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
            return setOf(SpecialNames.INIT)
        }
        return emptySet()
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
        if (!isWorkflowCompanion(owner)) return emptyList()
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
