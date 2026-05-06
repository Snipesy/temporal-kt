// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Workflow("CorrectAsyncWorkflow")
class CorrectAsyncUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(items: List<String>): String {
        val job = launch {
            sleep(1.seconds)
            println("Background work completed")
        }

        val results = items.map { item ->
            async {
                sleep(100.milliseconds)
                processItem(item)
            }
        }

        val processed = results.map { it.await() }
        job.join()
        return processed.joinToString()
    }

    private suspend fun WorkflowContext.processItem(item: String): String {
        val deferred = async {
            sleep(50.milliseconds)
            item.uppercase()
        }
        return deferred.await()
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, integerLiteral, lambdaLiteral,
localProperty, propertyDeclaration, stringLiteral, suspend */
