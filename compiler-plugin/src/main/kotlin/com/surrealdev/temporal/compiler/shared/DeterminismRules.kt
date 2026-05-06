package com.surrealdev.temporal.compiler.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    /**
     * Receiver kind for receiver-based matches.
     * One of: "DispatchReceiver", "ExtensionReceiver", "Context", "Regular".
     */
    val parameterKind: String? = null,
    /** FQN of the receiver type to match. */
    val type: String? = null,
    /**
     * Function name pattern; for property accessors use `<get-Foo>` / `<set-Foo>`.
     * Optional refinement when [parameterKind] + [type] are set.
     */
    val functionPattern: String? = null,
    /** Direct match on a function FQN (e.g. `kotlinx.coroutines.withContext`). */
    val function: String? = null,
    /**
     * If specified, the call matches when at least one argument's type contains/equals
     * one of these strings. Used together with [function].
     */
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
}
