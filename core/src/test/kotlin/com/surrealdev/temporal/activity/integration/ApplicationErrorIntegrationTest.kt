package com.surrealdev.temporal.activity.integration

import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.exceptions.ActivityRetryState
import com.surrealdev.temporal.common.exceptions.ApplicationErrorCategory
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.common.exceptions.nonRetryable
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.RetryPolicy
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import com.surrealdev.temporal.workflow.startLocalActivity
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [ApplicationFailure] exception handling.
 *
 * These tests verify that:
 * - Non-retryable ApplicationFailure stops retries immediately
 * - Retryable ApplicationFailure allows retries per retry policy
 * - Error type and message are correctly propagated to workflow
 * - Workflow can catch and inspect ApplicationFailureException
 */
@Tag("integration")
class ApplicationFailureIntegrationTest {
    // ================================================================
    // Test Activities
    // ================================================================

    /**
     * Activities that throw ApplicationFailure for testing.
     */
    class ApplicationFailureActivities {
        private val nonRetryableAttempts = AtomicInteger(0)
        private val retryableAttempts = AtomicInteger(0)

        @Activity("throwNonRetryable")
        fun throwNonRetryable(): String {
            nonRetryableAttempts.incrementAndGet()
            throw ApplicationFailure.nonRetryable(
                message = "This error should not be retried",
                type = "ValidationError",
            )
        }

        @Activity("throwRetryable")
        fun throwRetryable(): String {
            val attempt = retryableAttempts.incrementAndGet()
            throw ApplicationFailure.failure(
                message = "Temporary error on attempt $attempt",
                type = "TemporaryError",
            )
        }

        @Activity("throwRetryableThenSucceed")
        fun throwRetryableThenSucceed(): String {
            val attempt = retryableAttempts.incrementAndGet()
            if (attempt < 3) {
                throw ApplicationFailure.failure(
                    message = "Not ready yet, attempt $attempt",
                    type = "NotReadyError",
                )
            }
            return "Success on attempt $attempt"
        }

        @Activity("throwWithCustomType")
        fun throwWithCustomType(errorType: String): String =
            throw ApplicationFailure.nonRetryable(
                message = "Custom error",
                type = errorType,
            )

        @Activity("throwWithDetails")
        fun throwWithDetails(
            code: Int,
            field: String,
        ): String =
            throw ApplicationFailure.nonRetryable<String>(
                message = "Validation failed",
                type = "ValidationError",
                detail = "code=$code, field=$field",
            )

        @Activity("throwBenignError")
        fun throwBenignError(): String =
            throw ApplicationFailure.nonRetryable(
                message = "User not found - expected business case",
                type = "NotFoundError",
                category = ApplicationErrorCategory.BENIGN,
            )

        @Activity("throwWithVarargDetails")
        fun throwWithVarargDetails(): String =
            throw ApplicationFailure.nonRetryable(
                message = "Multiple details",
                type = "DetailedError",
            )

        fun getNonRetryableAttempts(): Int = nonRetryableAttempts.get()

        fun getRetryableAttempts(): Int = retryableAttempts.get()

        fun reset() {
            nonRetryableAttempts.set(0)
            retryableAttempts.set(0)
        }
    }

    // ================================================================
    // Test Workflows
    // ================================================================

