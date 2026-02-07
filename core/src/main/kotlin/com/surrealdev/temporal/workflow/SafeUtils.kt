package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.workflow.internal.WorkflowCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking

/**
 * Locks the workflow dispatcher if present, allowing for suspend functions to be safely executed with dispatcher
 * switching without breaking the workflow activation process.
 *
 * Currently, this is only used for .encode/.decode calls which are suspend calls to support reaching out to an
 * external server.
 *
 * This is necessary due to API limitations in Kotlinx-Coroutines.
 *
 * TODO: Revisit if this is fixed: https://github.com/Kotlin/kotlinx.coroutines/issues/4611
 */
@Suppress("RunBlockingInSuspendFunction")
internal suspend fun <T> lockWorkflowDispatcherIfPresent(block: suspend () -> T): T {
    val ctx = currentCoroutineContext()[CoroutineDispatcher]

    return if (ctx is WorkflowCoroutineDispatcher) {
        runBlocking {
            block()
        }
    } else {
        block()
    }
}
