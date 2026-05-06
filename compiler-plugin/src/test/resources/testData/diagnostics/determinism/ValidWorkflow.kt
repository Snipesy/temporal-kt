// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.time.Duration.Companion.milliseconds

@Workflow("ValidWorkflow")
class ValidWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(input: String): String {
        val deferred1 = async {
            sleep(100.milliseconds)
            "result1"
        }

        val deferred2 = async {
            sleep(100.milliseconds)
            "result2"
        }

        val results = awaitAll(deferred1, deferred2)
        return "$input: ${results.joinToString()}"
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, integerLiteral, lambdaLiteral,
localProperty, propertyDeclaration, stringLiteral, suspend */
