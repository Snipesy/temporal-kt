package com.surrealdev.temporal.compiler.fir

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
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
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

/**
 * Augments every `@com.surrealdev.temporal.annotation.Workflow`-annotated class with a synthesised
 * companion object exposing typed `start(...)` / `execute(...)` helpers.
 *
 * For a workflow:
 *
 * ```
 * @Workflow("Foo")
 * class Foo {
 *     @WorkflowRun suspend fun WorkflowContext.run(arg: A): R = ...
 * }
 * ```
 *
 * the companion gains:
 *
 * ```
 * companion object {
 *     suspend fun start(client, taskQueue, arg, options): TypedWorkflowHandle<R>
 *     suspend fun execute(client, taskQueue, arg, options): R
 * }
 * ```
 *
 * Bodies are stubbed at FIR (`withGeneratedDefaultBody()`) and filled by the IR body filler
 * (Stage 8.5).
 *
 * **Companion handling:**
 * - If the user did NOT write a companion object, the generator emits one (origin = plugin) plus
 *   a private no-arg constructor.
 * - If the user already wrote one, the generator augments it: it emits no nested classifier name
 *   (which would crash `FirCompanionGenerationProcessor` with "duplicated companion object"),
 *   adds `start`/`execute` to the existing companion via [getCallableNamesForClass], and skips
 *   constructor synthesis (the user-written companion has its own).
 *
 * **Phase ordering:**
 * - [getNestedClassifiersNames] runs at SUPERTYPES — must NOT read user method types here.
 * - [getCallableNamesForClass] may run at SUPERTYPES — return fixed names, defer type reading.
 * - [generateFunctions] runs at STATUS, which is past TYPES — safe to read
 *   `funcSymbol.resolvedReturnType` and value parameter types.
 */
class TemporalFirCompanionGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
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

    private val startName = Name.identifier("start")
    private val executeName = Name.identifier("execute")
    private val clientParamName = Name.identifier("client")
    private val taskQueueParamName = Name.identifier("taskQueue")
    private val argParamName = Name.identifier("arg")
    private val optionsParamName = Name.identifier("options")

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
        // Must use predicateBasedProvider here, NOT `hasAnnotation`. This callback runs at
        // COMPANION_GENERATION phase. `FirBasedSymbol.hasAnnotation` internally calls
        // `lazyResolveToPhase(TYPES)`, and TYPES > COMPANION_GENERATION — LL-FIR's IDE engine
        // enforces the lazy-resolve contract strictly and throws
        // `FirLazyResolveContractViolationException`. The predicate provider is indexed during
        // COMPILER_REQUIRED_ANNOTATIONS (one phase earlier) and is safe at any point afterward.
        if (!session.predicateBasedProvider.matches(WORKFLOW_PREDICATE, classSymbol)) return emptySet()
        // Skip if user already wrote a companion — emitting a name here causes
        // FirCompanionGenerationProcessor to throw "duplicated companion object".
        val existingNested = context.declaredScope?.getClassifierNames().orEmpty()
        if (SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT in existingNested) return emptySet()
        return setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
    }

    override fun generateNestedClassLikeDeclaration(
        owner: FirClassSymbol<*>,
        name: Name,
        context: NestedClassGenerationContext,
    ): FirClassLikeSymbol<*>? {
        if (name != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) return null
        // See note in getNestedClassifiersNames: this also runs at COMPANION_GENERATION.
        if (!session.predicateBasedProvider.matches(WORKFLOW_PREDICATE, owner)) return null
        return createCompanionObject(owner, TemporalCompanionKey).symbol
    }

    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext,
    ): Set<Name> {
        if (!isWorkflowCompanion(classSymbol)) return emptySet()
        val isPluginGenerated = (classSymbol.origin as? FirDeclarationOrigin.Plugin)?.key == TemporalCompanionKey
        return buildSet {
            add(startName)
            add(executeName)
            if (isPluginGenerated) add(SpecialNames.INIT)
        }
    }

    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        // Only emit a constructor for plugin-generated companion. User-written companions have
        // their own constructor from source.
        val isPluginGenerated = (context.owner.origin as? FirDeclarationOrigin.Plugin)?.key == TemporalCompanionKey
        if (!isPluginGenerated) return emptyList()
        val ctor = createDefaultPrivateConstructor(context.owner, TemporalCompanionKey)
        return listOf(ctor.symbol)
    }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirNamedFunctionSymbol> {
        val companion = context?.owner ?: return emptyList()
        if (!isWorkflowCompanion(companion)) return emptyList()
        val ownerClassId = companion.classId.outerClassId ?: return emptyList()
        val ownerClassSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(ownerClassId)
                as? FirRegularClassSymbol ?: return emptyList()
        val workflowRunSymbol = findWorkflowRunMethod(ownerClassSymbol) ?: return emptyList()

        val returnType: ConeKotlinType = workflowRunSymbol.resolvedReturnType
        val argParam: FirValueParameterSymbol? = workflowRunSymbol.valueParameterSymbols.firstOrNull()

        return when (callableId.callableName) {
            startName -> listOf(buildStart(companion, returnType, argParam).symbol)
            executeName -> listOf(buildExecute(companion, returnType, argParam).symbol)
            else -> emptyList()
        }
    }

    /**
     * @return true iff [classSymbol] is a companion (plugin-generated or user-written) of a class
     * carrying the `@Workflow` annotation.
     */
    private fun isWorkflowCompanion(classSymbol: FirClassSymbol<*>): Boolean {
        val classId = classSymbol.classId
        if (classId.shortClassName != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) return false
        val outer = classId.outerClassId ?: return false
        val outerSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(outer) as? FirRegularClassSymbol
                ?: return false
        // Predicate-based check is safe at any callback phase (≥ COMPANION_GENERATION).
        // `hasAnnotation` is not — it forward-resolves to TYPES.
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

    /** `suspend fun start(client, taskQueue[, arg], options): TypedWorkflowHandle<R>` */
    private fun buildStart(
        companionSymbol: FirClassSymbol<*>,
        returnType: ConeKotlinType,
        argParam: FirValueParameterSymbol?,
    ) = createMemberFunction(
        owner = companionSymbol,
        key = TemporalCompanionKey,
        name = startName,
        returnType = typedHandleType(returnType),
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

    /** `suspend fun execute(client, taskQueue[, arg], options): R` */
    private fun buildExecute(
        companionSymbol: FirClassSymbol<*>,
        returnType: ConeKotlinType,
        argParam: FirValueParameterSymbol?,
    ) = createMemberFunction(
        owner = companionSymbol,
        key = TemporalCompanionKey,
        name = executeName,
        returnType = returnType,
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

    private fun typedHandleType(returnType: ConeKotlinType): ConeKotlinType =
        typedWorkflowHandleClassId.createConeType(session, arrayOf(returnType))

    private fun temporalClientType(): ConeKotlinType =
        temporalClientClassId.createConeType(session, emptyArray())

    private fun workflowStartOptionsType(): ConeKotlinType =
        workflowStartOptionsClassId.createConeType(session, emptyArray())
}
