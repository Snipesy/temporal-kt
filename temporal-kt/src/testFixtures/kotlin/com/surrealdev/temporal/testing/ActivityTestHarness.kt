package com.surrealdev.temporal.testing

import com.google.protobuf.ByteString
import com.surrealdev.temporal.activity.internal.ActivityDispatcher
import com.surrealdev.temporal.activity.internal.ActivityRegistry
import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.ActivityRegistration
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.typeInfoOf
import coresdk.activity_task.ActivityTaskOuterClass
import coresdk.activity_task.activityTask
import coresdk.activity_task.start
import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.WorkflowExecution
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.seconds

/**
 * Test harness for testing activity implementations directly.
 *
 * This harness uses REAL SDK components ([ActivityDispatcher], [ActivityRegistry])
 * but bypasses Core SDK polling. This ensures:
 * - Real serialization/deserialization is tested
 * - Real [com.surrealdev.temporal.activity.ActivityContext] implementation is used
 * - Real dispatch logic is exercised
 *
 * Example:
 * ```kotlin
 * @Test
 * fun `greeting activity returns greeting`() = runActivityTest {
 *     val activity = GreetingActivity()
 *     register(activity)
 *
 *     val result = execute<String>("greet", "World")
 *
 *     assertEquals("Hello, World!", result)
 * }
 * ```
 *
 * For activities that use [com.surrealdev.temporal.activity.ActivityContext] as a receiver:
 * ```kotlin
 * class HeartbeatingActivity {
 *     @Activity
 *     suspend fun ActivityContext.longRunning(iterations: Int): Int {
 *         for (i in 1..iterations) {
 *             heartbeat(i)
 *         }
 *         return iterations
 *     }
 * }
 *
 * @Test
 * fun `activity with heartbeats`() = runActivityTest {
 *     register(HeartbeatingActivity())
 *
 *     val result = execute<Int>("longRunning", 5)
 *
 *     assertEquals(5, result)
 *     assertEquals(5, heartbeats.size)
 * }
 * ```
 */
@TemporalDsl
class ActivityTestHarness(
    /**
     * The serializer used for argument and result serialization.
     * Defaults to [KotlinxJsonSerializer].
     */
    val serializer: PayloadSerializer = KotlinxJsonSerializer(),
) {
    private val registry = ActivityRegistry()
    private val _heartbeats = mutableListOf<HeartbeatRecord>()
    private val taskCounter = AtomicInteger(0)

    @Volatile
    private var _isCancellationRequested = false

    private val dispatcher =
        ActivityDispatcher(
            registry = registry,
            serializer = serializer,
            taskQueue = TEST_TASK_QUEUE,
            maxConcurrent = 10,
            heartbeatFn = { taskToken, details ->
                if (_isCancellationRequested) {
                    throw com.surrealdev.temporal.activity.ActivityCancelledException(
                        "Activity cancelled by test harness",
                    )
                }
                _heartbeats.add(HeartbeatRecord(taskToken, details))
            },
        )

    /**
     * All heartbeats recorded during activity execution.
     */
    val heartbeats: List<HeartbeatRecord>
        get() = _heartbeats.toList()

    /**
     * Whether cancellation has been requested.
     */
    val isCancellationRequested: Boolean
        get() = _isCancellationRequested

    /**
     * Registers an activity implementation.
     *
     * The implementation class should contain methods annotated with [@Activity][com.surrealdev.temporal.annotation.Activity].
     *
     * @param implementation The activity implementation instance
     * @param activityType Optional activity type override (not typically needed).
     */
    fun register(
        implementation: Any,
        activityType: String = "",
    ) {
        registry.register(
            ActivityRegistration(
                activityType = activityType,
                implementation = implementation,
            ),
        )
    }

    /**
     * Executes an activity by type and returns the result.
     *
     * Uses real serialization and the real [ActivityDispatcher].
     *
     * @param R The expected result type
     * @param activityType The full activity type name (e.g., "GreetingActivity::greet")
     * @param args Arguments to pass to the activity
     * @return The deserialized result from the activity
     * @throws ActivityTestException if the activity fails
     * @throws ActivityTestCancelledException if the activity is cancelled
     */
    suspend inline fun <reified R> execute(
        activityType: String,
        vararg args: Any?,
    ): R = execute(activityType, typeOf<R>(), args.toList())

    /**
     * Executes an activity with explicit return type.
     *
     * @param R The expected result type
     * @param activityType The full activity type name
     * @param returnType The KType for result deserialization
     * @param args Arguments to pass to the activity
     * @return The deserialized result
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <R> execute(
        activityType: String,
        returnType: KType,
        args: List<Any?>,
    ): R {
        // Serialize arguments (use serializer for all values including null)
        val argPayloads =
            args.map { arg ->
                // The serializer handles null properly with binary/null encoding
                serializer.serialize(typeInfoOf(arg), arg)
            }

        // Build activity task
        val task = buildActivityTask(activityType, argPayloads)

        // Dispatch using real dispatcher
        // The test harness only sends Start tasks
        // Cancellation in tests is handled via the heartbeat mechanism
        val completion =
            dispatcher.dispatch(task)
                ?: error("Unexpected null completion - test harness only sends Start tasks")

        // Extract result
        val executionResult = completion.result
        if (executionResult.hasFailed()) {
            throw ActivityTestException(
                message = executionResult.failed.failure.message,
                stackTrace = executionResult.failed.failure.stackTrace,
                activityType = activityType,
            )
        }
        if (executionResult.hasCancelled()) {
            throw ActivityTestCancelledException(activityType = activityType)
        }

        val resultPayload = executionResult.completed.result
        return if (resultPayload == Payload.getDefaultInstance() || resultPayload.data.isEmpty) {
            if (returnType.classifier == Unit::class) {
                Unit as R
            } else {
                null as R
            }
        } else {
            serializer.deserialize(typeInfoOf(returnType), resultPayload) as R
        }
    }

    /**
     * Clears all recorded heartbeats.
     */
    fun clearHeartbeats() {
        _heartbeats.clear()
    }

    /**
     * Requests cancellation of the current or next activity execution.
     *
     * The activity will be notified via:
     * - [heartbeat()][com.surrealdev.temporal.activity.ActivityContext.heartbeat] throwing
     *   [ActivityCancelledException][com.surrealdev.temporal.activity.ActivityCancelledException]
     *
     * Note: For cancellation to take effect mid-execution, the activity must
     * call [heartbeat()][com.surrealdev.temporal.activity.ActivityContext.heartbeat] periodically.
     */
    fun requestCancellation() {
        _isCancellationRequested = true
    }

    /**
     * Resets cancellation state for the next test.
     */
    fun resetCancellation() {
        _isCancellationRequested = false
    }

    /**
     * Resets all harness state (heartbeats and cancellation).
     */
    fun reset() {
        clearHeartbeats()
        resetCancellation()
    }

    private fun buildActivityTask(
        activityType: String,
        arguments: List<Payload>,
    ): ActivityTaskOuterClass.ActivityTask {
        val taskNum = taskCounter.incrementAndGet()
        val taskToken = ByteString.copyFromUtf8("$TEST_TOKEN_PREFIX$taskNum")

        return activityTask {
            this.taskToken = taskToken
            this.start =
                start {
                    this.workflowNamespace = TEST_NAMESPACE
                    this.workflowType = TEST_WORKFLOW_TYPE
                    this.workflowExecution =
                        WorkflowExecution
                            .newBuilder()
                            .setWorkflowId(TEST_WORKFLOW_ID)
                            .setRunId(TEST_RUN_ID)
                            .build()
                    this.activityId = "$TEST_ACTIVITY_ID_PREFIX$taskNum"
                    this.activityType = activityType
                    this.input.addAll(arguments)
                    this.attempt = 1
                }
        }
    }

    companion object {
        private const val TEST_TASK_QUEUE = "test-task-queue"
        private const val TEST_NAMESPACE = "default"
        private const val TEST_WORKFLOW_TYPE = "TestWorkflow"
        private const val TEST_WORKFLOW_ID = "test-workflow-id"
        private const val TEST_RUN_ID = "test-run-id"
        private const val TEST_TOKEN_PREFIX = "test-token-"
        private const val TEST_ACTIVITY_ID_PREFIX = "test-activity-"
    }
}

