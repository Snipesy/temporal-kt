// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient

// User's playground shape: @Workflow (no name argument), no-arg @WorkflowRun method.
// Tests two edge cases the existing companion testData doesn't cover:
//  1. `@Workflow` annotation with default name argument (empty string) — predicate must still match
//  2. `@WorkflowRun` method that takes no value parameters (no `arg` param on synth'd companion)
@Workflow
class Test {
    @WorkflowRun
    fun test() { }
}

suspend fun useTest(client: TemporalClient) {
    Test.execute(client, "hello")          // 2 args — options has default
    val handle = Test.start(client, "hello")
    handle.result()                        // typed result, no <Unit>
}
