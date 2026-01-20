package testData.determinism

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This workflow calls a regular suspend function that uses withContext internally.
 * This is a subtle violation - the workflow code looks clean, but the called
 * function breaks determinism.
 *
 * Expected behavior: Should be caught if the suspend function is in the same
 * compilation unit. May not be caught if it's in a dependency.
 */
@Workflow("IndirectWithContextWorkflow")
class IndirectWithContextUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(url: String): String {
        // This LOOKS safe - just calling a suspend function
        // But fetchData() internally uses withContext(Dispatchers.IO)
        val data = fetchData(url)
        return "Fetched: $data"
    }

    // Regular suspend function (no WorkflowContext receiver)
    // VIOLATION: Uses withContext internally
    private suspend fun fetchData(url: String): String = withContext(Dispatchers.IO) {
        // This breaks determinism when called from workflow!
        "data from $url"
    }
}

/**
 * Another example: calling a top-level suspend function that uses withContext
 */
@Workflow("IndirectTopLevelWorkflow")
class IndirectTopLevelCall {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        // Calling a top-level function that internally uses withContext
        return processDataInBackground("test")
    }
}

// Top-level suspend function that uses withContext
// VIOLATION: When called from workflow, this breaks determinism
suspend fun processDataInBackground(input: String): String = withContext(Dispatchers.Default) {
    input.uppercase()
}

/**
 * Correct version: suspend function that doesn't use withContext
 */
@Workflow("CorrectIndirectCallWorkflow")
class CorrectIndirectCall {
    @WorkflowRun
    suspend fun WorkflowContext.run(input: String): String {
        // VALID: Calling a suspend function that doesn't use external dispatchers
        return processDataCorrectly(input)
    }

    // VALID: Regular suspend function without withContext
    private suspend fun processDataCorrectly(input: String): String {
        // Pure computation - no dispatcher switching
        return input.uppercase()
    }
}