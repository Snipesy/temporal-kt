// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.client.TypedWorkflowHandle
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(arg: String): String = "Hello, $arg"
}

// Companion injection produces typed start/execute on the user's class.
// `.result()` works without the <R> reified parameter because the typed handle carries R.
suspend fun useCompanion(client: TemporalClient) {
    val handle: TypedWorkflowHandle<String> = Greeter.start(client, "queue", "World")
    val result: String = handle.result()

    val direct: String = Greeter.execute(client, "queue", "World")
}
