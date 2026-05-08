// RUN_PIPELINE_TILL: FRONTEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Workflow("DispatchersDefaultWorkflow")
class DispatchersDefaultUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        withContext(<!TEMPORAL_NONDETERMINISTIC_CALL!>Dispatchers.Default<!>) {
            "Running on default dispatcher pool"
        }

        return "This should not compile"
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral, stringLiteral,
suspend */
