package testData.determinism.coroutine

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/**
 * This workflow uses GlobalScope.async which should be caught by the compiler plugin.
 * Expected error: "GlobalScope.async is forbidden in workflow code"
 */
@Workflow("GlobalScopeWorkflow")
class GlobalScopeFieldUsage {

    val topLevel = GlobalScope
        .async {
            "This breaks determinism!"
        }

    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        topLevel.await()
        return "This should not compile"
    }
}
