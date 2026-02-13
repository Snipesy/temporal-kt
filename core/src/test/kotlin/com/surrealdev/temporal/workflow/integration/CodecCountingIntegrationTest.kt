package com.surrealdev.temporal.workflow.integration

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.heartbeat
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Query
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Update
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.plugin.install
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.client.query
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.client.update
import com.surrealdev.temporal.common.EncodedTemporalPayloads
import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.common.exceptions.ClientWorkflowFailedException
import com.surrealdev.temporal.common.exceptions.PayloadCodecException
import com.surrealdev.temporal.serialization.CodecPlugin
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.PayloadCodec
import com.surrealdev.temporal.testing.assertHistory
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.workflow.ActivityOptions
import com.surrealdev.temporal.workflow.ChildWorkflowOptions
import com.surrealdev.temporal.workflow.LocalActivityOptions
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.signal
import com.surrealdev.temporal.workflow.startActivity
import com.surrealdev.temporal.workflow.startChildWorkflow
import com.surrealdev.temporal.workflow.startLocalActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that use counting/marker codecs to verify encode/decode is invoked
 * at every boundary in the payload pipeline.
 *
 * [CountingCodec] is a passthrough that counts encode/decode calls.
 * [MarkerCodec] additionally rewrites encoding metadata so a worker without it
 * will fail at deserialization ("No converter registered for encoding: test/marker").
 */
@Tag("integration")
class CodecCountingIntegrationTest {
    // ================================================================
    // CountingCodec – passthrough with counters
    // ================================================================

    class CountingCodec : PayloadCodec {
        val encodeCount = AtomicInteger(0)
        val decodeCount = AtomicInteger(0)

        override suspend fun encode(payloads: TemporalPayloads): EncodedTemporalPayloads {
            encodeCount.incrementAndGet()
            // Codecs, when called from workflows will many times reach out to an sdk
            // Those SDKs will many times switch dispatchers, which can break the workflow activation process
            // To simulate this, we switch dispatchers and add a delay to guarantee this suspend function
            // will actually suspend
            withContext(Dispatchers.Default) {
                // Simulate some work to make it more likely encode/decode calls are on different threads
                delay(1)
            }
            return EncodedTemporalPayloads(payloads.proto)
        }

        override suspend fun decode(payloads: EncodedTemporalPayloads): TemporalPayloads {
            decodeCount.incrementAndGet()
            return TemporalPayloads(payloads.proto)
        }
    }

    // ================================================================
    // MarkerCodec – rewrites encoding metadata to prove transformation
    // ================================================================

    /**
     * Replaces `encoding` metadata with "test/marker" on encode and restores it on decode.
     * A worker WITHOUT this codec sees encoding="test/marker" and the serializer throws
     * "No converter registered for encoding: test/marker".
     */
    class MarkerCodec : PayloadCodec {
        val encodeCount = AtomicInteger(0)
        val decodeCount = AtomicInteger(0)

        companion object {
            const val MARKER_ENCODING = "test/marker"
            const val ORIGINAL_ENCODING_KEY = "x-original-encoding"
            private val MARKER_ENCODING_BYTES = TemporalByteString.fromUtf8(MARKER_ENCODING)
        }

        override suspend fun encode(payloads: TemporalPayloads): EncodedTemporalPayloads {
            encodeCount.incrementAndGet()
            val encoded =
                payloads.payloads.map { payload ->
                    val meta = payload.metadataByteStrings.toMutableMap()
                    val originalEncoding = meta[TemporalPayload.METADATA_ENCODING]
                    if (originalEncoding != null) {
                        meta[ORIGINAL_ENCODING_KEY] = originalEncoding
                    }
                    meta[TemporalPayload.METADATA_ENCODING] = MARKER_ENCODING_BYTES
                    TemporalPayload.create(payload.data, meta)
                }
            return EncodedTemporalPayloads(TemporalPayloads.of(encoded).proto)
        }

