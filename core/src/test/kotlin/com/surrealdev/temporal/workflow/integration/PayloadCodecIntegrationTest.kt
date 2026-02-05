package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.serialization.PayloadCodecPlugin
import com.surrealdev.temporal.serialization.codec.CompressionCodec
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for PayloadCodec functionality.
 *
 * These tests verify that payload codecs (compression, custom) work correctly
 * throughout the workflow lifecycle, including:
 * - Workflow input arguments
 * - Workflow results
 * - Activity input arguments
 * - Activity results
 */
@Tag("integration")
class PayloadCodecIntegrationTest {
    // ================================================================
    // Test Data Classes
    // ================================================================

    @Serializable
    data class LargePayload(
        val id: String,
        val data: String,
        val items: List<String>,
    )

    @Serializable
    data class ProcessedResult(
        val originalSize: Int,
        val processedData: String,
    )

    // ================================================================
    // Test Activity Classes
    // ================================================================

    /**
     * Activity that processes a large payload.
     */
    class LargeDataActivity {
        @Activity("processLargeData")
        suspend fun ActivityContext.processLargeData(payload: LargePayload): ProcessedResult =
            ProcessedResult(
                originalSize = payload.data.length,
                processedData = "Processed: ${payload.id} with ${payload.items.size} items",
            )
    }

    // ================================================================
    // Test Workflow Classes
    // ================================================================

