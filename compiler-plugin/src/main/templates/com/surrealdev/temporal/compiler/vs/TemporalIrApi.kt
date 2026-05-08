/*
 * Version-specific IR API surfaces.
 *
 * This file is a CSM template — it lives under `src/main/templates/`, not `src/main/kotlin/`.
 * The `processCsmTemplates` Gradle task reads it, selects the `//##csm` block matching the
 * active `kotlin.compiler` Gradle property, and writes a plain `.kt` file to
 * `build/generated-sources/csm/`. That generated file is what compileKotlin actually compiles.
 *
 * Kotlin compiler-plugin APIs (`@ExperimentalCompilerApi`) drift between minor releases. To
 * support multiple Kotlin versions (specifically: IDEA-bundled `-ij` builds), every divergent
 * API call lives behind a wrapper here. Single-version code paths remain in `src/main/kotlin/`.
 *
 * See `Stage 12` in the plan file for the full strategy and `kotlinx-rpc/.../VersionSpecificApiImpl.kt`
 * for the precedent.
 */

package com.surrealdev.temporal.compiler.vs

import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.IrType
//##csm imports
//##csm specific=[2.1.0...2.3.99]
//##csm /specific
//##csm default
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
//##csm /default
//##csm /imports

/**
 * Single source of truth for version-divergent IR construction. Callers route through this
 * object so adding a new Kotlin version means editing one file, not chasing call sites.
 */
internal object TemporalIrApi {
    /**
     * Plain (non-annotation) constructor call. Stable across 2.3 → 2.4+ as `IrConstructorCallImpl`.
     */
    fun newConstructorCall(
        startOffset: Int,
        endOffset: Int,
        type: IrType,
        symbol: IrConstructorSymbol,
        typeArgumentsCount: Int = 0,
        constructorTypeArgumentsCount: Int = 0,
    ): IrConstructorCall =
        IrConstructorCallImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = type,
            symbol = symbol,
            typeArgumentsCount = typeArgumentsCount,
            constructorTypeArgumentsCount = constructorTypeArgumentsCount,
        )

    /**
     * Build an annotation call with all value arguments preset. Diverges between 2.3.x
     * (uses [IrConstructorCallImpl] directly) and 2.4+ (the `annotations` container element type
     * is `IrAnnotation`, requiring an [IrAnnotationImpl] node built via `fromSymbolOwner`).
     *
     * **Important:** value arguments must be passed via the `arguments` parameter rather than
     * mutated on the returned call after the fact. The 2.4+ path constructs a fresh
     * [IrAnnotationImpl] whose backing argument storage is independent of the original
     * [IrConstructorCallImpl] — post-construction mutation on the wrapper would silently drop
     * args at codegen time. Pass everything up-front to avoid that footgun.
     */
    fun newAnnotation(
        startOffset: Int,
        endOffset: Int,
        type: IrType,
        symbol: IrConstructorSymbol,
        arguments: List<IrExpression> = emptyList(),
    ): IrConstructorCall {
        //##csm newAnnotation
        //##csm specific=[2.1.0...2.3.99]
        val call =
            IrConstructorCallImpl(
                startOffset = startOffset,
                endOffset = endOffset,
                type = type,
                symbol = symbol,
                typeArgumentsCount = 0,
                constructorTypeArgumentsCount = 0,
            )
        arguments.forEachIndexed { i, arg -> call.arguments[i] = arg }
        return call
        //##csm /specific
        //##csm default
        // 2.4+: build an IrAnnotationImpl (which IS-A IrConstructorCall) so we can return it
        // through the same signature. Value args go on the wrapper's inherited `arguments` list.
        val ann =
            IrAnnotationImpl.fromSymbolOwner(
                startOffset = startOffset,
                endOffset = endOffset,
                type = type,
                constructorSymbol = symbol,
            )
        arguments.forEachIndexed { i, arg -> ann.arguments[i] = arg }
        return ann
        //##csm /default
        //##csm /newAnnotation
    }

    /**
     * Replace a declaration's `annotations` with the given calls. In 2.3.x the property holds
     * `List<IrConstructorCall>`; in 2.4+ it's `List<IrAnnotation>`. The cast in the 2.4 branch is
     * safe ONLY when every input was produced via [newAnnotation] (which returns
     * [IrAnnotationImpl] in 2.4). Don't pass plain [IrConstructorCallImpl] instances.
     */
    fun setAnnotations(
        container: IrMutableAnnotationContainer,
        calls: List<IrConstructorCall>,
    ) {
        //##csm setAnnotations
        //##csm specific=[2.1.0...2.3.99]
        container.annotations = calls
        //##csm /specific
        //##csm default
        container.annotations = calls.map { it as IrAnnotation }
        //##csm /default
        //##csm /setAnnotations
    }
}
