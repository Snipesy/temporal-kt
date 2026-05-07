// IGNORE_BACKEND_K1: ANY
// WITH_STDLIB
// FULL_JDK

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.dsl.inlineActivity
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlin.random.Random

@Workflow
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        return inlineActivity("greet") {
            "Hello (random=${Random.nextInt()})"
        }
    }
}

fun box(): String {
    val companion = Greeter.Companion
    val handleCls = Greeter.Handle::class
    return "OK"
}
