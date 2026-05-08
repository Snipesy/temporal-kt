// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Stateful")
class Stateful {
    @WorkflowRun
    suspend fun run(): Int = 0

    @Query("status")
    fun status(): Int = 0

    @Update("upd")
    suspend fun upd(req: String): Int = req.length
}

// Stage 16: child workflows can't be queried or updated from inside workflow code (synchronous
// RPC breaks determinism). The compiler plugin honors that by NOT generating `@Query`/`@Update`
// wrappers on `ChildHandle`. Calling them on a `ChildHandle` instance fails to resolve.
@Workflow("Parent")
class Parent {
    @WorkflowRun
    suspend fun run() {
        // ChildHandle exposes only @Signal wrappers + result/awaitStart/cancel — `status` (@Query)
        // and `upd` (@Update) are NOT projected onto it. The .fir.txt golden verifies the absence.
        val child: Stateful.ChildHandle<Int> = Stateful.startChild()
        child.awaitStart()
    }
}
