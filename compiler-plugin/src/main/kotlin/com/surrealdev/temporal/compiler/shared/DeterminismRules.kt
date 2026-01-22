package com.surrealdev.temporal.compiler.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.ir.declarations.IrParameterKind

@Serializable
data class DeterminismRulesConfig(
    val defaultError: String,
    val rules: List<DeterminismRule>,
)

@Serializable
data class DeterminismRule(
    val name: String,
    val match: RuleMatch,
    val error: String? = null,
)

@Serializable
data class RuleMatch(
    // Match by parameter kind and type (for receiver checks)
    val parameterKind: String? = null,
    val type: String? = null,
    // Optional function name pattern to match (e.g., "<get-IO>")
    val functionPattern: String? = null,
    // Match by function FQ name directly
    val function: String? = null,
    // Match if any argument has one of these types
    val argumentTypes: List<String>? = null,
)

object DeterminismRulesLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): DeterminismRulesConfig {
        val resourceStream =
            this::class.java.classLoader
                .getResourceAsStream("determinism/determinism-rules.json")
                ?: error("Could not find determinism/determinism-rules.json in resources")

        val jsonString = resourceStream.bufferedReader().use { it.readText() }
        return json.decodeFromString<DeterminismRulesConfig>(jsonString)
    }

    fun parseParameterKind(kind: String): IrParameterKind =
        when (kind) {
            "DispatchReceiver" -> IrParameterKind.DispatchReceiver
            "ExtensionReceiver" -> IrParameterKind.ExtensionReceiver
            "Context" -> IrParameterKind.Context
            "Regular" -> IrParameterKind.Regular
            else -> error("Unknown parameter kind: $kind")
        }
}
