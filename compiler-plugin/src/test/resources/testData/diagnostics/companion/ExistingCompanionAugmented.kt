// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("AlreadyHasCompanion")
class AlreadyHasCompanion {
    @WorkflowRun
    suspend fun WorkflowContext.run(arg: String): String = "Hi, $arg"

    companion object {
        const val SOME_CONSTANT: String = "user-defined"
    }
}

// User's existing companion gets augmented (not replaced) with start/execute.
// Both the user's constant AND the synth'd helpers resolve without error.
suspend fun useAugmented(client: TemporalClient) {
    val s: String = AlreadyHasCompanion.SOME_CONSTANT
    val r: String = AlreadyHasCompanion.execute(client, "q", "World")
}
