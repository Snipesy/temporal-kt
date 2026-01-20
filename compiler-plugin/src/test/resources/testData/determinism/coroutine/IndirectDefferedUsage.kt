package testData.determinism.coroutine

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/**
 * This workflow returns a Deferred from GlobalScope.async, which is a violation.
 * The workflow code looks like it's just returning a Deferred, but it's
 * using GlobalScope which breaks determinism.
 *
 * Expected behavior: GlobalScope.async should be caught by the compiler plugin.
 */
@Workflow("IndirectDeferredWorkflow")
class IndirectDeferredUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(url: String): String {
        // This LOOKS safe - just calling a function that returns Deferred
        // But fetchData() uses GlobalScope.async internally
        val data = fetchData(url)
        return "Fetched: ${data.await()}"
    }
}

// Regular function that returns Deferred
// VIOLATION: Uses GlobalScope.async internally
private fun fetchData(url: String): Deferred<String> =
    GlobalScope.async {
        // This breaks determinism when called from workflow!
        "data from $url"
    }
