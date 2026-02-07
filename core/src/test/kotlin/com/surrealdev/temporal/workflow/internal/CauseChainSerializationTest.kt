package com.surrealdev.temporal.workflow.internal

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.common.exceptions.RemoteException
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
@OptIn(InternalTemporalApi::class)
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
            assertEquals("outer error", reconstructed.message)

            val reconstructedCause = reconstructed.cause
            assertIs<ApplicationFailure>(reconstructedCause)
            assertEquals("InnerType", reconstructedCause.type)
            assertEquals("inner error", reconstructedCause.message)
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
            assertEquals("bad input", reconstructedCause.message)
            assertNotNull(reconstructedCause.originalStackTrace)
        }

    @Test
    fun `buildCause creates RemoteException for failure without ApplicationFailureInfo`() =
        runTest {
            // Manually build a proto Failure without ApplicationFailureInfo
            val proto =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("remote error")
                    .setStackTrace("at com.example.Foo.bar(Foo.kt:42)")
                    .build()

            val result = buildCause(proto, codec)
            assertIs<RemoteException>(result)
            assertEquals("remote error", result.message)
            assertEquals("at com.example.Foo.bar(Foo.kt:42)", result.originalStackTrace)
            assertNull(result.cause)
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
        }

    @Test
    fun `originalStackTrace is null when proto stack trace is empty`() =
        runTest {
            val proto =
                io.temporal.api.failure.v1.Failure
                    .newBuilder()
                    .setMessage("no stack")
                    .setStackTrace("")
                    .setApplicationFailureInfo(
                        io.temporal.api.failure.v1.ApplicationFailureInfo
                            .newBuilder()
                            .setType("TestType")
                            .setNonRetryable(false),
                    ).build()

            val reconstructed = buildCause(proto, codec)
            assertIs<ApplicationFailure>(reconstructed)
            assertNull(reconstructed.originalStackTrace)
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
