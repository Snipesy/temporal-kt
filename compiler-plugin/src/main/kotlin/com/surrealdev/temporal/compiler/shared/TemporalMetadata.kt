package com.surrealdev.temporal.compiler.shared

/**
 * Represents metadata extracted from a Temporal workflow definition.
 */
data class WorkflowMetadata(
    val name: String,
    val taskQueue: String,
    val argType: TypeInfo,
    val returnType: TypeInfo,
    val activities: List<ActivityMetadata>,
    val sourceFile: String,
    val lineNumber: Int,
)

/**
 * Represents metadata extracted from a Temporal activity definition.
 */
data class ActivityMetadata(
    val name: String,
    val taskQueue: String,
    val argType: TypeInfo,
    val returnType: TypeInfo,
    val isLocal: Boolean,
    val sourceFile: String,
    val lineNumber: Int,
)

/**
 * Represents type information for serialization.
 */
data class TypeInfo(
    val fqName: String,
    val simpleName: String,
    val typeArguments: List<TypeInfo> = emptyList(),
    val isNullable: Boolean = false,
) {
    fun toJson(): String =
        buildString {
            append("{")
            append("\"fqName\": \"$fqName\", ")
            append("\"simpleName\": \"$simpleName\", ")
            append("\"isNullable\": $isNullable, ")
            append("\"typeArguments\": [")
            typeArguments.forEachIndexed { index, arg ->
                append(arg.toJson())
                if (index < typeArguments.size - 1) append(", ")
            }
            append("]}")
        }
}

/**
 * Sealed class representing collected Temporal metadata.
 */
sealed class TemporalMetadata {
    abstract fun toJson(): String
}

data class TaskQueueMetadata(
    val name: String,
    val workflows: List<WorkflowMetadata>,
    val activities: List<ActivityMetadata>,
) : TemporalMetadata() {
    override fun toJson(): String =
        buildString {
            append("{")
            append("\"type\": \"taskQueue\", ")
            append("\"name\": \"$name\", ")
            append("\"workflows\": [")
            workflows.forEachIndexed { index, wf ->
                append(wf.toJson())
                if (index < workflows.size - 1) append(", ")
            }
            append("], ")
            append("\"activities\": [")
            activities.forEachIndexed { index, act ->
                append(act.toJson())
                if (index < activities.size - 1) append(", ")
            }
            append("]}")
        }
}

private fun WorkflowMetadata.toJson(): String =
    buildString {
        append("{")
        append("\"name\": \"$name\", ")
        append("\"taskQueue\": \"$taskQueue\", ")
        append("\"argType\": ${argType.toJson()}, ")
        append("\"returnType\": ${returnType.toJson()}, ")
        append("\"sourceFile\": \"$sourceFile\", ")
        append("\"lineNumber\": $lineNumber, ")
        append("\"activities\": [")
        activities.forEachIndexed { index, act ->
            append(act.toJson())
            if (index < activities.size - 1) append(", ")
        }
        append("]}")
    }

private fun ActivityMetadata.toJson(): String =
    buildString {
        append("{")
        append("\"name\": \"$name\", ")
        append("\"taskQueue\": \"$taskQueue\", ")
        append("\"argType\": ${argType.toJson()}, ")
        append("\"returnType\": ${returnType.toJson()}, ")
        append("\"isLocal\": $isLocal, ")
        append("\"sourceFile\": \"$sourceFile\", ")
        append("\"lineNumber\": $lineNumber")
        append("}")
    }
