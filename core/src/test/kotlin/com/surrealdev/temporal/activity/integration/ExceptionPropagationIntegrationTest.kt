package com.surrealdev.temporal.activity.integration

import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.common.exceptions.ChildWorkflowFailureException
import com.surrealdev.temporal.common.exceptions.ClientWorkflowFailedException
import com.surrealdev.temporal.common.exceptions.WorkflowActivityFailureException
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.RetryPolicy
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import com.surrealdev.temporal.workflow.startChildWorkflow
import com.surrealdev.temporal.workflow.startLocalActivity
import kotlinx.coroutines.async
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for exception propagation and reconstruction at every boundary.
 *
 * Verifies that:
 * - Exception types are precise (not generic `Exception`)
 * - Cause chains are correctly built (ApplicationFailure extractable, RuntimeException for regular)
 * - No coroutine cancellation artifacts leak ("Parent job is Cancelling")
 * - Non-ApplicationFailure exceptions are handled correctly
 * - Failures propagate correctly through child workflow boundaries
 */
@Tag("integration")
class ExceptionPropagationIntegrationTest {
    // ================================================================
    // Shared Activities
    // ================================================================

    class ExceptionActivities {
        @Activity("throwIllegalState")
        fun throwIllegalState(): String = throw IllegalStateException("illegal state from activity")

        @Activity("throwRuntimeWithCause")
        fun throwRuntimeWithCause(): String {
            val root = IllegalArgumentException("root cause")
            throw RuntimeException("outer error", root)
        }

        @Activity("throwAppFailureNR")
        fun throwAppFailureNR(): String =
            throw ApplicationFailure.nonRetryable(
                message = "validation failed",
                type = "ValidationError",
            )

        @Activity("echoActivity")
        fun echo(input: String): String = input
    }

    class RetryCountingActivities {
        private val attempts = AtomicInteger(0)

        @Activity("retryThenSucceed")
        fun retryThenSucceed(): String {
            val attempt = attempts.incrementAndGet()
            if (attempt < 3) {
                throw RuntimeException("Not ready yet, attempt $attempt")
            }
            return "Success on attempt $attempt"
        }

        fun getAttempts(): Int = attempts.get()
    }

    // ================================================================
    // Section A — Activity → Workflow: Non-ApplicationFailure
    // ================================================================

