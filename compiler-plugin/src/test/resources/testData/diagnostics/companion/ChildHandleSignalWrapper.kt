// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun run(arg: String): String = "Hello, $arg"

    @Signal("cancel")
    fun cancel(reason: String) {
        // body irrelevant for the wrapper test
    }
}

// Stage 16: typed `@Signal` wrappers project onto `Greeter.ChildHandle<String>` (mirror of
// `Greeter.Handle<String>`'s wrappers). Signal-only — Query/Update wrappers are deliberately
// omitted on ChildHandle (architectural per `ChildWorkflowHandle` docs).
@Workflow("Parent")
class Parent {
    @WorkflowRun
    suspend fun run() {
        val child: Greeter.ChildHandle<String> = Greeter.startChild("World")
        child.cancel("user requested")
    }
}
