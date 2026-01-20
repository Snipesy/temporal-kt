package testData.determinism

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * This workflow uses withContext(Dispatchers.IO) which should be caught by the compiler plugin.
 * Expected error: "withContext(IO) is forbidden in workflow code"
 */
@Workflow("WithContextWorkflow")
class WithContextIoUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        // VIOLATION: Switching to Dispatchers.IO breaks deterministic replay
        withContext(Dispatchers.IO) {
            async {
                "This runs on IO thread pool - non-deterministic!"
            }.await()
        }

        return "This should not compile"
    }
}
