package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.common.exceptions.RemoteException
import com.surrealdev.temporal.common.failure.FAILURE_SOURCE
import com.surrealdev.temporal.common.failure.buildCause
import com.surrealdev.temporal.common.failure.buildFailureProto
import com.surrealdev.temporal.serialization.CompositePayloadSerializer
import com.surrealdev.temporal.serialization.NoOpCodec
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for recursive cause chain serialization and stack trace preservation
 * through `buildFailureProto` → proto → `buildCause` round-trips.
 */
class CauseChainSerializationTest {
    private val serializer = CompositePayloadSerializer.default()
    private val codec = NoOpCodec

    // ================================================================
    // Cause chain round-trip
    // ================================================================

    @Test
    fun `cause chain with ApplicationFailure causes round-trips through proto`() =
        runTest {
            val innerCause =
                ApplicationFailure.nonRetryable(
                    message = "inner error",
                    type = "InnerType",
                )
            val outerException =
                ApplicationFailure.failure(
                    message = "outer error",
                    type = "OuterType",
                    cause = innerCause,
                )

            val proto = buildFailureProto(outerException, serializer, codec)

            // Proto should have a cause set
            assertTrue(proto.hasCause(), "Proto should have a cause")
            assertTrue(proto.cause.hasApplicationFailureInfo(), "Cause proto should have ApplicationFailureInfo")
            assertEquals("InnerType", proto.cause.applicationFailureInfo.type)
            assertTrue(proto.cause.applicationFailureInfo.nonRetryable)

            // Round-trip back through buildCause
            val reconstructed = buildCause(proto, codec)
            assertIs<ApplicationFailure>(reconstructed)
            assertEquals("OuterType", reconstructed.type)
            assertEquals("outer error", reconstructed.originalMessage)

            val reconstructedCause = reconstructed.cause
            assertIs<ApplicationFailure>(reconstructedCause)
            assertEquals("InnerType", reconstructedCause.type)
            assertEquals("inner error", reconstructedCause.originalMessage)
            assertTrue(reconstructedCause.isNonRetryable)
        }

    @Test
    fun `three-level cause chain preserves all levels`() =
        runTest {
            val level3 =
                ApplicationFailure.failure(
                    message = "level 3",
                    type = "Level3Type",
                )
            val level2 =
                ApplicationFailure.nonRetryable(
                    message = "level 2",
                    type = "Level2Type",
                    cause = level3,
                )
            val level1 =
                ApplicationFailure.failure(
                    message = "level 1",
                    type = "Level1Type",
                    cause = level2,
                )

            val proto = buildFailureProto(level1, serializer, codec)

            // Verify proto chain
            assertTrue(proto.hasCause())
            assertTrue(proto.cause.hasCause())
            assertEquals("Level2Type", proto.cause.applicationFailureInfo.type)
            assertEquals("Level3Type", proto.cause.cause.applicationFailureInfo.type)

            // Round-trip
            val reconstructed = buildCause(proto, codec)
            assertIs<ApplicationFailure>(reconstructed)
            assertEquals("Level1Type", reconstructed.type)

            val r2 = reconstructed.cause
            assertIs<ApplicationFailure>(r2)
            assertEquals("Level2Type", r2.type)
            assertTrue(r2.isNonRetryable)

            val r3 = r2.cause
            assertIs<ApplicationFailure>(r3)
            assertEquals("Level3Type", r3.type)

            assertNull(r3.cause)
        }

    // ================================================================
    // Non-ApplicationFailure causes become RemoteException
    // ================================================================

    @Test
    fun `non-ApplicationFailure cause becomes RemoteException with originalStackTrace`() =
        runTest {
            val innerCause = IllegalArgumentException("bad input")
            val outerException =
                ApplicationFailure.failure(
                    message = "outer",
                    type = "OuterType",
                    cause = innerCause,
                )

            val proto = buildFailureProto(outerException, serializer, codec)

            assertTrue(proto.hasCause())
            // The inner cause should be wrapped with ApplicationFailureInfo (type = qualifiedName)
            assertEquals(
                "java.lang.IllegalArgumentException",
                proto.cause.applicationFailureInfo.type,
            )

            // Round-trip: the inner cause has ApplicationFailureInfo so it becomes ApplicationFailure
            val reconstructed = buildCause(proto, codec)
            assertIs<ApplicationFailure>(reconstructed)

            val reconstructedCause = reconstructed.cause
            assertIs<ApplicationFailure>(reconstructedCause)
            assertEquals("java.lang.IllegalArgumentException", reconstructedCause.type)
            assertEquals("bad input", reconstructedCause.originalMessage)
            assertNotNull(reconstructedCause.originalStackTrace)
        }

