package testData.determinism

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * This workflow uses the WorkflowContext scope correctly.
 * This should compile without errors.
 */
@Workflow("ValidWorkflow")
class ValidWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(input: String): String {
        // VALID: Using async within WorkflowContext scope
        val deferred1 = async {
            sleep(100.milliseconds)
            "result1"
        }

        val deferred2 = async {
            sleep(100.milliseconds)
            "result2"
        }

        // VALID: Awaiting Deferreds that are children of the workflow
        val results = awaitAll(deferred1, deferred2)

        return "$input: ${results.joinToString()}"
    }
}
