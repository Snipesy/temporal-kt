package com.surrealdev.temporal.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * Stage 8.1 placeholder.
 *
 * The previous IR pass filled bodies of FIR-synthesised `__Foo` / `FooStub` / `FooHandle` /
 * `FooHandleImpl` classes. Stage 8.1 deletes that whole infrastructure; future stages (8.4 inline
 * workflow synthesis, 8.5 typed-companion bodies, 8.6 inline activity lifting) will rebuild
 * targeted IR transforms here.
 *
 * For now, this extension exists only so [TemporalDslLowering]'s fallback rewrite continues to
 * neutralise stray `workflow(...)` / `activity(...)` DSL calls in user code (rewritten to `Unit`
 * for `Unit`-returning calls or `kotlin.error(...)` otherwise) — preventing the runtime from
 * actually invoking the no-op DSL stubs.
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