    @Test
    fun `buildCause creates RemoteException for failure without ApplicationFailureInfo`() =
        runTest {
            // Manually build a proto Failure without ApplicationFailureInfo, with Kotlin source
            val proto =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("remote error")
                    .setStackTrace("com.example.Foo.bar(Foo.kt:42)")
                    .setSource(FAILURE_SOURCE)
                    .build()

            val result = buildCause(proto, codec)
            assertIs<RemoteException>(result)
            assertEquals("remote error", result.message)
            assertEquals("com.example.Foo.bar(Foo.kt:42)", result.originalStackTrace)
            assertNull(result.cause)

            // Java stack trace should be overridden to the remote trace
            assertTrue(result.stackTrace.isNotEmpty(), "Java stack trace should be overridden")
            assertEquals("com.example.Foo", result.stackTrace[0].className)
            assertEquals("bar", result.stackTrace[0].methodName)
            assertEquals("Foo.kt", result.stackTrace[0].fileName)
            assertEquals(42, result.stackTrace[0].lineNumber)
        }

    @Test
    fun `buildCause does NOT override Java stack trace for non-Kotlin source`() =
        runTest {
            // Manually build a proto Failure with a different source
            val proto =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("remote error")
                    .setStackTrace("com.example.Foo.bar(Foo.java:42)")
                    .setSource("JavaSDK")
                    .build()

            val result = buildCause(proto, codec)
            assertIs<RemoteException>(result)
            assertEquals("remote error", result.message)

            // Java stack trace should NOT be overridden (different source)
            // The stack trace should point to buildCause/test infrastructure, not the remote trace
            val hasRemoteFrame = result.stackTrace.any { it.className == "com.example.Foo" }
            assertTrue(!hasRemoteFrame, "Java stack trace should NOT be overridden for non-Kotlin source")
        }

    // ================================================================
    // originalStackTrace preservation
    // ================================================================

    @Test
    fun `originalStackTrace is populated on reconstructed ApplicationFailure`() =
        runTest {
            val exception =
                ApplicationFailure.failure(
                    message = "test error",
                    type = "TestType",
                )

            val proto = buildFailureProto(exception, serializer, codec)
            // Proto should have a stack trace
            assertTrue(proto.stackTrace.isNotEmpty())

            val reconstructed = buildCause(proto, codec)
            assertIs<ApplicationFailure>(reconstructed)
            val stackTrace = reconstructed.originalStackTrace
            assertNotNull(stackTrace)
            assertTrue(stackTrace.isNotEmpty())

            // Java stack trace should be overridden to contain a frame from this test
            val hasTestFrame =
                reconstructed.stackTrace.any {
                    it.className.contains("CauseChainSerializationTest")
                }
            assertTrue(hasTestFrame, "Java stack trace should contain frame from test, not buildCause")
        }

    @Test
    fun `originalStackTrace is null when proto stack trace is empty`() =
        runTest {
            val proto =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("no stack")
                    .setStackTrace("")
                    .setSource(FAILURE_SOURCE)
                    .setApplicationFailureInfo(
                        io.temporal.api.failure.v1.ApplicationFailureInfo
                            .newBuilder()
                            .setType("TestType")
                            .setNonRetryable(false),
                    ).build()

            val reconstructed = buildCause(proto, codec)
            assertIs<ApplicationFailure>(reconstructed)
            assertNull(reconstructed.originalStackTrace)

            // Java stack trace should NOT be overridden (empty remote trace)
            // The stack trace should point to buildCause/test infrastructure
            val hasBuildCauseFrame =
                reconstructed.stackTrace.any {
                    it.className.contains("FailureConverters") || it.className.contains("CauseChainSerializationTest")
                }
            assertTrue(hasBuildCauseFrame, "Java stack trace should not be overridden when remote trace is empty")
        }

    // ================================================================
    // Wrapper failure info types are skipped
    // ================================================================

