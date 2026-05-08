// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient

@Workflow
class Test {
    @WorkflowRun
    fun test() { }
}

suspend fun useTest(client: TemporalClient) {
    val handle: Test.Handle<Unit> = Test.start(client, "hello")
    handle.result()
}
