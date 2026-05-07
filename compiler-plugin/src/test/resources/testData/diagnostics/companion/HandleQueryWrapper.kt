// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Counter")
class Counter {
    @WorkflowRun
    suspend fun WorkflowContext.run(): Int = 0

    @Query("status")
    fun status(): Int = 42
}

// `@Query` projects to a typed `status(): Int` method on `Counter.Handle<Int>`.
suspend fun useQueryWrapper(client: TemporalClient) {
    val handle: Counter.Handle<Int> = Counter.start(client, "queue")
    val s: Int = handle.status()
}
