// RUN_PIPELINE_TILL: FRONTEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

@Workflow("GlobalScopeWorkflow")
class GlobalScopeUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        <!TEMPORAL_NONDETERMINISTIC_CALL("GlobalScope extension receiver")!><!OPT_IN_USAGE!>GlobalScope<!>
            .async {
                "This breaks determinism!"
            }<!>.await()

        return "This should not compile"
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral, stringLiteral,
suspend */
