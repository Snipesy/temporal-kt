package testData.determinism.coroutine

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This workflow uses withContext(Dispatchers.Default) which should be caught by the compiler plugin.
 * Expected error: "withContext(Default) is forbidden in workflow code"
 */
@Workflow("DispatchersDefaultWorkflow")
class DispatchersDefaultUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        // VIOLATION: Using external dispatchers breaks determinism
        withContext(Dispatchers.Default) {
            "Running on default dispatcher pool"
        }

        return "This should not compile"
    }
}
