// RUN_PIPELINE_TILL: BACKEND

import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow("Cart")
class Cart {
    @WorkflowRun
    suspend fun WorkflowContext.run(): Int = 0

    @Update("addItem")
    suspend fun WorkflowContext.addItem(item: String): Int = 1
}

// `@Update` projects to a typed `addItem(item): Int` method on `Cart.Handle<Int>`.
suspend fun useUpdateWrapper(client: TemporalClient) {
    val handle: Cart.Handle<Int> = Cart.start(client, "queue")
    val n: Int = handle.addItem("hello")
}
