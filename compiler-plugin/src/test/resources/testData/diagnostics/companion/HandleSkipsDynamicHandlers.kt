// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.common.TemporalPayloads

@Workflow("Catchall")
class Catchall {
    @WorkflowRun
    suspend fun run(): Unit = Unit

    @Signal(dynamic = true)
    fun catchall(name: String, payloads: TemporalPayloads) {
        // dynamic — receives the wire name as first param. Plugin must NOT generate a wrapper
        // for it; the .fir.txt golden verifies that no `catchall` member appears on Handle.
    }
}

suspend fun useDynamicHandler(client: TemporalClient) {
    val handle: Catchall.Handle<Unit> = Catchall.start(client, "queue")
    // Touch the handle so it's not dead; the no-wrapper assertion lives in the FIR golden.
    handle.toString()
}