    @Workflow("RegularExceptionReportWF")
    class RegularExceptionReportWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startActivity(
                    activityType = "throwIllegalState",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 1),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                buildString {
                    append("exType=${e::class.simpleName}")
                    append("|appFailure=${e.applicationFailure != null}")
                    append("|appFailureType=${e.applicationFailure?.type}")
                    append("|appFailureNR=${e.applicationFailure?.isNonRetryable}")
                    append("|causeType=${e.cause?.let { it::class.simpleName }}")
                    append("|causeMsg=${(e.cause as? ApplicationFailure)?.originalMessage ?: e.cause?.message}")
                    append("|failureType=${e.failureType}")
                    append("|activityType=${e.activityType}")
                }
            }
    }

    @Test
    fun `activity regular exception produces WorkflowActivityFailureException with correct fields`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-a1-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<RegularExceptionReportWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "RegularExceptionReportWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            // Exact exception type
            assertTrue(result.contains("exType=WorkflowActivityFailureException"), "Wrong exception type: $result")
            // applicationFailure is present — all exceptions are wrapped with ApplicationFailureInfo
            // at the proto level (matching Python SDK behavior), so the cause is always ApplicationFailure
            assertTrue(result.contains("appFailure=true"), "Should have applicationFailure: $result")
            // The ApplicationFailure type is the original exception class name
            assertTrue(
                result.contains("appFailureType=java.lang.IllegalStateException"),
                "ApplicationFailure type should be original class: $result",
            )
            // Regular exceptions are retryable (nonRetryable=false)
            assertTrue(result.contains("appFailureNR=false"), "Regular exceptions should be retryable: $result")
            // Cause is ApplicationFailure (reconstructed from proto with ApplicationFailureInfo)
            assertTrue(result.contains("causeType=ApplicationFailure"), "Cause should be ApplicationFailure: $result")
            // Original message preserved
            assertTrue(result.contains("causeMsg=illegal state from activity"), "Original message lost: $result")
            // Activity type preserved
            assertTrue(result.contains("activityType=throwIllegalState"), "Activity type lost: $result")

            handle.assertHistory {
                completed()
            }
        }

    // Workflow that captures originalStackTrace and Java stack trace info from activity failure
    @Workflow("ActivityStackTraceReportWF")
    class ActivityStackTraceReportWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startActivity(
                    activityType = "throwIllegalState",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 1),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                val appFailure = e.applicationFailure
                buildString {
                    append("hasOriginalStackTrace=${appFailure?.originalStackTrace != null}")
                    // Check if Java stack trace references the activity class
                    val hasActivityFrame =
                        appFailure?.stackTrace?.any {
                            it.className.contains("ExceptionActivities")
                        } ?: false
                    append("|hasActivityFrame=$hasActivityFrame")
                }
            }
    }

    @Test
    fun `activity failure has originalStackTrace and Java stack trace references activity class`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-a1b-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<ActivityStackTraceReportWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ActivityStackTraceReportWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("hasOriginalStackTrace=true"), "Should have originalStackTrace: $result")
            assertTrue(
                result.contains("hasActivityFrame=true"),
                "Java stack trace should reference activity class: $result",
            )

            handle.assertHistory {
                completed()
            }
        }

    @Workflow("NestedCauseReportWF")
    class NestedCauseReportWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startActivity(
                    activityType = "throwRuntimeWithCause",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 1),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                buildString {
                    append("causeMsg=${(e.cause as? ApplicationFailure)?.originalMessage ?: e.cause?.message}")
                    append("|nestedCause=${e.cause?.cause != null}")
                }
            }
    }

    @Test
    fun `activity exception nested cause is preserved through proto boundary`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-a2-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<NestedCauseReportWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "NestedCauseReportWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            // Outer message preserved
            assertTrue(result.contains("causeMsg=outer error"), "Outer message lost: $result")
            // Nested Java cause IS preserved through proto serialization (recursive cause chain)
            assertTrue(result.contains("nestedCause=true"), "Nested cause should be preserved: $result")

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `regular exception retries per retry policy`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-a3-${UUID.randomUUID()}"
            val activities = RetryCountingActivities()

            @Workflow("RetryRegularExceptionWF")
            class RetryRegularExceptionWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String =
                    startActivity(
                        activityType = "retryThenSucceed",
                        options =
                            ActivityOptions(
                                startToCloseTimeout = 1.minutes,
                                retryPolicy = RetryPolicy(maximumAttempts = 5),
                            ),
                    ).result()
            }

            application {
                taskQueue(taskQueue) {
                    workflow<RetryRegularExceptionWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "RetryRegularExceptionWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertEquals("Success on attempt 3", result)
            assertEquals(3, activities.getAttempts(), "Should have retried 3 times")

            handle.assertHistory {
                completed()
            }
        }

    // ================================================================
    // Section B — Activity → Workflow: ApplicationFailure Cause Chain
    // ================================================================

    @Workflow("AppFailureCauseChainWF")
    class AppFailureCauseChainWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startActivity(
                    activityType = "throwAppFailureNR",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 1),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                val appFailure = e.applicationFailure
                buildString {
                    append("hasAppFailure=${appFailure != null}")
                    append("|causeIsAppFailure=${e.cause is ApplicationFailure}")
                    append("|type=${appFailure?.type}")
                    append("|msg=${appFailure?.originalMessage}")
                    append("|nonRetryable=${appFailure?.isNonRetryable}")
                    append("|failureType=${e.failureType}")
                }
            }
    }

    @Test
    fun `ApplicationFailure produces WorkflowActivityFailureException with extractable applicationFailure`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-excprop-b1-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<AppFailureCauseChainWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "AppFailureCauseChainWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("hasAppFailure=true"), "Should have applicationFailure: $result")
            assertTrue(result.contains("causeIsAppFailure=true"), "Cause should be ApplicationFailure: $result")
            assertTrue(result.contains("type=ValidationError"), "Type not preserved: $result")
            assertTrue(result.contains("msg=validation failed"), "Message not preserved: $result")
            assertTrue(result.contains("nonRetryable=true"), "NonRetryable flag not preserved: $result")

            handle.assertHistory {
                completed()
            }
        }

    // ================================================================
    // Section C — Workflow → Client
    // ================================================================

    @Workflow("ThrowRegularExceptionWF")
    class ThrowRegularExceptionWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = throw IllegalStateException("workflow regular failure")
    }

    @Test
    fun `workflow regular exception propagates as ClientWorkflowFailedException`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-c1-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ThrowRegularExceptionWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ThrowRegularExceptionWF",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<ClientWorkflowFailedException> {
                    handle.result<String>(timeout = 30.seconds)
                }

            // All exceptions are wrapped with ApplicationFailureInfo at proto level
            // (matching Python SDK behavior), so applicationFailure is always present
            val appFailure = exception.applicationFailure
            assertNotNull(appFailure, "Should have applicationFailure (all exceptions wrapped)")
            // The ApplicationFailure type preserves the original exception class name
            assertEquals("java.lang.IllegalStateException", appFailure.type)
            // Regular exceptions are retryable
            assertEquals(false, appFailure.isNonRetryable, "Regular exceptions should be retryable")
            // Original message preserved
            assertEquals("workflow regular failure", appFailure.originalMessage)
            // Cause is ApplicationFailure (reconstructed from proto with ApplicationFailureInfo)
            assertTrue(exception.cause is ApplicationFailure, "Cause should be ApplicationFailure: ${exception.cause}")
            // Workflow ID populated
            assertTrue(exception.workflowId.isNotEmpty(), "workflowId should be populated")

            // Java stack trace should reference the workflow class, not buildCause
            val hasWorkflowFrame =
                appFailure.stackTrace.any {
                    it.className.contains("ThrowRegularExceptionWorkflow")
                }
            assertTrue(
                hasWorkflowFrame,
                "Stack trace should reference ThrowRegularExceptionWorkflow, got: ${appFailure.stackTrace.toList()}",
            )

            handle.assertHistory {
                failed()
            }
        }

    @Workflow("ThrowApplicationFailureWF")
    class ThrowApplicationFailureWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            throw ApplicationFailure.nonRetryable(
                message = "workflow app failure",
                type = "WorkflowValidationError",
            )
    }

    @Test
    fun `workflow ApplicationFailure propagates as ClientWorkflowFailedException with applicationFailure`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-c2-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ThrowApplicationFailureWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ThrowApplicationFailureWF",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<ClientWorkflowFailedException> {
                    handle.result<String>(timeout = 30.seconds)
                }

            val appFailure = exception.applicationFailure
            assertNotNull(appFailure, "Should have applicationFailure")
            assertEquals("WorkflowValidationError", appFailure.type)
            assertEquals("workflow app failure", appFailure.originalMessage)
            assertTrue(appFailure.isNonRetryable)
            // Cause IS ApplicationFailure
            assertTrue(exception.cause is ApplicationFailure, "Cause should be ApplicationFailure")

            // Java stack trace should reference the workflow class, not buildCause
            val hasWorkflowFrame =
                appFailure.stackTrace.any {
                    it.className.contains("ThrowApplicationFailureWorkflow")
                }
            assertTrue(
                hasWorkflowFrame,
                "Stack trace should reference ThrowApplicationFailureWorkflow, got: ${appFailure.stackTrace.toList()}",
            )

            handle.assertHistory {
                failed()
            }
        }

    @Workflow("CatchAndRethrowAppFailureWF")
    class CatchAndRethrowAppFailureWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                return startActivity(
                    activityType = "throwAppFailureNR",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 1),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                throw e.applicationFailure ?: throw e
            }
        }
    }

    @Test
    fun `activity ApplicationFailure rethrown from workflow reaches client correctly`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-excprop-c3-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<CatchAndRethrowAppFailureWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CatchAndRethrowAppFailureWF",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<ClientWorkflowFailedException> {
                    handle.result<String>(timeout = 30.seconds)
                }

            val appFailure = exception.applicationFailure
            assertNotNull(appFailure, "Should have applicationFailure from rethrown activity error")
            assertEquals("ValidationError", appFailure.type)
            assertEquals("validation failed", appFailure.originalMessage)

            handle.assertHistory {
                failed()
            }
        }

    // ================================================================
    // Section D — Child Workflow → Parent Workflow
    // ================================================================

    @Workflow("FailingChildRegularExWF")
    class FailingChildRegularExWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = throw IllegalStateException("child regular failure")
    }

    @Workflow("FailingChildAppFailureWF")
    class FailingChildAppFailureWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            throw ApplicationFailure.nonRetryable(
                message = "child app failure",
                type = "ChildValidationError",
            )
    }

    @Workflow("ChildActivityNoRethrowWF")
    class ChildActivityNoRethrowWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            startActivity(
                activityType = "throwAppFailureNR",
                options =
                    ActivityOptions(
                        startToCloseTimeout = 1.minutes,
                        retryPolicy = RetryPolicy(maximumAttempts = 1),
                    ),
            ).result()
    }

    @Workflow("ChildActivityRethrowsWF")
    class ChildActivityRethrowsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            try {
                return startActivity(
                    activityType = "throwAppFailureNR",
                    options =
                        ActivityOptions(
                            startToCloseTimeout = 1.minutes,
                            retryPolicy = RetryPolicy(maximumAttempts = 1),
                        ),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                throw e.applicationFailure ?: throw e
            }
        }
    }

    @Test
    fun `child regular exception produces ChildWorkflowFailureException`() =
        runTemporalTest(timeSkipping = true) {
            @Workflow("ParentCatchesChildRegularWF")
            class ParentCatchesChildRegularWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String =
                    try {
                        startChildWorkflow("FailingChildRegularExWF", ChildWorkflowOptions()).result<String>()
                    } catch (e: ChildWorkflowFailureException) {
                        val allMessages =
                            generateSequence(e as Throwable) { it.cause }
                                .map { it.message ?: "" }
                                .toList()
                        buildString {
                            append("exType=${e::class.simpleName}")
                            append("|appFailure=${e.applicationFailure != null}")
                            append("|appFailureType=${e.applicationFailure?.type}")
                            append("|appFailureNR=${e.applicationFailure?.isNonRetryable}")
                            append("|hasOriginalMsg=${allMessages.any { it.contains("child regular failure") }}")
                        }
                    }
            }

            val taskQueue = "test-excprop-d1-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ParentCatchesChildRegularWorkflow>()
                    workflow<FailingChildRegularExWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ParentCatchesChildRegularWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("exType=ChildWorkflowFailureException"), "Wrong type: $result")
            // applicationFailure is present because all exceptions are wrapped with ApplicationFailureInfo
            assertTrue(result.contains("appFailure=true"), "Should have applicationFailure: $result")
            // Type preserves the original exception class name
            assertTrue(
                result.contains("appFailureType=java.lang.IllegalStateException"),
                "Type should be original class: $result",
            )
            // Regular exceptions are retryable
            assertTrue(result.contains("appFailureNR=false"), "Regular exceptions should be retryable: $result")
            // Original message should be somewhere in the cause chain
            assertTrue(result.contains("hasOriginalMsg=true"), "Original message lost in cause chain: $result")

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `child ApplicationFailure produces ChildWorkflowFailureException with applicationFailure`() =
        runTemporalTest(timeSkipping = true) {
            @Workflow("ParentCatchesChildAppFailureWF")
            class ParentCatchesChildAppFailureWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String =
                    try {
                        startChildWorkflow("FailingChildAppFailureWF", ChildWorkflowOptions()).result<String>()
                    } catch (e: ChildWorkflowFailureException) {
                        buildString {
                            append("appFailure=${e.applicationFailure != null}")
                            append("|type=${e.applicationFailure?.type}")
                            append("|msg=${e.applicationFailure?.originalMessage}")
                            append("|nonRetryable=${e.applicationFailure?.isNonRetryable}")
                        }
                    }
            }

            val taskQueue = "test-excprop-d2-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ParentCatchesChildAppFailureWorkflow>()
                    workflow<FailingChildAppFailureWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ParentCatchesChildAppFailureWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("appFailure=true"), "Should have applicationFailure: $result")
            assertTrue(result.contains("type=ChildValidationError"), "Type not preserved: $result")
            assertTrue(result.contains("msg=child app failure"), "Message not preserved: $result")
            assertTrue(result.contains("nonRetryable=true"), "NonRetryable flag not preserved: $result")

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `child activity failure without rethrow loses original ApplicationFailure type at parent`() =
        runTemporalTest(timeSkipping = false) {
            @Workflow("ParentCatchesChildActivityNoRethrowWF")
            class ParentCatchesChildActivityNoRethrowWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String =
                    try {
                        startChildWorkflow("ChildActivityNoRethrowWF", ChildWorkflowOptions()).result<String>()
                    } catch (e: ChildWorkflowFailureException) {
                        buildString {
                            append("appFailure=${e.applicationFailure != null}")
                            append("|appType=${e.applicationFailure?.type}")
                        }
                    }
            }

            val taskQueue = "test-excprop-d3-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<ParentCatchesChildActivityNoRethrowWorkflow>()
                    workflow<ChildActivityNoRethrowWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ParentCatchesChildActivityNoRethrowWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            // applicationFailure IS present (all exceptions are wrapped at proto level),
            // but the type is the WRAPPER class (WorkflowActivityFailureException),
            // NOT the original "ValidationError" from the activity's ApplicationFailure.
            // To preserve the original type, the child must catch and rethrow the ApplicationFailure
            // (see test D4).
            assertTrue(result.contains("appFailure=true"), "Should have applicationFailure: $result")
            assertTrue(
                result.contains("WorkflowActivityFailureException"),
                "Type should be wrapper class, not original ValidationError: $result",
            )

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `child catches and rethrows ApplicationFailure preserves type at parent`() =
        runTemporalTest(timeSkipping = false) {
            @Workflow("ParentCatchesChildActivityRethrowWF")
            class ParentCatchesChildActivityRethrowWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String =
                    try {
                        startChildWorkflow("ChildActivityRethrowsWF", ChildWorkflowOptions()).result<String>()
                    } catch (e: ChildWorkflowFailureException) {
                        buildString {
                            append("appFailure=${e.applicationFailure != null}")
                            append("|type=${e.applicationFailure?.type}")
                            append("|msg=${e.applicationFailure?.originalMessage}")
                        }
                    }
            }

            val taskQueue = "test-excprop-d4-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<ParentCatchesChildActivityRethrowWorkflow>()
                    workflow<ChildActivityRethrowsWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ParentCatchesChildActivityRethrowWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("appFailure=true"), "ApplicationFailure should be preserved: $result")
            assertTrue(result.contains("type=ValidationError"), "Type should be preserved: $result")
            assertTrue(result.contains("msg=validation failed"), "Message should be preserved: $result")

            handle.assertHistory {
                completed()
            }
        }

    // ================================================================
    // Section E — No Wrapped Exception / Stack Trace Sanity
    // ================================================================

    @Workflow("ActivityFailureUnhandledWF")
    class ActivityFailureUnhandledWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            startActivity(
                activityType = "throwIllegalState",
                options =
                    ActivityOptions(
                        startToCloseTimeout = 1.minutes,
                        retryPolicy = RetryPolicy(maximumAttempts = 1),
                    ),
            ).result()
    }

    @Test
    fun `activity failure cause chain contains no coroutine cancellation artifacts`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-e1-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<ActivityFailureUnhandledWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ActivityFailureUnhandledWF",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<ClientWorkflowFailedException> {
                    handle.result<String>(timeout = 30.seconds)
                }

            // Walk entire cause chain and verify no cancellation artifacts
            val allMessages =
                generateSequence(exception as Throwable) { it.cause }
                    .map { it.message ?: "" }
                    .toList()

            for (msg in allMessages) {
                assertTrue(
                    !msg.contains("Parent job is Cancelling"),
                    "Cause chain should not contain 'Parent job is Cancelling': $allMessages",
                )
            }

            handle.assertHistory {
                failed()
            }
        }

    @Test
    fun `activity failure in async await propagates without cancellation artifacts`() =
        runTemporalTest(timeSkipping = true) {
            @Workflow("AsyncAwaitActivityFailureWF")
            class AsyncAwaitActivityFailureWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String {
                    val deferred =
                        async {
                            startActivity(
                                activityType = "throwIllegalState",
                                options =
                                    ActivityOptions(
                                        startToCloseTimeout = 1.minutes,
                                        retryPolicy = RetryPolicy(maximumAttempts = 1),
                                    ),
                            ).result<String>()
                        }
                    return deferred.await()
                }
            }

            val taskQueue = "test-excprop-e2-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<AsyncAwaitActivityFailureWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "AsyncAwaitActivityFailureWF",
                    taskQueue = taskQueue,
                )

            val exception =
                assertFailsWith<ClientWorkflowFailedException> {
                    handle.result<String>(timeout = 30.seconds)
                }

            val allMessages =
                generateSequence(exception as Throwable) { it.cause }
                    .map { it.message ?: "" }
                    .toList()

            // The cause chain should reference the activity failure.
            // Note: The original "illegal state from activity" message is lost at the
            // workflow→client boundary because buildWorkflowFailureCompletion only
            // serializes the top-level exception (WorkflowActivityFailureException),
            // not its nested Kotlin cause chain. The "Activity task failed" message
            // from the wrapper is what survives.
            assertTrue(
                allMessages.any { it.contains("Activity task failed") },
                "Cause chain should reference activity failure: $allMessages",
            )

            // No coroutine cancellation artifacts
            for (msg in allMessages) {
                assertTrue(
                    !msg.contains("Parent job is Cancelling"),
                    "Cause chain should not contain 'Parent job is Cancelling': $allMessages",
                )
            }

            handle.assertHistory {
                failed()
            }
        }

    @Test
    fun `concurrent activities with one failure produce clean exception at client`() =
        runTemporalTest(timeSkipping = true) {
            @Workflow("ConcurrentActivitiesOneFailsWF")
            class ConcurrentActivitiesOneFailsWorkflow {
                @WorkflowRun
                suspend fun WorkflowContext.run(): String {
                    val d1 =
                        async {
                            startActivity(
                                activityType = "echoActivity",
                                arg = "hello",
                                options =
                                    ActivityOptions(
                                        startToCloseTimeout = 1.minutes,
                                    ),
                            ).result<String>()
                        }
                    val d2 =
                        async {
                            startActivity(
                                activityType = "throwIllegalState",
                                options =
                                    ActivityOptions(
                                        startToCloseTimeout = 1.minutes,
                                        retryPolicy = RetryPolicy(maximumAttempts = 1),
                                    ),
                            ).result<String>()
                        }
                    return "${d1.await()} ${d2.await()}"
                }
            }

            val taskQueue = "test-excprop-e3-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<ConcurrentActivitiesOneFailsWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ConcurrentActivitiesOneFailsWF",
                    taskQueue = taskQueue,
                )

            // The workflow doesn't catch - the failure propagates to the client.
            // Verify we get the real activity failure, NOT "Parent job is Cancelling".
            val exception =
                assertFailsWith<ClientWorkflowFailedException> {
                    handle.result<String>(timeout = 30.seconds)
                }

            val allMessages =
                generateSequence(exception as Throwable) { it.cause }
                    .map { it.message ?: "" }
                    .toList()

            // No coroutine cancellation artifacts — the CancellationException should be
            // unwrapped to the real WorkflowActivityFailureException
            for (msg in allMessages) {
                assertTrue(
                    !msg.contains("Parent job is Cancelling"),
                    "Should not contain 'Parent job is Cancelling': $allMessages",
                )
            }

            handle.assertHistory {
                failed()
            }
        }

    // ================================================================
    // Section F — Local Activity Exception Propagation
    // ================================================================

    @Workflow("LocalActivityRegularExWF")
    class LocalActivityRegularExWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startLocalActivity(
                    activityType = "throwIllegalState",
                    startToCloseTimeout = 1.minutes,
                    retryPolicy = RetryPolicy(maximumAttempts = 1),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                buildString {
                    append("exType=${e::class.simpleName}")
                    append("|appFailure=${e.applicationFailure != null}")
                    append("|appFailureType=${e.applicationFailure?.type}")
                    append("|appFailureNR=${e.applicationFailure?.isNonRetryable}")
                    append("|causeType=${e.cause?.let { it::class.simpleName }}")
                    append("|causeMsg=${(e.cause as? ApplicationFailure)?.originalMessage ?: e.cause?.message}")
                }
            }
    }

    @Test
    fun `local activity regular exception produces WorkflowActivityFailureException`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-f1-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<LocalActivityRegularExWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "LocalActivityRegularExWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("exType=WorkflowActivityFailureException"), "Wrong type: $result")
            // All exceptions are wrapped with ApplicationFailureInfo at proto level
            assertTrue(result.contains("appFailure=true"), "Should have applicationFailure: $result")
            assertTrue(
                result.contains("appFailureType=java.lang.IllegalStateException"),
                "Type should be original class: $result",
            )
            assertTrue(result.contains("appFailureNR=false"), "Regular exceptions should be retryable: $result")
            // Original message preserved in cause
            assertTrue(result.contains("causeMsg=illegal state from activity"), "Original message lost: $result")

            handle.assertHistory {
                completed()
            }
        }

    @Workflow("LocalActivityAppFailureWF")
    class LocalActivityAppFailureWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String =
            try {
                startLocalActivity(
                    activityType = "throwAppFailureNR",
                    startToCloseTimeout = 1.minutes,
                    retryPolicy = RetryPolicy(maximumAttempts = 1),
                ).result()
            } catch (e: WorkflowActivityFailureException) {
                val appFailure = e.applicationFailure
                buildString {
                    append("hasAppFailure=${appFailure != null}")
                    append("|type=${appFailure?.type}")
                    append("|msg=${appFailure?.originalMessage}")
                    append("|nonRetryable=${appFailure?.isNonRetryable}")
                }
            }
    }

    @Test
    fun `local activity ApplicationFailure produces correct cause chain`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-excprop-f2-${UUID.randomUUID()}"
            val activities = ExceptionActivities()

            application {
                taskQueue(taskQueue) {
                    workflow<LocalActivityAppFailureWorkflow>()
                    activity(activities)
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "LocalActivityAppFailureWF",
                    taskQueue = taskQueue,
                )

            val result = handle.result<String>(timeout = 30.seconds)

            assertTrue(result.contains("hasAppFailure=true"), "Should have applicationFailure: $result")
            assertTrue(result.contains("type=ValidationError"), "Type not preserved: $result")
            assertTrue(result.contains("msg=validation failed"), "Message not preserved: $result")
            assertTrue(result.contains("nonRetryable=true"), "NonRetryable flag not preserved: $result")

            handle.assertHistory {
                completed()
            }
        }
}
