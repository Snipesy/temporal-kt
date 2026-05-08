package com.surrealdev.temporal.compiler

import com.surrealdev.temporal.compiler.fir.TemporalFirExtensionRegistrar
import com.surrealdev.temporal.compiler.ir.TemporalIrBodyFiller
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * Registers the Temporal compiler plugin extensions.
 *
 * **Cross-version extension registration:** the type that the
 * `ExtensionStorage.registerExtension(...)` extension function dispatches off of changed between
 * Kotlin 2.3.x and 2.4.x — `ProjectExtensionDescriptor<T>` → `ExtensionPointDescriptor<T>`. Calling
 * `FirExtensionRegistrarAdapter.registerExtension(extension)` directly produces bytecode tied to
 * the receiver type at compile time. Bytecode compiled against 2.3.21 references the
 * `ProjectExtensionDescriptor`-receiving method; running it inside a 2.4.x compiler/IDE (where
 * `FirExtensionRegistrarAdapter.Companion` is now `ExtensionPointDescriptor`, NOT
 * `ProjectExtensionDescriptor`) throws `ClassCastException` at the call site.
 *
 * Workaround: register via reflection — look up `ExtensionStorage.registerExtension` by name,
 * which exists in both versions with compatible erased signatures (the receiver and extension
 * parameters both erase to `Object`). Targeted to one call so a future CSM-templated registrar
 * can replace this when proper per-version build is wired.
 */
@OptIn(ExperimentalCompilerApi::class)
class TemporalCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override val pluginId: String = TemporalCommandLineProcessor.PLUGIN_ID

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val enabled = configuration.get(TemporalPluginConfigurationKeys.ENABLED, true)
        if (!enabled) return

        registerExtensionCrossVersion(
            descriptor = FirExtensionRegistrarAdapter.Companion,
            extension = TemporalFirExtensionRegistrar(configuration),
        )
        registerExtensionCrossVersion(
            descriptor = IrGenerationExtension.Companion,
            extension = TemporalIrBodyFiller(),
        )
    }

    /**
     * Reflective replacement for `descriptor.registerExtension(extension)`.
     *
     * In 2.3.x the relevant signature is
     * `ExtensionStorage.registerExtension(ProjectExtensionDescriptor<T>, T)`.
     * In 2.4.x it is
     * `ExtensionStorage.registerExtension(ExtensionPointDescriptor<T>, T)`.
     *
     * Both erase to `(Object, Object)` so a single by-name lookup works against either ABI.
     */
    private fun ExtensionStorage.registerExtensionCrossVersion(
        descriptor: Any,
        extension: Any,
    ) {
        val storage: ExtensionStorage = this
        val method =
            ExtensionStorage::class.java.declaredMethods.firstOrNull {
                it.name == "registerExtension" && it.parameterCount == 2
            }
                ?: error(
                    "Cannot find ExtensionStorage.registerExtension(descriptor, extension) — " +
                        "Kotlin compiler ABI change, please update TemporalCompilerPluginRegistrar.",
                )
        method.isAccessible = true
        method.invoke(storage, descriptor, extension)
    }
}
