// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.dsl.inlineActivity
import com.surrealdev.temporal.workflow.WorkflowContext

// User's request: inline `activity { ... }` lambdas should be allowed to capture local
// vals/vars/parameters from the enclosing `@WorkflowRun` body. The plugin lifts the lambda
// into a top-level `@Activity` function with extra parameters mirroring each capture, and
// the workflow-side call site evaluates the captured values and passes them as activity args.
@Workflow("Capturing")
class Capturing {
    @WorkflowRun
    suspend fun WorkflowContext.run(arg: String): String {
        val capture = "Workflow Foo!"
        val n = arg.length
        return inlineActivity("nestedActivity") {
            "Hello $capture / $arg / $n Activity!"
        }
    }
}

suspend fun useCapturing(client: TemporalClient) {
    val handle: Capturing.Handle<String> = Capturing.start(client, "queue", "x")
    val result: String = handle.result()
}
