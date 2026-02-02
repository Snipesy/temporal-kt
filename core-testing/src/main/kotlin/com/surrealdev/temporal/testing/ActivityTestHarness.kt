package com.surrealdev.temporal.testing

import com.google.protobuf.ByteString
import com.surrealdev.temporal.activity.internal.ActivityDispatcher
import com.surrealdev.temporal.activity.internal.ActivityRegistry
import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.ActivityRegistration
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.SimpleAttributeScope
import coresdk.activity_task.ActivityTaskOuterClass
import coresdk.activity_task.activityTask
import coresdk.activity_task.start
import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.WorkflowExecution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KType
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
    /**
     * The dispatcher to use for activity execution.
     *
     * Defaults to [Dispatchers.Unconfined] for immediate, predictable execution in tests.
     * Can be set to a [kotlinx.coroutines.test.StandardTestDispatcher] for controlled
     * time advancement in tests.
     */
    val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    private val registry = ActivityRegistry()
    private val _heartbeats = mutableListOf<HeartbeatRecord>()
    private val taskCounter = AtomicInteger(0)

    @Volatile
    private var _isCancellationRequested = false

    // Create a stub application for testing (DI not supported in activity test harness)
    private val stubApplication =
        TemporalApplication {
            connection {
                target = "http://localhost:7233"
                namespace = "test"
            }
        }

    // Create a scope hierarchy for testing (taskQueue -> application)
    private val taskQueueScope =
        SimpleAttributeScope(
            attributes = Attributes(concurrent = false),
            parentScope = stubApplication,
        )

    private val activityDispatcher =
        ActivityDispatcher(
            registry = registry,
            serializer = serializer,
            codec = com.surrealdev.temporal.serialization.NoOpCodec,
            taskQueue = TEST_TASK_QUEUE,
            maxConcurrent = 10,
            heartbeatFn = { taskToken, details ->
                if (_isCancellationRequested) {
                    throw com.surrealdev.temporal.activity.ActivityCancelledException.Cancelled(
                        "Activity cancelled by test harness",
                    )
                }
                _heartbeats.add(HeartbeatRecord(taskToken, details))
            },
            taskQueueScope = taskQueueScope,
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
     */
    fun register(implementation: Any) {
        registry.register(ActivityRegistration.InstanceRegistration(implementation))
    }

    /**
     * Executes an activity with pre-serialized arguments and explicit return type.
     *
     * This is the base execution method. Use the extension functions in
     * [execute] for convenient typed argument handling.
     *
     * @param R The expected result type
     * @param activityType The full activity type name (e.g., "GreetingActivity::greet")
     * @param returnType The KType for result deserialization
     * @param args Pre-serialized argument payloads
     * @return The deserialized result from the activity
     * @throws ActivityTestException if the activity fails
     * @throws ActivityTestCancelledException if the activity is cancelled
     */
    @Suppress("UNCHECKED_CAST")
    @InternalTemporalApi
    suspend fun <R> executeWithPayloads(
        activityType: String,
        returnType: KType,
        args: List<Payload>,
    ): R {
        // Build activity task
        val task = buildActivityTask(activityType, args)

        // Dispatch using real dispatcher, wrapped with configured test dispatcher
        // The test harness only sends Start tasks
        // Cancellation in tests is handled via the heartbeat mechanism
        val completion =
            withContext(dispatcher) {
                activityDispatcher.dispatchForTest(task)
            }

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
            serializer.deserialize(returnType, resultPayload) as R
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
     * - [heartbeat()][com.surrealdev.temporal.activity.ActivityContext.heartbeatWithPayload] throwing
     *   [ActivityCancelledException][com.surrealdev.temporal.activity.ActivityCancelledException]
     *
     * Note: For cancellation to take effect mid-execution, the activity must
     * call [heartbeat()][com.surrealdev.temporal.activity.ActivityContext.heartbeatWithPayload] periodically.
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
    val taskToken: ByteString,
    val details: Payload?,
)

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

/**
 * Runs an activity test with a custom dispatcher.
 *
 * Use this overload when you need precise control over activity thread execution,
 * such as using a [kotlinx.coroutines.test.StandardTestDispatcher] for
 * controlled time advancement.
 *
 * @param dispatcher The dispatcher to use for activity execution
 * @param serializer The serializer to use (defaults to [KotlinxJsonSerializer])
 * @param block The test block that configures the harness and runs assertions
 */
fun runActivityTest(
    dispatcher: CoroutineDispatcher,
    serializer: PayloadSerializer = KotlinxJsonSerializer(),
    block: suspend ActivityTestHarness.() -> Unit,
): TestResult =
    runTest(timeout = 60.seconds) {
        val harness = ActivityTestHarness(serializer = serializer, dispatcher = dispatcher)
        harness.block()
    }