    @Test
    fun `buildCause skips childWorkflowExecutionFailureInfo wrapper and returns inner ApplicationFailure`() =
        runTest {
            // Build a proto chain that mimics Core SDK wrapping a child workflow failure:
            // outer: childWorkflowExecutionFailureInfo (wrapper)
            //   cause: applicationFailureInfo (the real error)
            val innerFailure =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("child regular failure")
                    .setStackTrace("com.example.ChildWorkflow.run(ChildWorkflow.kt:10)")
                    .setSource(FAILURE_SOURCE)
                    .setApplicationFailureInfo(
                        io.temporal.api.failure.v1.ApplicationFailureInfo
                            .newBuilder()
                            .setType("java.lang.IllegalStateException")
                            .setNonRetryable(false),
                    ).build()

            val outerFailure =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("Child Workflow execution failed")
                    .setChildWorkflowExecutionFailureInfo(
                        io.temporal.api.failure.v1.ChildWorkflowExecutionFailureInfo
                            .newBuilder()
                            .setWorkflowType(
                                io.temporal.api.common.v1.WorkflowType
                                    .newBuilder()
                                    .setName("ChildWorkflowType"),
                            ),
                    ).setCause(innerFailure)
                    .build()

            val result = buildCause(outerFailure, codec)

            // Should skip the wrapper and return the inner ApplicationFailure directly
            assertIs<ApplicationFailure>(result, "Should be ApplicationFailure, not RemoteException")
            assertEquals("child regular failure", result.originalMessage)
            assertEquals("java.lang.IllegalStateException", result.type)

            // No intermediate RemoteException in the chain
            assertNull(result.cause, "Should not have a nested cause")
        }

    @Test
    fun `buildCause skips activityFailureInfo wrapper and returns inner ApplicationFailure`() =
        runTest {
            val innerFailure =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("activity error")
                    .setSource(FAILURE_SOURCE)
                    .setApplicationFailureInfo(
                        io.temporal.api.failure.v1.ApplicationFailureInfo
                            .newBuilder()
                            .setType("ValidationError")
                            .setNonRetryable(true),
                    ).build()

            val outerFailure =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("Activity task failed")
                    .setActivityFailureInfo(
                        io.temporal.api.failure.v1.ActivityFailureInfo
                            .newBuilder()
                            .setActivityType(
                                io.temporal.api.common.v1.ActivityType
                                    .newBuilder()
                                    .setName("myActivity"),
                            ),
                    ).setCause(innerFailure)
                    .build()

            val result = buildCause(outerFailure, codec)

            assertIs<ApplicationFailure>(result, "Should be ApplicationFailure, not RemoteException")
            assertEquals("activity error", result.originalMessage)
            assertEquals("ValidationError", result.type)
            assertTrue(result.isNonRetryable)
        }

    @Test
    fun `buildCause creates RemoteException for wrapper without cause`() =
        runTest {
            // Edge case: wrapper failure with no cause (shouldn't happen in practice, but defensive)
            val wrapperOnly =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("wrapper without cause")
                    .setChildWorkflowExecutionFailureInfo(
                        io.temporal.api.failure.v1.ChildWorkflowExecutionFailureInfo
                            .newBuilder(),
                    ).build()

            val result = buildCause(wrapperOnly, codec)

            // Without a cause to skip to, falls back to RemoteException
            assertIs<RemoteException>(result)
            assertEquals("wrapper without cause", result.message)
        }

    // ================================================================
    // Depth limit
    // ================================================================

    @Test
    fun `buildFailureProto respects depth limit of 20`() =
        runTest {
            // Build a chain of 25 exceptions
            var exception: Throwable = RuntimeException("leaf")
            repeat(24) { i ->
                exception = RuntimeException("level-$i", exception)
            }

            val proto = buildFailureProto(exception, serializer, codec)

            // Count proto chain depth
            var depth = 0
            var current: io.temporal.api.failure.v1.Failure? = proto
            while (current != null && current.hasCause()) {
                depth++
                current = current.cause
            }

            // Should be capped at 20 (depth 0..19 = 20 nodes, so 19 cause links)
            assertTrue(depth <= 20, "Cause chain depth should not exceed 20, was $depth")
        }

    @Test
    fun `buildCause respects max depth`() =
        runTest {
            // Build a proto chain of 25 levels
            var proto =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("leaf")
                    .build()
            repeat(24) { i ->
                proto =
                    io.temporal.api.failure.v1.Failure
                        .newBuilder()
                        .setMessage("level-$i")
                        .setCause(proto)
                        .build()
            }

            val result = buildCause(proto, codec)

            // Count the exception chain depth
            var depth = 0
            var current: Throwable? = result
            while (current != null) {
                depth++
                current = current.cause
            }

            // Should be capped at 21 (20 recursive levels + 1 terminal node)
            assertTrue(depth <= 21, "Exception chain depth should not exceed 21, was $depth")
        }

    // ================================================================
    // Exception without cause produces proto without cause
    // ================================================================

    @Test
    fun `exception without cause produces proto without cause field`() =
        runTest {
            val exception =
                ApplicationFailure.failure(
                    message = "no cause",
                    type = "NoCauseType",
                )

            val proto = buildFailureProto(exception, serializer, codec)
            assertTrue(!proto.hasCause(), "Proto should not have a cause")
        }
}
