// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.dsl.inlineActivity
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlin.random.Random

@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        return inlineActivity("greet") {
            "Hello (random=${Random.nextInt()})"
        }
    }
}

suspend fun useGreeter(client: TemporalClient) {
    val handle: Greeter.Handle<String> = Greeter.start(client, "queue")
    val result: String = handle.result()
}
