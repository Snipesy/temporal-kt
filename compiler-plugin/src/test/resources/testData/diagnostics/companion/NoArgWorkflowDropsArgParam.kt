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

// No-arg workflow's companion exposes start/execute WITHOUT an `arg` parameter.
// Calling them with an arg should NOT typecheck.
suspend fun useNoArg(client: TemporalClient) {
    val result: String = NoArg.execute(client, "queue")
}
