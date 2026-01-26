package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Context escape tests
 *
 * TODO: Need to revisit this in future
 */
@Tag("integration")
class ContextEscape {
    /**
     * Workflow that handles updates with return values.
     */
    @Workflow("ContextEscape")
    class ContextEscape {
        val interceptor =
            object : ContinuationInterceptor {
                override val key: CoroutineContext.Key<*>
                    get() = ContinuationInterceptor.Key

                override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
                    println("Intercepted continuation: $continuation")
                    return continuation
                }

                override fun plus(context: CoroutineContext): CoroutineContext {
                    println("Plus called with context: $context")
                    return this
                }
            }

        @WorkflowRun
        suspend fun WorkflowContext.run(): Int {
            // TODO it would be useful if we manage this without runblocking
            runBlocking {
                withContext(Dispatchers.IO) {
                    println("Dispatchers IO Context")
                }
            }

            println("Outside Context")
            return 0
        }
    }

    @Test
    @Disabled
    fun `test workflows`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-update-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ContextEscape())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int>(
                    workflowClass = ContextEscape::class,
                    taskQueue = taskQueue,
                )

            assertEquals(handle.result(), 0)

            handle.assertHistory {
                completed()
            }
        }
}
