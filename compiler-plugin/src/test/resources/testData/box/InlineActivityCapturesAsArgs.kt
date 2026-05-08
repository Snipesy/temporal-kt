// IGNORE_BACKEND_K1: ANY
// WITH_STDLIB
// FULL_JDK

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.dsl.inlineActivity
import com.surrealdev.temporal.workflow.WorkflowContext

@Workflow
class Capturing {
    @WorkflowRun
    suspend fun WorkflowContext.run(arg: String): String {
        val capture = "Workflow Foo!"
        val n = arg.length
        return inlineActivity("nestedActivity") {
            "Hello $capture / $arg / $n!"
        }
    }
}

fun box(): String {
    // Verify the lifted top-level `__Capturing_nestedActivity` has the right JVM signature:
    //   - param[0] = ActivityContext (extension receiver — the lambda was `ActivityContext.()`)
    //   - param[1..3] = the 3 captures (`capture: String`, `arg: String`, `n: Int`)
    //   - param[4] = Continuation (suspend marker)
    // Return type at the JVM level is Object (suspend functions return Any?).
    val mainKt = Class.forName("InlineActivityCapturesAsArgsKt")
    val lifted = mainKt.declaredMethods.firstOrNull { it.name == "__Capturing_nestedActivity" }
        ?: return "FAIL: __Capturing_nestedActivity not found"
    val paramTypes = lifted.parameterTypes
    if (paramTypes.size != 5) return "FAIL: expected 5 params, got ${paramTypes.size}: ${paramTypes.joinToString { it.simpleName }}"
    if (paramTypes[0].simpleName != "ActivityContext") return "FAIL: param[0] = ${paramTypes[0].simpleName}"
    if (paramTypes[1] != String::class.java) return "FAIL: param[1] = ${paramTypes[1].simpleName}"
    if (paramTypes[2] != String::class.java) return "FAIL: param[2] = ${paramTypes[2].simpleName}"
    if (paramTypes[3] != Int::class.javaPrimitiveType) return "FAIL: param[3] = ${paramTypes[3].simpleName}"
    if (paramTypes[4].simpleName != "Continuation") return "FAIL: param[4] = ${paramTypes[4].simpleName}"
    return "OK"
}