/**
 * Record of a heartbeat call made during activity execution.
 *
 * @property taskToken The task token identifying the activity task
 * @property details The serialized heartbeat details, or null if no details were provided
 */
data class HeartbeatRecord(
    val taskToken: ByteArray,
    val details: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HeartbeatRecord

        if (!taskToken.contentEquals(other.taskToken)) return false
        if (details != null) {
            if (other.details == null) return false
            if (!details.contentEquals(other.details)) return false
        } else if (other.details != null) {
            return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = taskToken.contentHashCode()
        result = 31 * result + (details?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Exception thrown when an activity fails during testing.
 *
 * @property message The failure message
 * @property stackTrace The stack trace from the activity failure, if available
 * @property activityType The activity type that failed
 */
class ActivityTestException(
    message: String?,
    val stackTrace: String?,
    val activityType: String,
) : RuntimeException(message)

/**
 * Exception thrown when an activity is cancelled during testing.
 *
 * @property activityType The activity type that was cancelled
 */
class ActivityTestCancelledException(
    val activityType: String,
) : RuntimeException("Activity '$activityType' was cancelled")

/**
 * Runs an activity test with a fresh [ActivityTestHarness].
 *
 * Example:
 * ```kotlin
 * @Test
 * fun `my activity test`() = runActivityTest {
 *     register(MyActivity())
 *     val result = execute<String>("MyActivity::doWork", "input")
 *     assertEquals("expected", result)
 * }
 * ```
 *
 * @param block The test block that configures the harness and runs assertions
 */
fun runActivityTest(block: suspend ActivityTestHarness.() -> Unit): TestResult =
    runTest(timeout = 60.seconds) {
        val harness = ActivityTestHarness()
        harness.block()
    }

/**
 * Runs an activity test with a custom serializer.
 *
 * @param serializer The serializer to use for argument and result serialization
 * @param block The test block that configures the harness and runs assertions
 */
fun runActivityTest(
    serializer: PayloadSerializer,
    block: suspend ActivityTestHarness.() -> Unit,
): TestResult =
    runTest(timeout = 60.seconds) {
        val harness = ActivityTestHarness(serializer = serializer)
        harness.block()
    }
