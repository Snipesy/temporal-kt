// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("NoArg")
class NoArg {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String = "no args"
}

// No-arg workflow's companion exposes start WITHOUT an `arg` parameter.
suspend fun useNoArg(client: TemporalClient) {
    val handle: NoArg.Handle<String> = NoArg.start(client, "queue")
    val result: String = handle.result()
}
