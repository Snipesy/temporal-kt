package testData.determinism.coroutine

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * This workflow uses GlobalScope.launch which should be caught by the compiler plugin.
 * Expected error: "GlobalScope.launch is forbidden in workflow code"
 */
@Workflow("GlobalScopeLaunchWorkflow")
class GlobalScopeLaunch {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        // VIOLATION: Fire-and-forget on GlobalScope breaks determinism
        GlobalScope.launch {
            // This runs outside workflow control - completely non-deterministic!
            println("This will never be tracked or replayed")
        }

        return "This should not compile"
    }
}
