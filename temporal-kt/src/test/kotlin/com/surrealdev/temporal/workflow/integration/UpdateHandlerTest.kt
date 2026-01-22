package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.UpdateValidator
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.signal
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.client.update
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.WorkflowContext
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for update handlers in workflows.
 */
@Tag("integration")
class UpdateHandlerTest {
    /**
     * Workflow that handles updates with return values.
     */
    @Workflow("UpdateCounterWorkflow")
    class UpdateCounterWorkflow {
        private var counter = 0
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): Int {
            awaitCondition { done }
            return counter
        }

        @Update("increment")
        fun WorkflowContext.increment(amount: Int): Int {
            counter += amount
            return counter
        }

        @Update("getCounter")
        fun WorkflowContext.getCounter(): Int = counter

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `workflow handles updates and returns values`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-update-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(UpdateCounterWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<Int>(
                    workflowType = "UpdateCounterWorkflow",
                    taskQueue = taskQueue,
                )

            // Send updates and verify return values
            val result1: Int = handle.update("increment", 5)
            assertEquals(5, result1)

            val result2: Int = handle.update("increment", 3)
            assertEquals(8, result2)

            val result3: Int = handle.update("getCounter")
            assertEquals(8, result3)

            // Complete workflow
            handle.signal("complete")

            val finalResult = handle.result(timeout = 30.seconds)
            assertEquals(8, finalResult)

            handle.assertHistory {
                completed()
            }
        }

    /**
     * Workflow with update validation.
     */
    @Workflow("ValidatedUpdateWorkflow")
    class ValidatedUpdateWorkflow {
        private var value = ""
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return value
        }

        @Update("setValue")
        fun WorkflowContext.setValue(newValue: String): String {
            value = newValue
            return "set:$value"
        }

        @UpdateValidator("setValue")
        fun validateSetValue(newValue: String) {
            if (newValue.isBlank()) {
                throw IllegalArgumentException("Value cannot be blank")
            }
            if (newValue.length > 10) {
                throw IllegalArgumentException("Value too long (max 10 chars)")
            }
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    @Test
    fun `update validator accepts valid input`() =
        runTemporalTest {
            val taskQueue = "test-validated-update-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ValidatedUpdateWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ValidatedUpdateWorkflow",
                    taskQueue = taskQueue,
                )

            // Valid update should succeed
            val result: String = handle.update("setValue", "hello")
            assertEquals("set:hello", result)

            handle.signal("complete")

            val finalResult = handle.result(timeout = 30.seconds)
            assertEquals("hello", finalResult)

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `update validator rejects invalid input`() =
        runTemporalTest {
            val taskQueue = "test-rejected-update-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow(ValidatedUpdateWorkflow())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow<String>(
                    workflowType = "ValidatedUpdateWorkflow",
                    taskQueue = taskQueue,
                )

            // Invalid update (blank) should be rejected
            assertFailsWith<Exception> {
                handle.update<String, String, String>("setValue", "")
            }

            // Invalid update (too long) should be rejected
            assertFailsWith<Exception> {
                handle.update<String, String, String>("setValue", "this is way too long")
            }

            // Valid update should still work after rejections
            val result: String = handle.update("setValue", "valid")
            assertEquals("set:valid", result)

            handle.signal("complete")

            val finalResult = handle.result(timeout = 30.seconds)
            assertEquals("valid", finalResult)

            handle.assertHistory {
                completed()
            }
        }
}
