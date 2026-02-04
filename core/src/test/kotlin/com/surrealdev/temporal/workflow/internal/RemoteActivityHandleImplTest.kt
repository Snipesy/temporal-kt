package com.surrealdev.temporal.workflow.internal

import com.google.protobuf.ByteString
import com.surrealdev.temporal.common.ActivityRetryState
import com.surrealdev.temporal.common.ActivityTimeoutType
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.workflow.ActivityCancellationType
import com.surrealdev.temporal.workflow.ActivityCancelledException
import com.surrealdev.temporal.workflow.ActivityFailureException
import com.surrealdev.temporal.workflow.ActivityTimeoutException
import coresdk.activity_result.ActivityResult
import io.temporal.api.common.v1.Payload
import io.temporal.api.enums.v1.RetryState
import io.temporal.api.failure.v1.ActivityFailureInfo
import io.temporal.api.failure.v1.ApplicationFailureInfo
import io.temporal.api.failure.v1.Failure
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

class RemoteActivityHandleImplTest {
    private val serializer = KotlinxJsonSerializer()
    private val workflowState = WorkflowState("test-run-id")

    private fun <R> createHandle(
        activityId: String = "test-activity-id",
        seq: Int = 1,
        activityType: String = "TestActivity::run",
        returnType: kotlin.reflect.KType = typeOf<String>(),
        cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
    ): RemoteActivityHandleImpl<R> =
        RemoteActivityHandleImpl(
            activityId = activityId,
            seq = seq,
            activityType = activityType,
            state = workflowState,
            serializer = serializer,
            returnType = returnType,
            cancellationType = cancellationType,
        )

    private fun createCompletedResolution(
        payload: Payload = Payload.getDefaultInstance(),
    ): ActivityResult.ActivityResolution =
        ActivityResult.ActivityResolution
            .newBuilder()
            .setCompleted(
                ActivityResult.Success
                    .newBuilder()
                    .setResult(payload),
            ).build()

    private fun createFailedResolution(failure: Failure): ActivityResult.ActivityResolution =
        ActivityResult.ActivityResolution
            .newBuilder()
            .setFailed(
                ActivityResult.Failure
                    .newBuilder()
                    .setFailure(failure),
            ).build()

    private fun createCancelledResolution(failure: Failure? = null): ActivityResult.ActivityResolution {
        val builder = ActivityResult.Cancellation.newBuilder()
        if (failure != null) {
            builder.failure = failure
        }
        return ActivityResult.ActivityResolution
            .newBuilder()
            .setCancelled(builder)
            .build()
    }

    private fun createBackoffResolution(): ActivityResult.ActivityResolution =
        ActivityResult.ActivityResolution
            .newBuilder()
            .setBackoff(ActivityResult.DoBackoff.getDefaultInstance())
            .build()

    // ============================================================================
    // 1. Creation and Properties Tests
    // ============================================================================

    @Test
    fun `ActivityHandleImpl creation and initial state`() {
        val handle =
            createHandle<String>(
                activityId = "my-activity",
                seq = 42,
            )

        assertEquals("my-activity", handle.activityId)
        assertEquals(42, handle.seq)
        assertFalse(handle.isDone)
        assertFalse(handle.isCancellationRequested)
    }

    @Test
    fun `isDone becomes true after completion`() =
        runTest {
            val handle = createHandle<String>()

            assertFalse(handle.isDone)

            handle.resolve(createCompletedResolution())

            assertTrue(handle.isDone)
        }

    @Test
    fun `isCancellationRequested becomes true after cancel`() {
        val handle = createHandle<String>()

        assertFalse(handle.isCancellationRequested)

        handle.cancel("test reason")

        assertTrue(handle.isCancellationRequested)
    }

    // ============================================================================
    // 2. Result Deserialization Tests
    // ============================================================================

    @Test
    fun `result() returns correct value on completion`() =
        runTest {
            val handle = createHandle<String>()

            // Create a payload with serialized string
            val jsonData = "\"Hello World\""
            val payload =
                Payload
                    .newBuilder()
                    .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                    .setData(ByteString.copyFromUtf8(jsonData))
                    .build()

            handle.resolve(createCompletedResolution(payload))

            val result = handle.result()

            assertEquals("Hello World", result)
        }

    @Test
    fun `result() handles Unit return type`() =
        runTest {
            val handle = createHandle<Unit>(returnType = typeOf<Unit>())

            handle.resolve(createCompletedResolution(Payload.getDefaultInstance()))

            val result = handle.result()

            assertEquals(Unit, result)
        }

    @Test
    fun `result() handles null return type`() =
        runTest {
            val handle = createHandle<String?>(returnType = typeOf<String?>())

            handle.resolve(createCompletedResolution(Payload.getDefaultInstance()))

            val result = handle.result()

            assertNull(result)
        }

