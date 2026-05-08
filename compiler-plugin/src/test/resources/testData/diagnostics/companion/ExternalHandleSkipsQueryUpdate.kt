// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun

@Workflow("Stateful")
class Stateful {
    @WorkflowRun
    suspend fun run(): Int = 0

    @Query("status")
    fun status(): Int = 0

    @Update("upd")
    suspend fun upd(req: String): Int = req.length
}

// ExternalHandle is signal-only — cross-workflow synchronous RPC isn't a Temporal primitive.
// The plugin generates @Signal wrappers + `cancel(reason)` only; `@Query` / `@Update` methods
// declared on the workflow class are NOT projected onto ExternalHandle. The .fir.txt golden
// verifies the absence (no `status` / `upd` member on the synthesised ExternalHandle).
@Workflow("Caller")
class Caller {
    @WorkflowRun
    suspend fun run() {
        val other: Stateful.ExternalHandle = Stateful.external("id")
        other.cancel("done")
    }
}
