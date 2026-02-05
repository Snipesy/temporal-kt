package com.surrealdev.temporal.testing

import com.surrealdev.temporal.activity.ActivityCancelledException
import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.ActivityInfo
import com.surrealdev.temporal.activity.ActivityWorkflowInfo
import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.PluginPipeline
import com.surrealdev.temporal.serialization.KotlinxJsonSerializer
import com.surrealdev.temporal.serialization.PayloadSerializer
import com.surrealdev.temporal.serialization.deserialize
import com.surrealdev.temporal.util.AttributeScope
import com.surrealdev.temporal.util.Attributes
import com.surrealdev.temporal.util.ExecutionScope
import com.surrealdev.temporal.util.SimpleAttributeScope
import io.temporal.api.common.v1.Payload
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Simplified test harness for testing activity implementations directly.
 *
 * This harness creates a mock [ActivityContext] and calls activity methods directly,
 * bypassing serialization and the Core SDK. This makes tests:
 * - **Faster** - No protobuf serialization or dispatcher overhead
 * - **Simpler** - Direct method calls with real Kotlin objects
 * - **Type-safe** - IDE autocomplete and type checking work perfectly
 * - **Focused** - Test activity logic, not SDK plumbing
 *
 * Example:
 * ```kotlin
 * @Test
 * fun `greeting activity returns greeting`() = runActivityTest {
 *     val activity = GreetingActivity()
 *
 *     val result = withActivityContext {
 *         activity.greet("World")
 *     }
 *
 *     assertEquals("Hello, World!", result)
 * }
 * ```
 *
 * For activities that use [ActivityContext] as a receiver:
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
 *     val activity = HeartbeatingActivity()
 *
 *     val result = withActivityContext {
 *         activity.longRunning(5)
 *     }
 *
 *     assertEquals(5, result)
 *     assertHeartbeatCount(5)
 * }
 * ```
 *
 * For cancellation testing:
 * ```kotlin
 * @Test
 * fun `activity handles cancellation`() = runActivityTest {
 *     val activity = CancellableActivity()
 *
 *     requestCancellation()
 *
 *     assertThrows<ActivityCancelledException> {
 *         withActivityContext {
 *             activity.longTask(1000)
 *         }
 *     }
 * }
 * ```
 */
@TemporalDsl
class ActivityTestHarness(
    /**
     * The serializer used for heartbeat detail serialization.
     * Defaults to [KotlinxJsonSerializer].
     */
    val serializer: PayloadSerializer = KotlinxJsonSerializer(),
) : PluginPipeline {
    private val heartbeatsList = mutableListOf<Any?>()
    private var cancellationRequested = false
    private var currentActivityInfo: ActivityInfo = createDefaultActivityInfo()
    private var currentParentScope: AttributeScope? = null

    /**
     * The harness's own attributes for storing plugin data.
     * Plugins (like DI) can store configuration here via [install].
     */
    override val attributes: Attributes = Attributes(concurrent = false)

    /**
     * The parent scope for hierarchical plugin lookup.
     * Set this to a [com.surrealdev.temporal.application.TemporalApplication]
     * or [com.surrealdev.temporal.application.TaskQueueBuilder] to enable
     * hierarchical dependency resolution and plugin inheritance.
     *
     * Example with DI plugin:
     * ```kotlin
     * val app = TemporalApplication { ... }
     * app.dependencies {
     *     workflowSafe<ConfigService> { ProductionConfig() }
     * }
     *
     * runActivityTest {
     *     parentScope = app  // Inherit app dependencies
     *
     *     // Override or add test-specific dependencies
     *     dependencies {
     *         activityOnly<HttpClient> { MockHttpClient() }
     *     }
     *
     *     withActivityContext {
     *         val httpClient: HttpClient by activityDependencies  // From harness
     *         val config: ConfigService by workflowDependencies   // From app
     *     }
     * }
     * ```
     */
    override var parentScope: AttributeScope?
        get() = currentParentScope
        set(value) {
            currentParentScope = value
        }

    /**
     * All heartbeats recorded during activity execution (as Payload objects or nulls).
     *
     * Use [deserializeHeartbeats] to get typed values.
     */
    val heartbeats: List<Any?>
        get() = heartbeatsList.toList()

    /**
     * Gets heartbeats deserialized to the specified type.
     *
     * Example:
     * ```kotlin
     * val progress = deserializeHeartbeats<Int>()
     * assertEquals(listOf(1, 2, 3), progress)
     * ```
     */
    inline fun <reified T> deserializeHeartbeats(): List<T?> =
        heartbeats.map { payload ->
            when (payload) {
                null -> null
                is Payload -> serializer.deserialize<T>(payload)
                else -> payload as? T
            }
        }

    /**
     * Whether cancellation has been requested.
     */
    val isCancellationRequested: Boolean
        get() = cancellationRequested

    /**
     * The activity info that will be provided to activities.
     * Can be customized before calling [withActivityContext].
     */
    var activityInfo: ActivityInfo
        get() = currentActivityInfo
        set(value) {
            currentActivityInfo = value
        }

    /**
     * Creates a test [ActivityContext] and runs the block with it as the receiver.
     *
     * The activity method is called directly - no serialization or dispatcher involved.
     * Heartbeats and cancellation are tracked by the harness for assertions.
     *
     * Example:
     * ```kotlin
     * val result = withActivityContext {
     *     myActivity.doWork("input")
     * }
     * ```
     *
     * @param R The return type of the activity
     * @param block The code block to run with the activity context
     * @return The result from the activity
     * @throws ActivityCancelledException if the activity calls heartbeat() after cancellation
     */
    suspend fun <R> withActivityContext(block: suspend ActivityContext.() -> R): R {
        // Create a scope chain: TestActivityContext -> Harness -> parentScope
        // This allows per-context caching while inheriting dependencies from harness/app
        val harnessScope = SimpleAttributeScope(attributes, currentParentScope)
        val context =
            TestActivityContext(
                info = currentActivityInfo,
                serializer = serializer,
                onHeartbeat = { details -> heartbeatsList.add(details) },
                isCancelled = { cancellationRequested },
                parentScope = harnessScope,
            )
        return context.block()
    }

    /**
     * Requests cancellation of the current or next activity execution.
     *
     * The activity will be notified when it calls [ActivityContext.heartbeat]
     * or [ActivityContext.ensureNotCancelled].
     */
    fun requestCancellation() {
        cancellationRequested = true
    }

    /**
     * Resets cancellation state for the next test.
     */
    fun resetCancellation() {
        cancellationRequested = false
    }

    /**
     * Clears all recorded heartbeats.
     */
    fun clearHeartbeats() {
        heartbeatsList.clear()
    }

    /**
     * Resets all harness state (heartbeats and cancellation).
     */
    fun reset() {
        clearHeartbeats()
        resetCancellation()
    }

    // =========================================================================
    // Assertion Helpers
    // =========================================================================

    /**
     * Asserts that the exact number of heartbeats was recorded.
     */
    fun assertHeartbeatCount(expected: Int) {
        if (heartbeatsList.size != expected) {
            throw AssertionError("Expected $expected heartbeat(s) but got ${heartbeatsList.size}")
        }
    }

    /**
     * Asserts that no heartbeats were recorded.
     */
    fun assertNoHeartbeats() {
        if (heartbeatsList.isNotEmpty()) {
            throw AssertionError("Expected no heartbeats but got ${heartbeatsList.size}")
        }
    }

    /**
     * Asserts that at least one heartbeat was recorded.
     */
    fun assertHasHeartbeats() {
        if (heartbeatsList.isEmpty()) {
            throw AssertionError("Expected at least one heartbeat but got none")
        }
    }

    /**
     * Asserts a predicate over the list of heartbeats.
     *
     * Example:
     * ```kotlin
     * assertHeartbeats { it.all { h -> h is Int && h > 0 } }
     * ```
     */
    fun assertHeartbeats(predicate: (List<Any?>) -> Boolean) {
        if (!predicate(heartbeatsList)) {
            throw AssertionError("Heartbeat assertion failed. Heartbeats: $heartbeatsList")
        }
    }

    /**
     * Asserts that the last heartbeat matches the given predicate.
     *
     * Example:
     * ```kotlin
     * assertLastHeartbeat { it == "progress: 100%" }
     * ```
     */
    fun assertLastHeartbeat(predicate: (Any?) -> Boolean) {
        val last = heartbeatsList.lastOrNull()
        if (!predicate(last)) {
            throw AssertionError("Last heartbeat assertion failed. Last heartbeat: $last")
        }
    }

    private fun createDefaultActivityInfo(): ActivityInfo =
        ActivityInfo(
            activityId = "test-activity-1",
            activityType = "TestActivity",
            taskQueue = "test-task-queue",
            attempt = 1,
            startTime = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            deadline = null,
            heartbeatDetails = null,
            workflowInfo =
                ActivityWorkflowInfo(
                    workflowId = "test-workflow-id",
                    runId = "test-run-id",
                    workflowType = "TestWorkflow",
                    namespace = "default",
                ),
        )
}

/**
 * Test implementation of [ActivityContext] that also implements [ExecutionScope]
 * to support plugins like dependency injection.
 *
 * This provides a minimal, functional implementation for unit testing activities.
 */
private class TestActivityContext(
    override val info: ActivityInfo,
    override val serializer: PayloadSerializer,
    private val onHeartbeat: (Any?) -> Unit,
    private val isCancelled: () -> Boolean,
    override val parentScope: AttributeScope?,
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : ActivityContext,
    ExecutionScope {
    /**
     * This context's own attributes for per-execution plugin state.
     * Dependencies are resolved through hierarchical lookup via [parentScope].
     */
    override val attributes: Attributes = Attributes(concurrent = false)

    /**
     * Activity execution contexts are not workflow contexts.
     */
    override val isWorkflowContext: Boolean = false

    override val isCancellationRequested: Boolean
        get() = isCancelled()

    override suspend fun heartbeatWithPayload(details: Payload?) {
        if (isCancellationRequested) {
            throw ActivityCancelledException.Cancelled("Activity cancelled by test harness")
        }
        onHeartbeat(details)
    }

    override fun ensureNotCancelled() {
        if (isCancellationRequested) {
            throw ActivityCancelledException.Cancelled("Activity cancelled by test harness")
        }
    }
}

/**
 * Runs an activity test with a fresh [ActivityTestHarness].
 *
 * Example:
 * ```kotlin
 * @Test
 * fun `my activity test`() = runActivityTest {
 *     val activity = MyActivity()
 *     val result = withActivityContext {
 *         activity.doWork("input")
 *     }
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
 * @param serializer The serializer to use for heartbeat details
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