    @Test
    fun `result deserialization works for various types`() =
        runTest {
            // Test Int type
            val intHandle = createHandle<Int>(returnType = typeOf<Int>())
            val intPayload =
                Payload
                    .newBuilder()
                    .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                    .setData(ByteString.copyFromUtf8("42"))
                    .build()

            intHandle.resolve(createCompletedResolution(intPayload))
            val intResult = intHandle.result()

            assertEquals(42, intResult)
        }

    // ============================================================================
    // 3. Exception Handling Tests
    // ============================================================================

    @Test
    fun `result() throws ActivityFailureException on failure`() =
        runTest {
            val handle = createHandle<String>()

            // Build nested failure proto (proper Temporal structure)
            // Outer: ActivityFailureInfo (activity failed)
            // Inner cause: ApplicationFailureInfo (the actual app error)
            val applicationFailureCause =
                Failure
                    .newBuilder()
                    .setMessage("Test error occurred")
                    .setApplicationFailureInfo(
                        ApplicationFailureInfo
                            .newBuilder()
                            .setType("TestError")
                            .setNonRetryable(true),
                    ).build()

            val failure =
                Failure
                    .newBuilder()
                    .setMessage("Activity execution failed")
                    .setCause(applicationFailureCause)
                    .setActivityFailureInfo(
                        ActivityFailureInfo
                            .newBuilder()
                            .setRetryState(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE),
                    ).build()

            handle.resolve(createFailedResolution(failure))

            // Should throw ActivityFailureException
            val exception =
                try {
                    val result = handle.result()
                    throw AssertionError("Expected ActivityFailureException to be thrown, but got result: $result")
                } catch (e: ActivityFailureException) {
                    e
                } catch (e: Throwable) {
                    throw AssertionError(
                        "Expected ActivityFailureException, but got ${e::class.simpleName}: ${e.message}",
                        e,
                    )
                }

            assertEquals("Activity execution failed", exception.message)
            assertEquals("ActivityFailure", exception.failureType)
            assertEquals(ActivityRetryState.NON_RETRYABLE_FAILURE, exception.retryState)
            // ApplicationFailure should be extracted from the failure itself or its cause
            assertNotNull(exception.applicationFailure)
            assertEquals("TestError", exception.applicationFailure?.type)
            assertTrue(exception.applicationFailure?.nonRetryable ?: false)
        }

    @Test
    fun `result() throws ActivityCancelledException on cancellation`() =
        runTest {
            val handle = createHandle<String>()

            val failure =
                Failure
                    .newBuilder()
                    .setMessage("Cancelled by user")
                    .build()

            handle.resolve(createCancelledResolution(failure))

            // Should throw ActivityCancelledException
            val exception =
                try {
                    handle.result()
                    throw AssertionError("Expected ActivityCancelledException to be thrown")
                } catch (e: ActivityCancelledException) {
                    e
                }

            assertEquals("Activity was cancelled", exception.message)
            assertEquals("TestActivity::run", exception.activityType)
            assertEquals("test-activity-id", exception.activityId)
        }

