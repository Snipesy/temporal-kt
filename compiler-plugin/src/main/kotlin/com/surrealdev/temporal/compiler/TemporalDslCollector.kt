package com.surrealdev.temporal.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.visitors.IrVisitor
import org.jetbrains.kotlin.name.FqName

/**
 * Visitor that collects Temporal DSL definitions from IR.
 *
 * Recognizes the following patterns:
 * - `taskQueue("name") { ... }`
 * - `workflow<T>("name") { ... }`
 * - `activity<T>("name") { ... }`
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class TemporalDslCollector(
    private val pluginContext: IrPluginContext,
) : IrVisitor<Unit, CollectorContext?>() {
    private val collectedMetadata = mutableListOf<TemporalMetadata>()
    private var currentFileName: String = "unknown"

    fun getCollectedMetadata(): List<TemporalMetadata> = collectedMetadata.toList()

    override fun visitElement(
        element: IrElement,
        data: CollectorContext?,
    ) {
        element.acceptChildren(this, data)
    }

    override fun visitFile(
        declaration: IrFile,
        data: CollectorContext?,
    ) {
        currentFileName = declaration.fileEntry.name
        super.visitFile(declaration, data)
        currentFileName = "unknown"
    }

    override fun visitCall(
        expression: IrCall,
        data: CollectorContext?,
    ) {
        val calleeName =
            expression.symbol.owner.name
                .asString()
        val calleeFqName = expression.symbol.owner.fqNameWhenAvailable

        when {
            isTaskQueueCall(calleeName, calleeFqName) -> {
                handleTaskQueueCall(expression, data)
            }

            isWorkflowCall(calleeName, calleeFqName) -> {
                handleWorkflowCall(expression, data)
            }

            isActivityCall(calleeName, calleeFqName) -> {
                handleActivityCall(expression, data)
            }

            else -> {
                // Continue traversing
                expression.acceptChildren(this, data)
            }
        }
    }

    private fun isTaskQueueCall(
        name: String,
        fqName: FqName?,
    ): Boolean =
        name == "taskQueue" &&
            (fqName?.asString()?.contains("temporal") == true || fqName == null)

    private fun isWorkflowCall(
        name: String,
        fqName: FqName?,
    ): Boolean =
        name == "workflow" &&
            (fqName?.asString()?.contains("temporal") == true || fqName == null)

    private fun isActivityCall(
        name: String,
        fqName: FqName?,
    ): Boolean =
        name == "activity" &&
            (fqName?.asString()?.contains("temporal") == true || fqName == null)

    private fun handleTaskQueueCall(
        expression: IrCall,
        data: CollectorContext?,
    ) {
        val queueName = extractStringArgument(expression, 0) ?: return
        val lambdaArg = extractLambdaArgument(expression)

        val context =
            CollectorContext(
                taskQueueName = queueName,
                workflows = mutableListOf(),
                activities = mutableListOf(),
            )

        // Visit the lambda body to collect workflows and activities
        lambdaArg?.acceptChildren(this, context)

        val metadata =
            TaskQueueMetadata(
                name = queueName,
                workflows = context.workflows,
                activities = context.activities,
            )

        collectedMetadata.add(metadata)
    }

    private fun handleWorkflowCall(
        expression: IrCall,
        data: CollectorContext?,
    ) {
        val workflowName = extractStringArgument(expression, 0) ?: return
        val taskQueue = data?.taskQueueName ?: "unknown"

        // Extract type argument (the workflow input type)
        val argType =
            expression.typeArguments.firstOrNull()?.let { extractTypeInfo(it) }
                ?: TypeInfo("kotlin.Unit", "Unit")

        // Return type is inferred from the lambda return
        val returnType =
            extractReturnTypeFromLambda(expression)
                ?: TypeInfo("kotlin.Unit", "Unit")

        // Collect nested activities
        val nestedContext =
            CollectorContext(
                taskQueueName = taskQueue,
                workflows = mutableListOf(),
                activities = mutableListOf(),
                parentWorkflow = workflowName,
            )

        val lambdaArg = extractLambdaArgument(expression)
        lambdaArg?.acceptChildren(this, nestedContext)

        val metadata =
            WorkflowMetadata(
                name = workflowName,
                taskQueue = taskQueue,
                argType = argType,
                returnType = returnType,
                activities = nestedContext.activities,
                sourceFile = currentFileName,
                lineNumber = getLineNumber(expression),
            )

        data?.workflows?.add(metadata)
    }

    private fun handleActivityCall(
        expression: IrCall,
        data: CollectorContext?,
    ) {
        val activityName = extractStringArgument(expression, 0) ?: return
        val taskQueue = data?.taskQueueName ?: "unknown"
        val isLocal = data?.parentWorkflow != null

        // Extract type argument (the activity input type)
        val argType =
            expression.typeArguments.firstOrNull()?.let { extractTypeInfo(it) }
                ?: TypeInfo("kotlin.Unit", "Unit")

        // Return type from lambda
        val returnType =
            extractReturnTypeFromLambda(expression)
                ?: TypeInfo("kotlin.Unit", "Unit")

        val metadata =
            ActivityMetadata(
                name = activityName,
                taskQueue = taskQueue,
                argType = argType,
                returnType = returnType,
                isLocal = isLocal,
                sourceFile = currentFileName,
                lineNumber = getLineNumber(expression),
            )

        data?.activities?.add(metadata)

        // Continue traversing in case there are nested calls
        expression.acceptChildren(this, data)
    }

    private fun extractStringArgument(
        expression: IrCall,
        index: Int,
    ): String? {
        val args = expression.getArgumentsWithIr()
        if (args.size <= index) return null

        val arg = args[index].second
        return when (arg) {
            is IrConst -> arg.value as? String
            else -> null
        }
    }

    private fun extractLambdaArgument(expression: IrCall): IrFunctionExpression? {
        val args = expression.getArgumentsWithIr()
        return args.map { it.second }.filterIsInstance<IrFunctionExpression>().firstOrNull()
    }

    private fun extractTypeInfo(type: IrType): TypeInfo {
        val fqName = type.classFqName?.asString() ?: "kotlin.Any"
        val simpleName = fqName.substringAfterLast('.')

        return TypeInfo(
            fqName = fqName,
            simpleName = simpleName,
            isNullable = type.isNullable(),
        )
    }

    private fun extractReturnTypeFromLambda(expression: IrCall): TypeInfo? {
        val lambda = extractLambdaArgument(expression) ?: return null
        val returnType = lambda.function.returnType
        return extractTypeInfo(returnType)
    }

    private fun getLineNumber(expression: IrCall): Int {
        return expression.startOffset // This is character offset, would need source mapping for line
    }
}

/**
 * Context passed during IR traversal to track current scope.
 */
data class CollectorContext(
    val taskQueueName: String,
    val workflows: MutableList<WorkflowMetadata>,
    val activities: MutableList<ActivityMetadata>,
    val parentWorkflow: String? = null,
)
