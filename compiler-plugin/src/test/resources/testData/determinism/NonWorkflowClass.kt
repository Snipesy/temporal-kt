package testData.determinism

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This class is NOT a workflow (no @Workflow annotation).
 * GlobalScope and Dispatchers.* usage should be ALLOWED here.
 * This ensures the validator only checks @Workflow annotated classes.
 */
class NonWorkflowClass {
    // ALLOWED: GlobalScope is fine in non-workflow code
    fun doBackgroundWork() {
        GlobalScope.launch {
            println("This is fine - not a workflow")
        }
    }

    // ALLOWED: withContext(Dispatchers.IO) is fine in non-workflow code
    suspend fun fetchData(): String = withContext(Dispatchers.IO) {
        // Simulate IO operation
        "data"
    }

    // ALLOWED: Using external dispatchers is fine outside workflows
    suspend fun processInBackground(): Int = withContext(Dispatchers.Default) {
        // CPU-intensive work
        (1..1000).sum()
    }

    // ALLOWED: GlobalScope.async is fine in non-workflow code
    fun startAsync() {
        GlobalScope.async {
            "async result"
        }
    }
}

/**
 * Regular suspend function outside a workflow.
 * All coroutine APIs should be allowed.
 */
suspend fun regularSuspendFunction() {
    withContext(Dispatchers.IO) {
        println("This is totally fine")
    }

    GlobalScope.launch {
        println("Also fine - not in a workflow")
    }
}