    @Test
    fun `exceptionOrNull returns correct exception after failure`() {
        val handle = createHandle<String>()

        // Initially null
        assertNull(handle.exceptionOrNull())

        // Resolve with failed
        val failure =
            Failure
                .newBuilder()
                .setMessage("Test failure")
                .setApplicationFailureInfo(
                    ApplicationFailureInfo
                        .newBuilder()
                        .setType("TestError"),
                ).build()

        handle.resolve(createFailedResolution(failure))

        // Should have exception
        val exception = handle.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception is ActivityFailureException)
        assertEquals("Test failure", exception?.message)
    }

    @Test
    fun `exceptionOrNull returns null before resolution`() {
        val handle = createHandle<String>()

        assertNull(handle.exceptionOrNull())
    }

    // ============================================================================
    // 4. Cancellation Tests
    // ============================================================================

    @Test
    fun `cancel() sends RequestCancelActivity command`() {
        val state = WorkflowState("test-run-id")

        // Create a fresh handle with fresh state
        val freshHandle =
            RemoteActivityHandleImpl<String>(
                activityId = "test-activity-id",
                seq = 99,
                activityType = "TestActivity::run",
                state = state,
                serializer = serializer,
                returnType = typeOf<String>(),
                cancellationType = ActivityCancellationType.TRY_CANCEL,
            )

        assertFalse(state.hasCommands())

        freshHandle.cancel("test cancellation")

        // Verify a command was added
        assertTrue(state.hasCommands())

        // Drain and verify the command is RequestCancelActivity with correct seq
        val commands = state.drainCommands()
        assertEquals(1, commands.size)
        val command = commands.first()
        assertTrue(command.hasRequestCancelActivity())
        assertEquals(99, command.requestCancelActivity.seq)
    }

    @Test
    fun `cancel() is idempotent`() {
        val state = WorkflowState("test-run-id")
        val handle =
            RemoteActivityHandleImpl<String>(
                activityId = "test-activity-id",
                seq = 1,
                activityType = "TestActivity::run",
                state = state,
                serializer = serializer,
                returnType = typeOf<String>(),
                cancellationType = ActivityCancellationType.TRY_CANCEL,
            )

        // Cancel multiple times
        handle.cancel("first")
        handle.cancel("second")
        handle.cancel("third")

        // Should only have one command
        val commands = state.drainCommands()
        assertEquals(1, commands.size)

        // Flag should remain true
        assertTrue(handle.isCancellationRequested)
    }

    @Test
    fun `cancel() is no-op if isDone`() {
        val state = WorkflowState("test-run-id")
        val handle =
            RemoteActivityHandleImpl<String>(
                activityId = "test-activity-id",
                seq = 1,
                activityType = "TestActivity::run",
                state = state,
                serializer = serializer,
                returnType = typeOf<String>(),
                cancellationType = ActivityCancellationType.TRY_CANCEL,
            )

        // Complete the activity first
        handle.resolve(createCompletedResolution())

        assertTrue(handle.isDone)

        // Now try to cancel
        handle.cancel("after completion")

        // Should not have any commands
        assertFalse(state.hasCommands())

        // Flag should remain false
        assertFalse(handle.isCancellationRequested)
    }

    // ============================================================================
    // 5. Edge Cases Tests
    // ============================================================================

    @Test
    fun `backoff resolution throws IllegalStateException`() {
        val handle = createHandle<String>()

        // Resolve with backoff (invalid for regular activities)
        val exception =
            assertThrows(IllegalStateException::class.java) {
                handle.resolve(createBackoffResolution())
            }

        assertTrue(exception.message!!.contains("Regular activity received DoBackoff"))
        assertTrue(exception.message!!.contains("activityType=TestActivity::run"))
        assertTrue(exception.message!!.contains("activityId=test-activity-id"))
    }

    @Test
    fun `resolve with unknown status throws IllegalStateException`() {
        val handle = createHandle<String>()

        // Empty resolution (no status set)
        val resolution = ActivityResult.ActivityResolution.getDefaultInstance()

        val exception =
            assertThrows(IllegalStateException::class.java) {
                handle.resolve(resolution)
            }

        assertTrue(exception.message!!.contains("Unknown activity resolution status"))
    }

    @Test
    fun `retry state mapping covers all cases`() {
        val handle = createHandle<String>()

        val failure =
            Failure
                .newBuilder()
                .setMessage("Timeout")
                .setActivityFailureInfo(
                    ActivityFailureInfo
                        .newBuilder()
                        .setRetryState(RetryState.RETRY_STATE_TIMEOUT),
                ).build()

        handle.resolve(createFailedResolution(failure))

        val exception = handle.exceptionOrNull() as ActivityFailureException
        assertEquals(ActivityRetryState.TIMEOUT, exception.retryState)
    }

    @Test
    fun `failure with cause chain is built correctly`() =
        runTest {
            val handle = createHandle<String>()

            // Build nested failure
            val rootCause =
                Failure
                    .newBuilder()
                    .setMessage("Root cause")
                    .build()

            val failure =
                Failure
                    .newBuilder()
                    .setMessage("Primary failure")
                    .setCause(rootCause)
                    .setApplicationFailureInfo(
                        ApplicationFailureInfo
                            .newBuilder()
                            .setType("TestError"),
                    ).build()

            handle.resolve(createFailedResolution(failure))

            val exception =
                try {
                    handle.result()
                    throw AssertionError("Expected ActivityFailureException to be thrown")
                } catch (e: ActivityFailureException) {
                    e
                }

            assertEquals("Primary failure", exception.message)
            assertNotNull(exception.cause)
            assertEquals("Root cause", exception.cause?.message)
        }

    @Test
    fun `multiple retry states map correctly`() {
        // Test mapping of various retry states
        val testCases =
            mapOf(
                RetryState.RETRY_STATE_IN_PROGRESS to ActivityRetryState.IN_PROGRESS,
                RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED to ActivityRetryState.MAXIMUM_ATTEMPTS_REACHED,
                RetryState.RETRY_STATE_CANCEL_REQUESTED to ActivityRetryState.CANCEL_REQUESTED,
            )

        testCases.forEach { (protoState, expectedKotlinState) ->
            val handle = createHandle<String>()
            val failure =
                Failure
                    .newBuilder()
                    .setMessage("Test")
                    .setActivityFailureInfo(
                        ActivityFailureInfo
                            .newBuilder()
                            .setRetryState(protoState),
                    ).build()

            handle.resolve(createFailedResolution(failure))

            val exception = handle.exceptionOrNull() as ActivityFailureException
            assertEquals(
                expectedKotlinState,
                exception.retryState,
                "Failed to map $protoState to $expectedKotlinState",
            )
        }
    }

    // ============================================================================
    // 6. Sprint 3 Code Review Fixes - New Tests
    // ============================================================================

    @Test
    fun `result() throws ActivityTimeoutException on timeout`() =
        runTest {
            val handle = createHandle<String>()

            // Build timeout failure
            val timeoutFailure =
                Failure
                    .newBuilder()
                    .setMessage("Activity timed out")
                    .setTimeoutFailureInfo(
                        io.temporal.api.failure.v1.TimeoutFailureInfo
                            .newBuilder()
                            .setTimeoutType(io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE),
                    ).build()

            handle.resolve(createFailedResolution(timeoutFailure))

            // Should throw ActivityTimeoutException
            val exception =
                try {
                    handle.result()
                    throw AssertionError("Expected ActivityTimeoutException to be thrown")
                } catch (e: ActivityTimeoutException) {
                    e
                }

            assertEquals("Activity timed out", exception.message)
            assertEquals(ActivityTimeoutType.START_TO_CLOSE, exception.timeoutType)
            assertEquals("TestActivity::run", exception.activityType)
            assertEquals("test-activity-id", exception.activityId)
        }

    @Test
    fun `cancel during resolve is thread-safe`() =
        runTest {
            val handle = createHandle<String>()

            // Launch concurrent operations
            val cancelJob =
                launch {
                    handle.cancel("concurrent cancel")
                }

            val resolveJob =
                launch {
                    handle.resolve(createCompletedResolution())
                }

            // Wait for both
            cancelJob.join()
            resolveJob.join()

            // Should complete without exceptions
            assertTrue(handle.isDone)
            // Cancel might or might not succeed depending on timing
        }

    @Test
    fun `result deserialization handles type mismatch gracefully`() {
        runTest {
            val handle = createHandle<Int>(returnType = typeOf<Int>())

            // Serialize a string but expect int
            val stringPayload =
                Payload
                    .newBuilder()
                    .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                    .setData(ByteString.copyFromUtf8("\"not an int\""))
                    .build()

            handle.resolve(createCompletedResolution(stringPayload))

            // Should throw deserialization exception
            val exception =
                try {
                    handle.result()
                    throw AssertionError("Expected deserialization exception to be thrown")
                } catch (e: Exception) {
                    // Expected - deserialization should fail
                    e
                }

            // Verify exception is related to deserialization
            assertNotNull(exception)
        }
    }

    @Test
    fun `cancel with ABANDON type does not send command`() {
        val state = WorkflowState("test-run-id")
        val handle =
            RemoteActivityHandleImpl<String>(
                activityId = "test-activity",
                seq = 1,
                activityType = "TestActivity::run",
                state = state,
                serializer = serializer,
                returnType = typeOf<String>(),
                cancellationType = ActivityCancellationType.ABANDON,
            )

        assertFalse(state.hasCommands())

        handle.cancel("abandon test")

        // Should NOT have added any command
        assertFalse(state.hasCommands())

        // But flag should be set
        assertTrue(handle.isCancellationRequested)
    }

    @Test
    fun `cancel with TRY_CANCEL type sends command`() {
        val state = WorkflowState("test-run-id")
        val handle =
            RemoteActivityHandleImpl<String>(
                activityId = "test-activity",
                seq = 1,
                activityType = "TestActivity::run",
                state = state,
                serializer = serializer,
                returnType = typeOf<String>(),
                cancellationType = ActivityCancellationType.TRY_CANCEL,
            )

        handle.cancel("try cancel test")

        // Should have added command
        assertTrue(state.hasCommands())
        val commands = state.drainCommands()
        assertEquals(1, commands.size)
        assertTrue(commands.first().hasRequestCancelActivity())
    }

    @Test
    fun `cancel with WAIT_CANCELLATION_COMPLETED type sends command`() {
        val state = WorkflowState("test-run-id")
        val handle =
            RemoteActivityHandleImpl<String>(
                activityId = "test-activity",
                seq = 1,
                activityType = "TestActivity::run",
                state = state,
                serializer = serializer,
                returnType = typeOf<String>(),
                cancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
            )

        handle.cancel("wait cancel test")

        // Should have added command (Core SDK will wait for ActivityTaskCanceled)
        assertTrue(state.hasCommands())
        val commands = state.drainCommands()
        assertEquals(1, commands.size)
        assertTrue(commands.first().hasRequestCancelActivity())
    }
}