    @Workflow("NonRetryableActivityWorkflow")
    class NonRetryableActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startActivity(
                    activityType = "throwNonRetryable",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 5),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                "caught: type=${e.applicationFailure?.type}, " +
                    "message=${e.applicationFailure?.message}, " +
                    "isNonRetryable=${e.applicationFailure?.isNonRetryable}, " +
                    "retryState=${e.retryState}"
            }
    }

    @Workflow("RetryableActivityWorkflow")
    class RetryableActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startActivity(
                    activityType = "throwRetryable",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 3),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                "caught: type=${e.applicationFailure?.type}, " +
                    "retryState=${e.retryState}"
            }
    }

    @Workflow("RetryThenSucceedWorkflow")
    class RetryThenSucceedWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            startActivity(
                activityType = "throwRetryableThenSucceed",
                options =
                    ActivityOptions(
                        startToCloseTimeout = 1.minutes,
                        retryPolicy = RetryPolicy(maximumAttempts = 5),
                    ),
            ).result()
    }

    @Workflow("CustomErrorTypeWorkflow")
    class CustomErrorTypeWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(errorType: String): String =
            try {
                startActivity(
                    activityType = "throwWithCustomType",
                    arg = errorType,
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 1),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                "caught: type=${e.applicationFailure?.type}"
            }
    }

    @Workflow("LocalActivityNonRetryableWorkflow")
    class LocalActivityNonRetryableWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startLocalActivity(
                    activityType = "throwNonRetryable",
                    startToCloseTimeout = 1.minutes,
                    retryPolicy = RetryPolicy(maximumAttempts = 5),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                "caught: type=${e.applicationFailure?.type}, " +
                    "isNonRetryable=${e.applicationFailure?.isNonRetryable}, " +
                    "retryState=${e.retryState}"
            }
    }

    // ================================================================
    // Integration Tests
    // ================================================================

    @Test
    fun `non-retryable ApplicationFailure stops retries immediately`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-non-retryable-${UUID.randomUUID()}"
            val activities = ApplicationFailureActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<NonRetryableActivityWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "NonRetryableActivityWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            // Verify only 1 attempt was made (no retries)
            assertEquals(1, activities.getNonRetryableAttempts(), "Should only attempt once for non-retryable error")

            // Verify the error info is correctly propagated
            assertTrue(result.contains("type=ValidationError"), "Should have correct error type: $result")
            assertTrue(result.contains("isNonRetryable=true"), "Should be marked as non-retryable: $result")
            assertTrue(result.contains("retryState=NON_RETRYABLE_FAILURE"), "Should have correct retry state: $result")

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `retryable ApplicationFailure allows retries until max attempts`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-retryable-${UUID.randomUUID()}"
            val activities = ApplicationFailureActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<RetryableActivityWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "RetryableActivityWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            // Verify all 3 attempts were made
            assertEquals(3, activities.getRetryableAttempts(), "Should attempt 3 times (max attempts)")

            // Verify the error info is correctly propagated
            assertTrue(result.contains("type=TemporaryError"), "Should have correct error type: $result")
            assertTrue(
                result.contains("retryState=MAXIMUM_ATTEMPTS_REACHED"),
                "Should indicate max attempts reached: $result",
            )

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `retryable ApplicationFailure succeeds after retries`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-retry-succeed-${UUID.randomUUID()}"
            val activities = ApplicationFailureActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<RetryThenSucceedWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "RetryThenSucceedWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            // Should succeed on attempt 3
            assertEquals("Success on attempt 3", result)
            assertEquals(3, activities.getRetryableAttempts(), "Should have made 3 attempts")

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `custom error type is correctly propagated`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-custom-type-${UUID.randomUUID()}"
            val activities = ApplicationFailureActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<CustomErrorTypeWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CustomErrorTypeWorkflow",
                    taskQueue = taskQueue,
                    arg = "MyCustomErrorType",
                )

            val result = handle.result<String>(timeout = 30.seconds)

            // Verify the custom error type is propagated
            assertTrue(result.contains("type=MyCustomErrorType"), "Should have custom error type: $result")

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `local activity non-retryable ApplicationFailure stops retries`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-la-non-retryable-${UUID.randomUUID()}"
            val activities = ApplicationFailureActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<LocalActivityNonRetryableWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "LocalActivityNonRetryableWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            // Verify only 1 attempt was made
            assertEquals(1, activities.getNonRetryableAttempts(), "Local activity should only attempt once")

            // Verify the error info is correctly propagated
            assertTrue(result.contains("type=ValidationError"), "Should have correct error type: $result")
            assertTrue(result.contains("isNonRetryable=true"), "Should be marked as non-retryable: $result")

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `workflow can inspect ApplicationFailure details`() =
        runTemporalTest(timeSkipping = true) {
            @Workflow("InspectFailureWorkflow")
            class InspectFailureWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String =
                    try {
                        startActivity(
                            activityType = "throwNonRetryable",
                            options =
                                ActivityOptions(
                                    startToCloseTimeout = 1.minutes,
                                    retryPolicy = RetryPolicy(maximumAttempts = 1),
                                ),
                        ).result()
                    } catch (e: WorkflowActivityFailureException) {
                        val failure = e.applicationFailure
                        assertNotNull(failure)
                        assertEquals("ValidationError", failure.type)
                        assertEquals("This error should not be retried", failure.message)
                        assertTrue(failure.isNonRetryable)
                        assertEquals(ActivityRetryState.NON_RETRYABLE_FAILURE, e.retryState)
                        "verified"
                    }
            }

            val taskQueue = "test-inspect-failure-${UUID.randomUUID()}"
            val activities = ApplicationFailureActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<InspectFailureWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "InspectFailureWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)
            assertEquals("verified", result)

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `ApplicationFailure with BENIGN category is propagated`() =
        runTemporalTest(timeSkipping = true) {
            @Workflow("BenignErrorWorkflow")
            class BenignErrorWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String =
                    try {
                        startActivity(
                            activityType = "throwBenignError",
                            options =
                                ActivityOptions(
                                    startToCloseTimeout = 1.minutes,
                                    retryPolicy = RetryPolicy(maximumAttempts = 1),
                                ),
                        ).result()
                    } catch (e: WorkflowActivityFailureException) {
                        val failure = e.applicationFailure
                        "type=${failure?.type}, " +
                            "category=${failure?.category}, " +
                            "message=${failure?.message}"
                    }
            }

            val taskQueue = "test-benign-${UUID.randomUUID()}"
            val activities = ApplicationFailureActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<BenignErrorWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "BenignErrorWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("type=NotFoundError"), "Should have NotFoundError type: $result")
            assertTrue(result.contains("category=BENIGN"), "Should have BENIGN category: $result")

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `ApplicationFailure with details is serialized`() =
        runTemporalTest(timeSkipping = true) {
            @Workflow("DetailsErrorWorkflow")
            class DetailsErrorWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String =
                    try {
                        startActivity(
                            activityType = "throwWithDetails",
                            arg1 = 400,
                            arg2 = "email",
                            options =
                                ActivityOptions(
                                    startToCloseTimeout = 1.minutes,
                                    retryPolicy = RetryPolicy(maximumAttempts = 1),
                                ),
                        ).result()
                    } catch (e: WorkflowActivityFailureException) {
                        val failure = e.applicationFailure
                        // Details are present as decoded TemporalPayloads - verify they exist
                        "type=${failure?.type}, hasDetails=${failure?.details?.isEmpty == false}"
                    }
            }

            val taskQueue = "test-details-${UUID.randomUUID()}"
            val activities = ApplicationFailureActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<DetailsErrorWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "DetailsErrorWorkflow",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("type=ValidationError"), "Should have ValidationError type: $result")
            assertTrue(result.contains("hasDetails=true"), "Should have details: $result")

            handle.assertHistory {
                completed()
            }
        }
}
