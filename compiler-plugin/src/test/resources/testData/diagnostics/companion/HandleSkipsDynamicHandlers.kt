// RUN_PIPELINE_TILL: FRONTEND

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Catchall")
class Catchall {
    @WorkflowRun
    suspend fun WorkflowContext.run(): Unit = Unit

    @Signal(dynamic = true)
    fun WorkflowContext.catchall(name: String, payloads: TemporalPayloads) {
        // dynamic — receives the wire name as first param. Plugin must NOT generate a wrapper.
    }
}

// The dynamic handler must not produce a typed wrapper — `Handle.catchall` should be unresolved.
suspend fun useDynamicHandler(client: TemporalClient) {
    val handle: Catchall.Handle<Unit> = Catchall.start(client, "queue")
    handle.<!UNRESOLVED_REFERENCE!>catchall<!>("foo", TemporalPayloads.EMPTY)
}
