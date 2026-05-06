// IGNORE_BACKEND_K1: ANY
// WITH_STDLIB
// FULL_JDK

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.dsl.activity
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlin.random.Random

// Reproduction of user's playground shape. We don't actually invoke the workflow — just
// declaring the class and main() forces JVM to classload the file, which catches any
// `ClassFormatError` from the IR pass emitting illegal method names.
@Workflow
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        return activity("greet") {
            "Hello (random=${Random.nextInt()})"
        }
    }
}

fun box(): String {
    // Reference Greeter.Companion to force class loading of all generated companion methods.
    // We don't call execute() because that would actually try to dispatch through Temporal.
    val companion = Greeter.Companion
    return "OK"
}
