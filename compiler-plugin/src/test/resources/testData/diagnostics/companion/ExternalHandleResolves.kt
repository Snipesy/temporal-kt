// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun

@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun run(arg: String): String = "Hello, $arg"

    @Signal("cancel")
    fun cancel(reason: String) {
        // body irrelevant — wrapper test
    }

    @Signal("ping")
    fun ping(payload: String) {
        // body irrelevant
    }
}

// Cross-workflow signal dispatch via the synthesised companion entry point
// `Foo.external(workflowId, runId)`. Returns `Foo.ExternalHandle` (signal-only — typed @Signal
// wrappers + suspend `cancel(reason)` route through the wire via ExternalWorkflowHandle).
@Workflow("Caller")
class Caller {
    @WorkflowRun
    suspend fun run() {
        val other: Greeter.ExternalHandle = Greeter.external("other-workflow-id")
        other.cancel("done")
        other.ping("hello")
    }
}