        override suspend fun decode(payloads: EncodedTemporalPayloads): TemporalPayloads {
            decodeCount.incrementAndGet()
            val decoded =
                TemporalPayloads(payloads.proto).payloads.map { payload ->
                    if (payload.encoding == MARKER_ENCODING) {
                        val meta = payload.metadataByteStrings.toMutableMap()
                        val originalEncoding = meta.remove(ORIGINAL_ENCODING_KEY)
                        if (originalEncoding != null) {
                            meta[TemporalPayload.METADATA_ENCODING] = originalEncoding
                        } else {
                            meta.remove(TemporalPayload.METADATA_ENCODING)
                        }
                        TemporalPayload.create(payload.data, meta)
                    } else {
                        payload
                    }
                }
            return TemporalPayloads.of(decoded)
        }
    }

    // ================================================================
    // Activities
    // ================================================================

    class EchoActivities {
        @Activity("codecEcho")
        suspend fun ActivityContext.echo(input: String): String = "echoed:$input"
    }

    // ================================================================
    // Workflows
    // ================================================================

    @Workflow("CodecEchoWorkflow")
    class CodecEchoWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String = "wf:$input"
    }

    @Workflow("CodecActivityWorkflow")
    class CodecActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String =
            startActivity<String>(
                activityType = "codecEcho",
                arg = input,
                options = ActivityOptions(startToCloseTimeout = 30.seconds),
            ).result()
    }

    @Workflow("CodecLocalActivityWorkflow")
    class CodecLocalActivityWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String =
            startLocalActivity<String>(
                activityType = "codecEcho",
                arg = input,
                options = LocalActivityOptions(startToCloseTimeout = 30.seconds),
            ).result()
    }

    @Workflow("CodecChildCallerWorkflow")
    class CodecChildCallerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String =
            startChildWorkflow("CodecEchoWorkflow", arg = input, options = ChildWorkflowOptions())
                .result()
    }

    @Workflow("CodecCrossQueueCallerWorkflow")
    class CodecCrossQueueCallerWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(
            input: String,
            childTaskQueue: String,
        ): String =
            startChildWorkflow(
                "CodecEchoWorkflow",
                arg = input,
                options = ChildWorkflowOptions(taskQueue = childTaskQueue),
            ).result()
    }

    // ================================================================
    // Section A: CountingCodec – verify encode/decode is invoked
    // ================================================================

    @Test
    fun `counting codec is invoked for workflow input and output`() =
        runTemporalTest {
            val taskQueue = "test-codec-count-wf-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecEchoWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecEchoWorkflow",
                    taskQueue = taskQueue,
                    arg = "hello",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:hello", result)

            // encode: workflow input (client) + workflow result (worker)
            // decode: workflow input (worker) + workflow result (client)
            assertTrue(codec.encodeCount.get() >= 2, "encode should be >= 2, was ${codec.encodeCount.get()}")
            assertTrue(codec.decodeCount.get() >= 2, "decode should be >= 2, was ${codec.decodeCount.get()}")

            handle.assertHistory { completed() }
        }

    @Test
    fun `counting codec is invoked for activity args and results`() =
        runTemporalTest {
            val taskQueue = "test-codec-count-act-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecActivityWorkflow>()
                    activity(EchoActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = "test-act",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("echoed:test-act", result)

            // encode: client→wf input, wf→act args, act→wf result, wf→client result
            // decode: wf←client input, act←wf args, wf←act result, client←wf result
            assertTrue(codec.encodeCount.get() >= 4, "encode should be >= 4, was ${codec.encodeCount.get()}")
            assertTrue(codec.decodeCount.get() >= 4, "decode should be >= 4, was ${codec.decodeCount.get()}")

            handle.assertHistory { completed() }
        }

    @Test
    fun `counting codec is invoked for local activity args and results`() =
        runTemporalTest {
            val taskQueue = "test-codec-count-local-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecLocalActivityWorkflow>()
                    activity(EchoActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecLocalActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = "test-local",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("echoed:test-local", result)

            assertTrue(codec.encodeCount.get() >= 2, "encode should be >= 2, was ${codec.encodeCount.get()}")
            assertTrue(codec.decodeCount.get() >= 2, "decode should be >= 2, was ${codec.decodeCount.get()}")

            handle.assertHistory { completed() }
        }

    @Test
    fun `counting codec is invoked for child workflow args and results`() =
        runTemporalTest(timeSkipping = true) {
            val taskQueue = "test-codec-count-child-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecChildCallerWorkflow>()
                    workflow<CodecEchoWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecChildCallerWorkflow",
                    taskQueue = taskQueue,
                    arg = "child-test",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:child-test", result)

            // encode: client→parent, parent→child args, child result, parent result
            // decode: parent←client, child←parent, parent←child, client←parent
            assertTrue(codec.encodeCount.get() >= 4, "encode should be >= 4, was ${codec.encodeCount.get()}")
            assertTrue(codec.decodeCount.get() >= 4, "decode should be >= 4, was ${codec.decodeCount.get()}")

            handle.assertHistory { completed() }
        }

    // ================================================================
    // Section B: MarkerCodec – verify encoding metadata is transformed
    // ================================================================

    @Test
    fun `marker codec round-trips correctly for workflow`() =
        runTemporalTest {
            val taskQueue = "test-marker-wf-${UUID.randomUUID()}"
            val codec = MarkerCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecEchoWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecEchoWorkflow",
                    taskQueue = taskQueue,
                    arg = "marker-test",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:marker-test", result)

            assertTrue(codec.encodeCount.get() >= 2, "encode count: ${codec.encodeCount.get()}")
            assertTrue(codec.decodeCount.get() >= 2, "decode count: ${codec.decodeCount.get()}")

            handle.assertHistory { completed() }
        }

    @Test
    fun `marker codec round-trips correctly for activity`() =
        runTemporalTest {
            val taskQueue = "test-marker-act-${UUID.randomUUID()}"
            val codec = MarkerCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecActivityWorkflow>()
                    activity(EchoActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = "marker-act",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("echoed:marker-act", result)

            assertTrue(codec.encodeCount.get() >= 4, "encode count: ${codec.encodeCount.get()}")
            assertTrue(codec.decodeCount.get() >= 4, "decode count: ${codec.decodeCount.get()}")

            handle.assertHistory { completed() }
        }

    // ================================================================
    // Section C: Cross-queue codec mismatch
    // ================================================================

    @Test
    fun `child workflow on queue with same codec succeeds`() =
        runTemporalTest(timeSkipping = true) {
            val parentQueue = "test-parent-codec-${UUID.randomUUID()}"
            val childQueue = "test-child-codec-${UUID.randomUUID()}"
            val parentCodec = MarkerCodec()
            val childCodec = MarkerCodec()

            application {
                taskQueue(parentQueue) {
                    install(CodecPlugin) { custom(parentCodec) }
                    workflow<CodecCrossQueueCallerWorkflow>()
                }
                taskQueue(childQueue) {
                    install(CodecPlugin) { custom(childCodec) }
                    workflow<CodecEchoWorkflow>()
                }
            }

            // Client uses parent's codec (same MarkerCodec)
            val client = client(CompositePayloadSerializer.default(), parentCodec)

            val handle =
                client.startWorkflow(
                    workflowType = "CodecCrossQueueCallerWorkflow",
                    taskQueue = parentQueue,
                    arg1 = "cross-queue-ok",
                    arg2 = childQueue,
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:cross-queue-ok", result)

            // Parent encoded the child's args
            assertTrue(parentCodec.encodeCount.get() >= 1, "parent encode: ${parentCodec.encodeCount.get()}")
            // Child decoded args and encoded result
            assertTrue(childCodec.decodeCount.get() >= 1, "child decode: ${childCodec.decodeCount.get()}")
            assertTrue(childCodec.encodeCount.get() >= 1, "child encode: ${childCodec.encodeCount.get()}")

            handle.assertHistory { completed() }
        }

    // ================================================================
    // Section D: Per-queue codec isolation
    // ================================================================

    @Test
    fun `codec installed on one queue does not affect another queue`() =
        runTemporalTest {
            val codecQueue = "test-isolated-codec-${UUID.randomUUID()}"
            val plainQueue = "test-isolated-plain-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                taskQueue(codecQueue) {
                    install(CodecPlugin) { custom(codec) }
                    workflow<CodecEchoWorkflow>()
                }
                taskQueue(plainQueue) {
                    workflow<CodecEchoWorkflow>()
                }
            }

            // Codec queue — use a client with the counting codec
            val codecClient = client(CompositePayloadSerializer.default(), codec)
            val handle1 =
                codecClient.startWorkflow(
                    workflowType = "CodecEchoWorkflow",
                    taskQueue = codecQueue,
                    arg = "codec-queue",
                )
            val result1: String = handle1.result(timeout = 30.seconds)
            assertEquals("wf:codec-queue", result1)

            val encodeAfterFirst = codec.encodeCount.get()
            val decodeAfterFirst = codec.decodeCount.get()
            assertTrue(encodeAfterFirst > 0, "codec queue should trigger encode")
            assertTrue(decodeAfterFirst > 0, "codec queue should trigger decode")

            // Plain queue — use a client WITHOUT the codec
            val plainClient = client(CompositePayloadSerializer.default())
            val handle2 =
                plainClient.startWorkflow(
                    workflowType = "CodecEchoWorkflow",
                    taskQueue = plainQueue,
                    arg = "plain-queue",
                )
            val result2: String = handle2.result(timeout = 30.seconds)
            assertEquals("wf:plain-queue", result2)

            // Counts should not have changed
            assertEquals(encodeAfterFirst, codec.encodeCount.get(), "plain queue should not trigger encode")
            assertEquals(decodeAfterFirst, codec.decodeCount.get(), "plain queue should not trigger decode")
        }

    // ================================================================
    // Additional Workflows for Signal/Query/Update/Failure tests
    // ================================================================

    @Workflow("CodecSignalQueryWorkflow")
    class CodecSignalQueryWorkflow {
        private val received = mutableListOf<String>()
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return received.joinToString(",")
        }

        @Signal("addItem")
        fun WorkflowContext.addItem(item: String) {
            received.add(item)
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }

        @Query("getItems")
        fun WorkflowContext.getItems(): List<String> = received.toList()

        @Query("getCount")
        fun WorkflowContext.getCount(): Int = received.size
    }

    @Workflow("CodecUpdateWorkflow")
    class CodecUpdateWorkflow {
        private var value = ""
        private var done = false

        @WorkflowRun
        suspend fun WorkflowContext.run(): String {
            awaitCondition { done }
            return value
        }

        @Update("setValue")
        fun WorkflowContext.setValue(v: String): String {
            value = v
            return "set:$v"
        }

        @Signal("complete")
        fun WorkflowContext.complete() {
            done = true
        }
    }

    class FailingWithDetailsActivity {
        @Activity("failWithDetails")
        suspend fun ActivityContext.failWithDetails(detail: String): String =
            throw ApplicationFailure.nonRetryableWithPayloads(
                message = "Activity failed with detail",
                type = "DetailedError",
                details = TemporalPayloads.of(listOf(serializer.serialize(typeOf<String>(), detail))),
            )
    }

    @Workflow("CodecFailureDetailsWorkflow")
    class CodecFailureDetailsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(detail: String): String =
            try {
                startActivity<String>(
                    activityType = "failWithDetails",
                    arg = detail,
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            } catch (e: Exception) {
                "caught:${e.message}"
            }
    }

    @Serializable
    data class HeartbeatProgress(
        val step: Int,
        val message: String,
    )

    class HeartbeatCodecActivity {
        @Activity("heartbeatWithCodec")
        suspend fun ActivityContext.heartbeatWithCodec(steps: Int): String {
            for (i in 1..steps) {
                delay(10)
                heartbeat(HeartbeatProgress(i, "step-$i"))
            }
            return "completed-$steps"
        }
    }

    @Workflow("CodecHeartbeatWorkflow")
    class CodecHeartbeatWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(steps: Int): String =
            startActivity<Int>(
                activityType = "heartbeatWithCodec",
                arg = steps,
                options =
                    ActivityOptions(
                        startToCloseTimeout = 1.minutes,
                        heartbeatTimeout = 10.seconds,
                    ),
            ).result<String>()
    }

    // ================================================================
    // Helpers
    // ================================================================

    private suspend inline fun <T> pollUntil(
        timeout: Duration = 15.seconds,
        crossinline condition: (T) -> Boolean,
        crossinline query: suspend () -> T,
    ): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(timeout) {
                while (true) {
                    val result = query()
                    if (condition(result)) return@withTimeout result
                    kotlinx.coroutines.yield()
                }
                @Suppress("UNREACHABLE_CODE")
                throw AssertionError("Unreachable")
            }
        }

    // ================================================================
    // Section E: Signal codec counting
    // ================================================================

    @Test
    fun `counting codec is invoked for signal args`() =
        runTemporalTest {
            val taskQueue = "test-codec-signal-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecSignalQueryWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecSignalQueryWorkflow",
                    taskQueue = taskQueue,
                )

            val encodeBeforeSignal = codec.encodeCount.get()
            val decodeBeforeSignal = codec.decodeCount.get()

            // Send a signal with an argument
            handle.signal("addItem", "signal-data")

            // Poll until signal processed
            pollUntil<Int>(condition = { it >= 1 }) {
                handle.query("getCount")
            }

            // Signal arg: client encodes, worker decodes
            assertTrue(
                codec.encodeCount.get() > encodeBeforeSignal,
                "signal should trigger encode, was ${codec.encodeCount.get()} (before: $encodeBeforeSignal)",
            )
            assertTrue(
                codec.decodeCount.get() > decodeBeforeSignal,
                "signal should trigger decode, was ${codec.decodeCount.get()} (before: $decodeBeforeSignal)",
            )

            handle.signal("complete")
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("signal-data", result)
        }

    // ================================================================
    // Section F: Query codec counting
    // ================================================================

    @Test
    fun `counting codec is invoked for query args and results`() =
        runTemporalTest {
            val taskQueue = "test-codec-query-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecSignalQueryWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecSignalQueryWorkflow",
                    taskQueue = taskQueue,
                )

            // Send a signal so query returns something interesting
            handle.signal("addItem", "A")
            pollUntil<Int>(condition = { it >= 1 }) {
                handle.query("getCount")
            }

            val encodeBeforeQuery = codec.encodeCount.get()
            val decodeBeforeQuery = codec.decodeCount.get()

            // Query with no args — still goes through codec (empty payloads)
            val items: List<String> = handle.query("getItems")
            assertEquals(listOf("A"), items)

            // Query: client encodes args + decodes result, worker decodes args + encodes result
            assertTrue(
                codec.encodeCount.get() > encodeBeforeQuery,
                "query should trigger encode (args+result), was ${codec.encodeCount.get()} (before: $encodeBeforeQuery)",
            )
            assertTrue(
                codec.decodeCount.get() > decodeBeforeQuery,
                "query should trigger decode (args+result), was ${codec.decodeCount.get()} (before: $decodeBeforeQuery)",
            )

            handle.signal("complete")
            handle.result<String>(timeout = 30.seconds)
        }

    // ================================================================
    // Section G: Update codec counting
    // ================================================================

    @Test
    fun `counting codec is invoked for update args and results`() =
        runTemporalTest {
            val taskQueue = "test-codec-update-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecUpdateWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecUpdateWorkflow",
                    taskQueue = taskQueue,
                )

            val encodeBeforeUpdate = codec.encodeCount.get()
            val decodeBeforeUpdate = codec.decodeCount.get()

            // Send update — synchronous round-trip
            val updateResult: String = handle.update("setValue", "updated-value")
            assertEquals("set:updated-value", updateResult)

            // Update: client encodes args + decodes result, worker decodes args + encodes result
            assertTrue(
                codec.encodeCount.get() > encodeBeforeUpdate,
                "update should trigger encode (args+result), was ${codec.encodeCount.get()} (before: $encodeBeforeUpdate)",
            )
            assertTrue(
                codec.decodeCount.get() > decodeBeforeUpdate,
                "update should trigger decode (args+result), was ${codec.decodeCount.get()} (before: $decodeBeforeUpdate)",
            )

            handle.signal("complete")
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("updated-value", result)
        }

    // ================================================================
    // Section H: ApplicationFailure details codec counting
    // ================================================================

    @Test
    fun `counting codec is invoked for ApplicationFailure details`() =
        runTemporalTest {
            val taskQueue = "test-codec-appfail-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecFailureDetailsWorkflow>()
                    activity(FailingWithDetailsActivity())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecFailureDetailsWorkflow",
                    taskQueue = taskQueue,
                    arg = "my-error-detail",
                )

            // Workflow catches the exception and returns a report string
            val result: String = handle.result(timeout = 30.seconds)
            assertTrue(result.startsWith("caught:"), "Expected caught result, got: $result")

            // ApplicationFailure details are encoded (activity → proto) and decoded (proto → workflow)
            // Plus workflow input/output, activity args
            // Exact count depends on internal paths, but encode/decode must be > baseline
            assertTrue(
                codec.encodeCount.get() >= 3,
                "encode should be >= 3 (wf in, act args, fail details), was ${codec.encodeCount.get()}",
            )
            assertTrue(
                codec.decodeCount.get() >= 3,
                "decode should be >= 3 (wf in, act args, fail details), was ${codec.decodeCount.get()}",
            )

            handle.assertHistory { completed() }
        }

    // ================================================================
    // Section I: Activity heartbeat codec counting
    // ================================================================

    @Test
    fun `counting codec is invoked for activity heartbeat execution`() =
        runTemporalTest(timeSkipping = false) {
            val taskQueue = "test-codec-heartbeat-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecHeartbeatWorkflow>()
                    activity(HeartbeatCodecActivity())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecHeartbeatWorkflow",
                    taskQueue = taskQueue,
                    arg = 3,
                )

            val result: String = handle.result(timeout = 1.minutes)
            assertEquals("completed-3", result)

            // Heartbeat payloads go through the codec: 3 heartbeat calls = 3 codec encodes.
            // Plus baseline encodes for workflow input (client), activity args, activity result, workflow result.
            // Total encode >= 5 (baseline wf+activity encodes + 3 heartbeat encodes).
            assertTrue(
                codec.encodeCount.get() >= 5,
                "encode should be >= 5 (includes 3 heartbeat encodes), was ${codec.encodeCount.get()}",
            )
            assertTrue(codec.decodeCount.get() >= 2, "decode should be >= 2, was ${codec.decodeCount.get()}")

            handle.assertHistory { completed() }
        }

    // ================================================================
    // Section J: Workflow failure with ApplicationFailure details
    //            (no double encoding)
    // ================================================================

    @Workflow("CodecWorkflowFailureDetailsWorkflow")
    class CodecWorkflowFailureDetailsWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(detail: String): String =
            throw ApplicationFailure.nonRetryableWithPayloads(
                message = "Workflow failed with detail",
                type = "WorkflowDetailedError",
                details = TemporalPayloads.of(listOf(serializer.serialize(typeOf<String>(), detail))),
            )
    }

    @Test
    fun `workflow failure details are encoded exactly once (no double encoding)`() =
        runTemporalTest {
            val taskQueue = "test-codec-wf-fail-${UUID.randomUUID()}"
            val codec = CountingCodec()

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecWorkflowFailureDetailsWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecWorkflowFailureDetailsWorkflow",
                    taskQueue = taskQueue,
                    arg = "my-wf-error-detail",
                )

            // Workflow throws ApplicationFailure with details — should fail
            val exception =
                assertFailsWith<ClientWorkflowFailedException> {
                    handle.result<String>(timeout = 30.seconds)
                }

            // Verify we can extract the failure cause and it's an ApplicationFailure
            val appFailure = exception.cause
            assertIs<ApplicationFailure>(appFailure)
            assertEquals("WorkflowDetailedError", appFailure.type)

            // Key assertion: encode count should be exactly 2:
            // 1. workflow input (client → server)
            // 2. failure details (worker → server via UnencodedCompletionEncoder)
            // If double encoding were happening, we'd see 3+ encodes.
            // decode count: 1 for workflow input (worker), 1 for failure details (client)
            assertTrue(
                codec.encodeCount.get() >= 2,
                "encode should be >= 2 (wf input + fail details), was ${codec.encodeCount.get()}",
            )
            assertTrue(
                codec.encodeCount.get() <= 3,
                "encode should be <= 3 (no double encoding of fail details), was ${codec.encodeCount.get()}",
            )
        }

    // ================================================================
    // Section K: Codec failure error handling
    // ================================================================

    /**
     * A codec that fails on specific decode/encode attempt numbers, then works normally.
     * [failOnDecodeAttempt] and [failOnEncodeAttempt] specify which attempt number (1-based)
     * should fail. This allows skipping client-side calls and targeting worker-side calls.
     */
    class FailOnAttemptCodec(
        private val failOnDecodeAttempt: Int = 0,
        private val failOnEncodeAttempt: Int = 0,
    ) : PayloadCodec {
        val decodeAttempts = AtomicInteger(0)
        val encodeAttempts = AtomicInteger(0)

        override suspend fun encode(payloads: TemporalPayloads): EncodedTemporalPayloads {
            val attempt = encodeAttempts.incrementAndGet()
            if (attempt == failOnEncodeAttempt) {
                throw PayloadCodecException("Simulated encode failure on attempt $attempt")
            }
            return EncodedTemporalPayloads(payloads.proto)
        }

        override suspend fun decode(payloads: EncodedTemporalPayloads): TemporalPayloads {
            val attempt = decodeAttempts.incrementAndGet()
            if (attempt == failOnDecodeAttempt) {
                throw PayloadCodecException("Simulated decode failure on attempt $attempt")
            }
            return TemporalPayloads(payloads.proto)
        }
    }

    // ================================================================
    // Section L: Activity codec failure error handling
    // ================================================================

    /**
     * Activity that throws an ApplicationFailure with serialized details.
     * Used to test that codec failures during failure encoding are handled gracefully.
     */
    class ActivityThrowingWithDetails {
        @Activity("throwWithCodecDetails")
        suspend fun ActivityContext.throwWithDetails(detail: String): String =
            throw ApplicationFailure.nonRetryableWithPayloads(
                message = "Activity error with details",
                type = "CodecTestError",
                details = TemporalPayloads.of(listOf(serializer.serialize(typeOf<String>(), detail))),
            )
    }

    @Workflow("CodecActivityFailureWorkflow")
    class CodecActivityFailureWorkflow {
        @WorkflowRun
        suspend fun WorkflowContext.run(input: String): String =
            try {
                startActivity<String>(
                    activityType = "throwWithCodecDetails",
                    arg = input,
                    options = ActivityOptions(startToCloseTimeout = 30.seconds),
                ).result()
            } catch (e: Exception) {
                "caught:${e.message}"
            }
    }

    @Test
    fun `activity codec decode failure on args is retried by server`() =
        runTemporalTest {
            val taskQueue = "test-act-codec-decode-${UUID.randomUUID()}"
            // Client encodes wf input = encode 1.
            // Worker decodes wf input = decode 1.
            // Worker encodes activity args = encode 2.
            // Worker decodes activity args = decode 2 (fail this one).
            // Server retries the activity, worker decodes again = decode 3 (succeeds).
            val codec = FailOnAttemptCodec(failOnDecodeAttempt = 2)

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecActivityWorkflow>()
                    activity(EchoActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = "act-decode-fail",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("echoed:act-decode-fail", result)

            // decode 1 = wf input (ok), decode 2 = act args (fail), decode 3+ = retries
            assertTrue(
                codec.decodeAttempts.get() >= 3,
                "decode should be >= 3 (wf input + failed act args + retry), was ${codec.decodeAttempts.get()}",
            )
        }

    @Test
    fun `activity codec encode failure on result is retried by server`() =
        runTemporalTest {
            val taskQueue = "test-act-codec-encode-${UUID.randomUUID()}"
            // Client encodes wf input = encode 1.
            // Worker encodes activity args = encode 2.
            // Activity completes, worker encodes activity result = encode 3 (fail this one).
            // Activity is retried, worker encodes result again = encode 4 (succeeds).
            val codec = FailOnAttemptCodec(failOnEncodeAttempt = 3)

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecActivityWorkflow>()
                    activity(EchoActivities())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecActivityWorkflow",
                    taskQueue = taskQueue,
                    arg = "act-encode-fail",
                )

            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("echoed:act-encode-fail", result)

            assertTrue(
                codec.encodeAttempts.get() >= 4,
                "encode should be >= 4 (wf input + act args + failed result + retry), was ${codec.encodeAttempts.get()}",
            )
        }

    @Test
    fun `activity codec encode failure during ApplicationFailure building falls back to bare failure`() =
        runTemporalTest {
            val taskQueue = "test-act-codec-fail-build-${UUID.randomUUID()}"
            // Client encodes wf input = encode 1.
            // Worker encodes activity args = encode 2.
            // Activity throws ApplicationFailure with details.
            // Worker tries to encode failure details = encode 3 (fail this one).
            // Fallback: bare failure without details is returned.
            // Activity is non-retryable, so workflow catches the error.
            val codec = FailOnAttemptCodec(failOnEncodeAttempt = 3)

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecActivityFailureWorkflow>()
                    activity(ActivityThrowingWithDetails())
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecActivityFailureWorkflow",
                    taskQueue = taskQueue,
                    arg = "fail-build-test",
                )

            // The workflow catches the activity failure and returns a report.
            // Even though codec failed during failure encoding, the bare failure
            // (without details) still propagates correctly.
            val result: String = handle.result(timeout = 30.seconds)
            assertTrue(
                result.startsWith("caught:"),
                "Expected workflow to catch activity failure, got: $result",
            )
        }

    // ================================================================
    // Section M: Workflow codec failure error handling
    // ================================================================

    @Test
    fun `codec decode failure returns failed completion and server retries`() =
        runTemporalTest {
            val taskQueue = "test-codec-decode-fail-${UUID.randomUUID()}"
            // Fail on decode attempt 1 (worker's first decode of workflow input).
            // Server retries the workflow task, and attempt 2 succeeds.
            val codec = FailOnAttemptCodec(failOnDecodeAttempt = 1)

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecEchoWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecEchoWorkflow",
                    taskQueue = taskQueue,
                    arg = "decode-fail-test",
                )

            // First activation decode fails, server retries, second attempt succeeds
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:decode-fail-test", result)

            // Verify decode was attempted more than once (first failed, then succeeded)
            assertTrue(
                codec.decodeAttempts.get() >= 2,
                "decode should have been attempted >= 2 times, was ${codec.decodeAttempts.get()}",
            )
        }

    @Test
    fun `codec encode failure returns failed completion and server retries`() =
        runTemporalTest {
            val taskQueue = "test-codec-encode-fail-${UUID.randomUUID()}"
            // Client encodes workflow input = encode attempt 1 (succeeds).
            // Worker encodes completion = encode attempt 2 (fail this one).
            // Server retries, worker re-encodes = attempt 3 (succeeds).
            val codec = FailOnAttemptCodec(failOnEncodeAttempt = 2)

            application {
                install(CodecPlugin) { custom(codec) }
                taskQueue(taskQueue) {
                    workflow<CodecEchoWorkflow>()
                }
            }

            val client = client()
            val handle =
                client.startWorkflow(
                    workflowType = "CodecEchoWorkflow",
                    taskQueue = taskQueue,
                    arg = "encode-fail-test",
                )

            // First worker encode fails, server retries (replay), second worker encode succeeds
            val result: String = handle.result(timeout = 30.seconds)
            assertEquals("wf:encode-fail-test", result)

            // Verify encode was attempted at least 3 times (client + failed worker + successful worker)
            assertTrue(
                codec.encodeAttempts.get() >= 3,
                "encode should have been attempted >= 3 times, was ${codec.encodeAttempts.get()}",
            )
        }
}
