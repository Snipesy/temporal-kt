// RUN_PIPELINE_TILL: BACKEND

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Not a workflow — GlobalScope and Dispatchers.* are allowed. */
class NonWorkflowClass {
    fun doBackgroundWork() {
        <!OPT_IN_USAGE!>GlobalScope<!>.launch { println("This is fine - not a workflow") }
    }

    suspend fun fetchData(): String =
        withContext(Dispatchers.IO) { "data" }

    suspend fun processInBackground(): Int =
        withContext(Dispatchers.Default) { (1..1000).sum() }

    fun startAsync() {
        <!OPT_IN_USAGE!>GlobalScope<!>.async { "async result" }
    }
}

suspend fun regularSuspendFunction() {
    withContext(Dispatchers.IO) { println("This is totally fine") }
    <!OPT_IN_USAGE!>GlobalScope<!>.launch { println("Also fine - not in a workflow") }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, lambdaLiteral, rangeExpression,
stringLiteral, suspend */
