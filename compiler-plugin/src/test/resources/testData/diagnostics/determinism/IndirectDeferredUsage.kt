// RUN_PIPELINE_TILL: FRONTEND

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/**
 * `fetchData` is at file top level outside any @Workflow class, but it's called from
 * `IndirectDeferredUsage.run`. The transitive taint walker tracks same-file callees and
 * applies determinism rules to their bodies as well.
 */
@Workflow("IndirectDeferredWorkflow")
class IndirectDeferredUsage {
    @WorkflowRun
    suspend fun WorkflowContext.run(url: String): String {
        val data = fetchData(url)
        return "Fetched: ${data.await()}"
    }
}

private fun fetchData(url: String): Deferred<String> =
    <!TEMPORAL_NONDETERMINISTIC_CALL("GlobalScope extension receiver")!><!OPT_IN_USAGE!>GlobalScope<!>.async { "data from $url" }<!>
