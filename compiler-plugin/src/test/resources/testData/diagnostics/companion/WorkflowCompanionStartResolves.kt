// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(arg: String): String = "Hello, $arg"
}

// Companion injection produces typed `start(...)` returning `Greeter.Handle<R>`. R propagates
// from `@WorkflowRun` return type, so `.result()` is typed as `String` without `<R>` ceremony.
// `Greeter.handle(...)` wraps an already-running execution.
suspend fun useCompanion(client: TemporalClient) {
    val handle: Greeter.Handle<String> = Greeter.start(client, "queue", "World")
    val result: String = handle.result()

    val existing: Greeter.Handle<String> = Greeter.handle(client, "some-workflow-id")
    val existingResult: String = existing.result()
}
