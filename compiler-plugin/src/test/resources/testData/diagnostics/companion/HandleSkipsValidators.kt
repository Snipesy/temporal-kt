// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.UpdateValidator
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient

@Workflow("Validated")
class Validated {
    @WorkflowRun
    suspend fun run(): Unit = Unit

    @UpdateValidator(updateName = "addItem")
    fun checkAddItem(item: String) {
        require(item.isNotBlank())
    }

    @Update("addItem")
    suspend fun addItem(item: String): Int = 1
}

// `@UpdateValidator` is server-side; clients never call it. Plugin must skip it — only the
// paired `@Update` gets a typed wrapper on Handle. The .fir.txt golden verifies no
// `checkAddItem` member is generated.
suspend fun useValidated(client: TemporalClient) {
    val handle: Validated.Handle<Unit> = Validated.start(client, "queue")
    val n: Int = handle.addItem("hello")
}
