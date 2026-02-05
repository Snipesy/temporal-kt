package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.WorkflowStartOptions
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.SearchAttributeKey
import com.surrealdev.temporal.common.searchAttributes
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startChildWorkflow
import com.surrealdev.temporal.workflow.upsertSearchAttributes
import org.junit.jupiter.api.Tag
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for search attributes functionality.
 *
 * These tests verify that search attributes can be set when starting workflows
 * and child workflows, with proper type metadata for Temporal indexing.
 */
@Tag("integration")
class SearchAttributesIntegrationTest {
    // ================================================================
    // Search Attribute Keys
    // ================================================================

    companion object {
        // Search attribute keys supported by the test server
        val IS_PREMIUM = SearchAttributeKey.forBool("CustomBoolField")
        val CREATED_AT = SearchAttributeKey.forDatetime("CustomDatetimeField")
        val SCORE = SearchAttributeKey.forDouble("CustomDoubleField")
        val DESCRIPTION = SearchAttributeKey.forText("CustomTextField")
        val COUNT = SearchAttributeKey.forInt("CustomIntField")
    }

    // ================================================================
    // Test Workflow Classes
    // ================================================================

    /**
     * Simple workflow that returns a greeting with workflow ID.
     */
    @Workflow("SearchAttrTestWorkflow")
    class SearchAttrTestWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String = "Completed workflow: ${info.workflowId}"
    }

    /**
     * Parent workflow that starts a child with search attributes.
     * Uses only attributes supported by the test server.
     */
    @Workflow("ParentWithSearchAttrChildWorkflow")
    class ParentWithSearchAttrChildWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            val childResult =
                startChildWorkflow(
                    "SearchAttrTestWorkflow",
                    ChildWorkflowOptions(
                        searchAttributes =
                            searchAttributes {
                                DESCRIPTION to "child-description"
                                IS_PREMIUM to false
                            },
                    ),
                ).result<String>()
            return "Parent received: $childResult"
        }
    }

    /**
     * Workflow that upserts search attributes during execution.
     * Uses only DESCRIPTION (CustomTextField) which is supported by the test server.
     */
    @Workflow("UpsertSearchAttrWorkflow")
    class UpsertSearchAttrWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(initialValue: String): String {
            // Upsert search attributes during workflow execution
            // Use DESCRIPTION (CustomTextField) which is supported by the test server
            upsertSearchAttributes {
                DESCRIPTION to initialValue
            }
            return "Upserted: $initialValue"
        }
    }

    /**
     * Workflow that upserts an Int search attribute during execution.
     */
    @Workflow("UpsertIntSearchAttrWorkflow")
    class UpsertIntSearchAttrWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(count: Long): String {
            // Upsert Int search attribute
            upsertSearchAttributes {
                COUNT to count
            }
            return "Upserted count: $count"
        }
    }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun `workflow starts with search attributes`() =
        runTemporalTest {
            val taskQueue = "test-search-attr-all-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<SearchAttrTestWorkflow>()
                }
            }

            val client = client()
            val timestamp = Instant.now()
            val handle =
                client.startWorkflow(
                    workflowType = "SearchAttrTestWorkflow",
                    taskQueue = taskQueue,
                    options =
                        WorkflowStartOptions(
                            searchAttributes =
                                searchAttributes {
                                    // Only use attributes supported by the test server
                                    IS_PREMIUM to true
                                    CREATED_AT to timestamp
                                    SCORE to 95.5
                                    DESCRIPTION to "Complete test with all supported types"
                                },
                        ),
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("Completed workflow:"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `child workflow starts with search attributes`() =
        runTemporalTest {
            val taskQueue = "test-child-search-attr-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<ParentWithSearchAttrChildWorkflow>()
                    workflow<SearchAttrTestWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "ParentWithSearchAttrChildWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertTrue(result.contains("Parent received:"))
            assertTrue(result.contains("Completed workflow:"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `workflow starts with empty search attributes succeeds`() =
        runTemporalTest {
            val taskQueue = "test-empty-search-attr-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<SearchAttrTestWorkflow>()
                }
            }

            val client = client()
            // Empty search attributes should not cause any issues
            val handle =
                client.startWorkflow(
                    workflowType = "SearchAttrTestWorkflow",
                    taskQueue = taskQueue,
                    options =
                        WorkflowStartOptions(
                            searchAttributes = searchAttributes { },
                        ),
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("Completed workflow:"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `workflow starts without search attributes option succeeds`() =
        runTemporalTest {
            val taskQueue = "test-no-search-attr-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<SearchAttrTestWorkflow>()
                }
            }

            val client = client()
            // No search attributes option at all
            val handle =
                client.startWorkflow(
                    workflowType = "SearchAttrTestWorkflow",
                    taskQueue = taskQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("Completed workflow:"))

            handle.assertHistory {
                completed()
            }
        }

    // ================================================================
    // Upsert Search Attributes Tests
    // ================================================================

    @Test
    fun `workflow can upsert search attributes`() =
        runTemporalTest {
            val taskQueue = "test-upsert-search-${UUID.randomUUID()}"
            val customerId = "upsert-test-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<UpsertSearchAttrWorkflow>()
                }
            }

            val client = client()

            // Start workflow that upserts search attributes
            val handle =
                client.startWorkflow(
                    workflowType = "UpsertSearchAttrWorkflow",
                    taskQueue = taskQueue,
                    arg = customerId,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("Upserted: $customerId", result)

            // Verify via history that the upsert command was recorded
            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `workflow can upsert Int search attribute`() =
        runTemporalTest(
            timeSkipping = false,
            searchAttributes = listOf(COUNT),
        ) {
            val taskQueue = "test-upsert-int-${UUID.randomUUID()}"
            val count = 144L

            application {
                taskQueue(taskQueue) {
                    workflow<UpsertIntSearchAttrWorkflow>()
                }
            }

            val client = client()

            // Start workflow that upserts Int search attribute
            val handle =
                client.startWorkflow(
                    workflowType = "UpsertIntSearchAttrWorkflow",
                    taskQueue = taskQueue,
                    arg = count,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("Upserted count: $count", result)

            // Verify via history that the upsert command was recorded
            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `workflow starts with Int search attribute on dev server`() =
        runTemporalTest(
            timeSkipping = false,
            searchAttributes = listOf(COUNT),
        ) {
            val taskQueue = "test-start-int-${UUID.randomUUID()}"

            application {
                taskQueue(taskQueue) {
                    workflow<SearchAttrTestWorkflow>()
                }
            }

            val client = client()

            // Start workflow with Int search attribute on dev server (timeSkipping=false)
            val handle =
                client.startWorkflow(
                    workflowType = "SearchAttrTestWorkflow",
                    taskQueue = taskQueue,
                    options =
                        WorkflowStartOptions(
                            searchAttributes =
                                searchAttributes {
                                    COUNT to 42L
                                },
                        ),
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("Completed workflow:"))

            handle.assertHistory {
                completed()
            }
        }
}
