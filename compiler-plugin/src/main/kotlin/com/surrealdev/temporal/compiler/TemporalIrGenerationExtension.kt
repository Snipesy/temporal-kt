package com.surrealdev.temporal.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.io.File

/**
 * IR Generation Extension that analyzes Temporal DSL blocks and extracts
 * workflow/activity metadata for client stub generation.
 *
 * This extension looks for patterns like:
 * ```kotlin
 * taskQueue("my-queue") {
 *     workflow<ArgType>("WorkflowName") { arg ->
 *         activity<ResultType>("ActivityName") { input ->
 *             // implementation
 *         }
 *     }
 * }
 * ```
 */
class TemporalIrGenerationExtension(
    private val outputDir: String?,
    private val messageCollector: MessageCollector,
) : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        // First pass: Validate workflow determinism
        val validator = WorkflowDeterminismValidator(pluginContext, messageCollector)
        moduleFragment.acceptVoid(validator)

        // Second pass: Collect DSL metadata
        val collector = TemporalDslCollector(pluginContext)
        moduleFragment.accept(collector, null)

        val metadata = collector.getCollectedMetadata()

        if (metadata.isNotEmpty()) {
            writeMetadata(metadata)
            generateClientStubs(metadata, pluginContext)
        }
    }

    private fun writeMetadata(metadata: List<TemporalMetadata>) {
        val outputPath = outputDir ?: return

        val dir = File(outputPath)
        dir.mkdirs()

        val metadataFile = File(dir, "temporal-metadata.json")
        metadataFile.writeText(metadata.toJson())
    }

    private fun generateClientStubs(
        metadata: List<TemporalMetadata>,
        pluginContext: IrPluginContext,
    ) {
        // TODO: Generate actual client stub classes
        // This would create Kotlin source files with typed workflow/activity stubs
    }

    private fun List<TemporalMetadata>.toJson(): String =
        buildString {
            appendLine("[")
            this@toJson.forEachIndexed { index, meta ->
                append("  ")
                append(meta.toJson())
                if (index < this@toJson.size - 1) append(",")
                appendLine()
            }
            appendLine("]")
        }
}
