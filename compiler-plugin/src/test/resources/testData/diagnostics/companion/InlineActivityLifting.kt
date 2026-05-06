// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.dsl.activity
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlin.random.Random

// Stage 8.6 — inline `activity("name") { ... }` calls inside a @WorkflowRun method get lifted by
// the IR transformation:
//   - lambda body lifted to a top-level `__<WorkflowClass>_<activityName>()` annotated `@Activity`
//   - registration hook `__registerInlineActivities(builder)` synthesised on the companion
//   - the call site is rewritten to `startActivityTyped(...)` for proper Temporal dispatch
//
// `Random.nextInt()` would normally be flagged by the determinism checker — the checker
// (correctly) skips activity bodies, so this compiles clean.
@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        return activity("greet") {
            "Hello (random=${Random.nextInt()})"
        }
    }
}

suspend fun useGreeter(client: TemporalClient) {
    val result: String = Greeter.execute(client, "queue")
}
