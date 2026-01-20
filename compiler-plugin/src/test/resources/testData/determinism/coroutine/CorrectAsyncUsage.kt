package testData.determinism.coroutine

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * This workflow demonstrates correct async/launch usage within WorkflowContext.
 * This should compile without any errors.
 */
@Workflow("CorrectAsyncWorkflow")
class CorrectAsyncUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(items: List<String>): String {
        // VALID: Launch uses WorkflowContext scope
        val job =
            launch {
                sleep(1.seconds)
                println("Background work completed")
            }

        // VALID: Multiple async operations in WorkflowContext scope
        val results =
            items.map { item ->
                async {
                    // Simulate processing
                    sleep(100.milliseconds)
                    processItem(item)
                }
            }

        // VALID: Await all results
        val processed = results.map { it.await() }

        // VALID: Wait for background job
        job.join()

        return processed.joinToString()
    }

    // Helper function showing nested coroutines
    private suspend fun WorkflowContext.processItem(item: String): String {
        // VALID: Nested async within workflow context
        val deferred =
            async {
                sleep(50.milliseconds)
                item.uppercase()
            }
        return deferred.await()
    }

    // Another common pattern
    @WorkflowRun
    suspend fun WorkflowContext.parallelProcessing(count: Int): List<Int> {
        // VALID: Creating multiple concurrent operations
        return List(count) { index ->
            async {
                sleep(10.milliseconds)
                index * 2
            }
        }.map { it.await() }
    }
}
