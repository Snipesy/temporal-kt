// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Foo")
class Foo {
    @WorkflowRun
    suspend fun run(arg: String): Int = arg.length
}

// Stage 17.5: child workflow start via central reified API.
//
// `Foo.startChild(arg, options = ...)` is an extension on `WorkflowContext` (the natural
// receiver — child starts must happen inside another `@WorkflowRun` body). The IR rewriter
// turns the call into `Foo.ChildHandle(startChildWorkflowGetHandle(this, "Foo", ...), typeOf<R>())`.
//
// Returns `Foo.ChildHandle<Int>` (R = Foo's @WorkflowRun return type).
@Workflow("Parent")
class Parent {
    @WorkflowRun
    suspend fun run(): Int {
        val child: Foo.ChildHandle<Int> = Foo.startChild("hi")
        return child.result()
    }
}
