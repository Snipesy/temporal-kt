// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(arg: String): String = "Hello, $arg"

    @Signal("cancel")
    fun WorkflowContext.cancel(reason: String) {
        // body irrelevant for the wrapper test
    }
}

// `@Signal` on the workflow class projects to a typed `cancel(reason)` method on `Greeter.Handle<String>`.
// The Handle wrapper:
//   - drops the `WorkflowContext` extension receiver (Handle is client-side)
//   - returns Unit (signals are fire-and-forget)
//   - is suspend (dispatch goes through `signalWithPayloads`)
suspend fun useSignalWrapper(client: TemporalClient) {
    val handle: Greeter.Handle<String> = Greeter.start(client, "queue", "World")
    handle.cancel("user requested")
}
