package com.surrealdev.temporal.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * Stage 8.1 placeholder.
 *
 * The previous IR pass filled bodies of FIR-synthesised `__Foo` / `FooStub` / `FooHandle` /
 * `FooHandleImpl` classes. Stage 8.1 deletes that whole infrastructure; typed-companion bodies
 * and inline activity lifting now use targeted IR transforms here.
 *
 * For now, this extension exists only so [TemporalDslLowering]'s fallback rewrite continues to
 * neutralise stray `activity(...)` DSL calls in user code (rewritten to `Unit` for
 * `Unit`-returning calls or `kotlin.error(...)` otherwise), preventing the runtime from actually
 * invoking the no-op DSL stub.
 */
class TemporalIrBodyFiller : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        TemporalCompanionIrBodyFiller(pluginContext).lower(moduleFragment)
        TemporalInlineActivityLowering(pluginContext).lower(moduleFragment)
        TemporalDslLowering(pluginContext).lower(moduleFragment)
    }
}
