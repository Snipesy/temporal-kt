// RUN_PIPELINE_TILL: FRONTEND

import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.UpdateValidator
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Validated")
class Validated {
    @WorkflowRun
    suspend fun WorkflowContext.run(): Unit = Unit

    @UpdateValidator(updateName = "addItem")
    fun checkAddItem(item: String) {
        require(item.isNotBlank())
    }

    @Update("addItem")
    suspend fun WorkflowContext.addItem(item: String): Int = 1
}

// `@UpdateValidator` is server-side; clients never call it. Plugin must skip it.
// Only the paired `@Update` produces a typed wrapper.
suspend fun useValidated(client: TemporalClient) {
    val handle: Validated.Handle<Unit> = Validated.start(client, "queue")
    val n: Int = handle.addItem("hello")
    handle.<!UNRESOLVED_REFERENCE!>checkAddItem<!>("hello")
}
