// IGNORE_BACKEND_K1: ANY
// WITH_STDLIB
// FULL_JDK

import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.workflow.WorkflowContext

// Reproduction of full handler shape — every handler kind on one workflow class.
// Box test verifies all synth'd `Greeter.Handle.*` methods class-load cleanly (no
// ClassFormatError, no `<anonymous>` sentinel bytecode, no missing Kotlin metadata).
@Workflow
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String = "ok"

    @Signal("cancel")
    fun WorkflowContext.cancel(reason: String) { /* ... */ }

    @Query("status")
    fun status(): Int = 42

    @Update("addItem")
    suspend fun WorkflowContext.addItem(item: String): Int = 1
}

fun box(): String {
    val handleCls = Greeter.Handle::class
    val members = handleCls.java.declaredMethods.map { it.name }.toSet()
    if ("cancel" !in members) return "FAIL: missing cancel"
    if ("status" !in members) return "FAIL: missing status"
    if ("addItem" !in members) return "FAIL: missing addItem"
    return "OK"
}