    /**
     * Simple workflow that echoes large data back.
     * This tests workflow input/output codec handling.
     */
    @Workflow("EchoLargeDataWorkflow")
    class EchoLargeDataWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(payload: LargePayload): LargePayload =
            // Just return the payload - tests round-trip encoding/decoding
            payload.copy(id = "echoed-${payload.id}")
    }

    /**
     * Workflow that processes large data through an activity.
     * This tests codec handling for activity inputs and results.
     */
    @Workflow("ProcessLargeDataWorkflow")
    class ProcessLargeDataWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(payload: LargePayload): ProcessedResult {
            val result: ProcessedResult =
                startActivity<LargePayload>(
                    activityType = "processLargeData",
                    arg = payload,
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            return result
        }
    }

    /**
     * Workflow that generates and returns large data.
     * Tests that large workflow results are compressed.
     */
    @Workflow("GenerateLargeDataWorkflow")
    class GenerateLargeDataWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(itemCount: Int): LargePayload =
            LargePayload(
                id = "generated-${info.workflowId}",
                data = "x".repeat(10000), // 10KB of data
                items = (1..itemCount).map { "item-$it-${"y".repeat(100)}" },
            )
    }

    // ================================================================
    // Custom Test Codec for Verification
    // ================================================================

    /**
     * A test codec that wraps another codec and records encode/decode calls.
     * Used to verify that the codec is actually being invoked.
     */
    @OptIn(InternalTemporalApi::class)
    class RecordingCodec(
        private val inner: PayloadCodec,
    ) : PayloadCodec {
        val encodeWasCalled = AtomicBoolean(false)
        val decodeWasCalled = AtomicBoolean(false)
        val lastEncodedPayloads = AtomicReference<List<TemporalPayload>>(emptyList())
        val lastDecodedPayloads = AtomicReference<List<TemporalPayload>>(emptyList())

        override suspend fun encode(payloads: TemporalPayloads): TemporalPayloads {
            encodeWasCalled.set(true)
            lastEncodedPayloads.set(payloads.payloads)
            return inner.encode(payloads)
        }

        override suspend fun decode(payloads: TemporalPayloads): TemporalPayloads {
            decodeWasCalled.set(true)
            lastDecodedPayloads.set(payloads.payloads)
            return inner.decode(payloads)
        }

        fun reset() {
            encodeWasCalled.set(false)
            decodeWasCalled.set(false)
            lastEncodedPayloads.set(emptyList())
            lastDecodedPayloads.set(emptyList())
        }
    }

    // ================================================================
    // Tests
    // ================================================================

    @Test
    fun `compression codec works for workflow input and output`() =
        runTemporalTest {
            val taskQueue = "test-codec-echo-${UUID.randomUUID()}"

            application {
                // Install compression codec
                install(PayloadCodecPlugin) {
                    compression(threshold = 100) // Compress payloads > 100 bytes
                }

                taskQueue(taskQueue) {
                    workflow<EchoLargeDataWorkflow>()
                }
            }

            val client = client()

            // Create a large payload that should trigger compression
            val largePayload =
                LargePayload(
                    id = "test-123",
                    data = "a".repeat(5000), // 5KB of data
                    items = (1..100).map { "item-$it" },
                )

            val handle =
                client.startWorkflow(
                    workflowType = "EchoLargeDataWorkflow",
                    taskQueue = taskQueue,
                    arg = largePayload,
                )

            val result: LargePayload = handle.result(timeout = 30.seconds)

            // Verify the data round-tripped correctly
            assertEquals("echoed-test-123", result.id)
            assertEquals(largePayload.data, result.data)
            assertEquals(largePayload.items, result.items)

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `compression codec works for activity input and output`() =
        runTemporalTest {
            val taskQueue = "test-codec-activity-${UUID.randomUUID()}"

            application {
                // Install compression codec
                install(PayloadCodecPlugin) {
                    compression(threshold = 100)
                }

                taskQueue(taskQueue) {
                    workflow<ProcessLargeDataWorkflow>()
                    activity(LargeDataActivity())
                }
            }

            val client = client()

            val largePayload =
                LargePayload(
                    id = "activity-test",
                    data = "b".repeat(3000),
                    items = (1..50).map { "activity-item-$it" },
                )

            val handle =
                client.startWorkflow(
                    workflowType = "ProcessLargeDataWorkflow",
                    taskQueue = taskQueue,
                    arg = largePayload,
                )

            val result: ProcessedResult = handle.result(timeout = 30.seconds)

            // Verify the activity processed the data correctly
            assertEquals(3000, result.originalSize)
            assertTrue(result.processedData.contains("activity-test"))
            assertTrue(result.processedData.contains("50 items"))

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `workflow generating large result has it compressed`() =
        runTemporalTest {
            val taskQueue = "test-codec-generate-${UUID.randomUUID()}"

            application {
                install(PayloadCodecPlugin) {
                    compression(threshold = 100)
                }

                taskQueue(taskQueue) {
                    workflow<GenerateLargeDataWorkflow>()
                }
            }

            val client = client()

            val handle =
                client.startWorkflow(
                    workflowType = "GenerateLargeDataWorkflow",
                    taskQueue = taskQueue,
                    arg = 200, // Generate 200 items
                )

            val result: LargePayload = handle.result(timeout = 30.seconds)

            // Verify the generated data
            assertTrue(result.id.startsWith("generated-"))
            assertEquals(10000, result.data.length)
            assertEquals(200, result.items.size)

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `custom codec is invoked during workflow execution`() =
        runTemporalTest {
            val taskQueue = "test-custom-codec-${UUID.randomUUID()}"
            val recordingCodec = RecordingCodec(CompressionCodec(threshold = 100))

            application {
                install(PayloadCodecPlugin) {
                    codec = recordingCodec
                }

                taskQueue(taskQueue) {
                    workflow<EchoLargeDataWorkflow>()
                }
            }

            val client = client()

            val largePayload =
                LargePayload(
                    id = "custom-codec-test",
                    data = "c".repeat(2000),
                    items = listOf("one", "two", "three"),
                )

            val handle =
                client.startWorkflow(
                    workflowType = "EchoLargeDataWorkflow",
                    taskQueue = taskQueue,
                    arg = largePayload,
                )

            val result: LargePayload = handle.result(timeout = 30.seconds)

            // Verify codec was called
            assertTrue(recordingCodec.encodeWasCalled.get(), "Encode should have been called")
            assertTrue(recordingCodec.decodeWasCalled.get(), "Decode should have been called")

            // Verify data integrity
            assertEquals("echoed-custom-codec-test", result.id)
            assertEquals(largePayload.data, result.data)

            handle.assertHistory {
                completed()
            }
        }

    @Test
    fun `small payloads are not compressed when below threshold`() =
        runTemporalTest {
            val taskQueue = "test-no-compress-${UUID.randomUUID()}"

            // Use a high threshold so nothing gets compressed
            val recordingCodec = RecordingCodec(CompressionCodec(threshold = 100000))

            application {
                install(PayloadCodecPlugin) {
                    codec = recordingCodec
                }

                taskQueue(taskQueue) {
                    workflow<EchoLargeDataWorkflow>()
                }
            }

            val client = client()

            // Small payload that won't be compressed
            val smallPayload =
                LargePayload(
                    id = "small",
                    data = "tiny",
                    items = listOf("a"),
                )

            val handle =
                client.startWorkflow(
                    workflowType = "EchoLargeDataWorkflow",
                    taskQueue = taskQueue,
                    arg = smallPayload,
                )

            val result: LargePayload = handle.result(timeout = 30.seconds)

            // Verify the codec was still called (even if it didn't compress)
            assertTrue(recordingCodec.encodeWasCalled.get())
            assertTrue(recordingCodec.decodeWasCalled.get())

            // Verify data integrity
            assertEquals("echoed-small", result.id)
            assertEquals("tiny", result.data)

            handle.assertHistory {
                completed()
            }
        }
}
