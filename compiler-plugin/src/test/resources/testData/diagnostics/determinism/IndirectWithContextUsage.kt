// RUN_PIPELINE_TILL: FRONTEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Both the in-class `fetchData` and the top-level `processDataInBackground` are flagged: the
 * in-class one because its enclosing class is @Workflow, the top-level one because it's reached
 * transitively from `IndirectTopLevelCall.run`.
 */
@Workflow("IndirectWithContextWorkflow")
class IndirectWithContextUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(url: String): String {
        val data = fetchData(url)
        return "Fetched: $data"
    }

    private suspend fun fetchData(url: String): String =
        withContext(<!TEMPORAL_NONDETERMINISTIC_CALL!>Dispatchers.IO<!>) {
            "data from $url"
        }
}

@Workflow("IndirectTopLevelWorkflow")
class IndirectTopLevelCall {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String =
        processDataInBackground("test")
}

suspend fun processDataInBackground(input: String): String =
    withContext(<!TEMPORAL_NONDETERMINISTIC_CALL!>Dispatchers.Default<!>) { input.uppercase() }

@Workflow("CorrectIndirectCallWorkflow")
class CorrectIndirectCall {
    @WorkflowRun
    suspend fun WorkflowContext.run(input: String): String =
        processDataCorrectly(input)

    private suspend fun processDataCorrectly(input: String): String = input.uppercase()
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral, localProperty,
propertyDeclaration, stringLiteral, suspend */
