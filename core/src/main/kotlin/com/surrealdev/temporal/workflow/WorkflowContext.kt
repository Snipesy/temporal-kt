package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.annotation.InternalTemporalApi
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.common.TypedSearchAttributes
import com.surrealdev.temporal.serialization.PayloadSerializer
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Context available within a workflow execution.
 *
 * This context provides access to workflow information and operations like
 * scheduling activities, timers, and child workflows.
 *
 * As a [CoroutineContext.Element], it can be accessed from any coroutine
 * running within the workflow's scope using `coroutineContext[WorkflowContext]`.
 *
 * Usage:
 * ```kotlin
 * @Workflow("MyWorkflow")
 * class MyWorkflow {
 *     @WorkflowRun
 *     suspend fun WorkflowContext.execute(arg: MyArg): String {
 *         val result = activity<MyActivity>().doSomething(arg.value)
 *         return "Result: $result"
 *     }
 * }
 * ```
 *
 * Or accessing from nested coroutines:
 * ```kotlin
 * suspend fun nestedFunction() {
 *     val ctx = coroutineContext[WorkflowContext]!!
 *     ctx.sleep(5.seconds)
 * }
 * ```
 *
 * As a [CoroutineScope], workflow code can use structured concurrency with
 * deterministic execution. The scope uses a custom dispatcher that ensures
 * all coroutines run synchronously on the workflow task thread.
 */
interface WorkflowContext :
    CoroutineScope,
    CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<WorkflowContext>

    override val key: CoroutineContext.Key<*> get() = Key

    /**
     * The serializer for converting values to/from Temporal Payloads.
     *
     * Use this in runtime query handlers to serialize return values and
     * deserialize arguments.
     */
    val serializer: PayloadSerializer

    /**
     * Information about the currently executing workflow.
     */
    val info: WorkflowInfo

    /**
     * Starts an activity and returns a handle to track its execution.
     *
     * This is the low-level method. For easier usage with type inference,
     * use the extension functions [startActivity].
     *
     * @param R The expected result type of the activity
     * @param activityType The activity type name (e.g., "greet")
     * @param args Serialized arguments to pass to the activity
     * @param options Configuration for the activity
     * @param returnType The KType for result deserialization
     * @return A handle to the activity for awaiting results or cancellation
     * @throws IllegalArgumentException if neither startToCloseTimeout nor scheduleToCloseTimeout is set
     */
    @InternalTemporalApi
    suspend fun startActivityWithPayloads(
        activityType: String,
        args: TemporalPayloads,
        options: ActivityOptions = ActivityOptions(),
    ): RemoteActivityHandle

    /**
     * Starts a local activity and returns a handle to track its execution.
     *
     * Local activities run in the same worker process as the workflow, avoiding
     * the roundtrip to the Temporal server. They're useful for short operations
     * that don't need server-side scheduling or persistence.
     *
     * This is the low-level method. For easier usage with type inference,
     * use the extension functions [startLocalActivity].
     *
     * **Key differences from regular activities:**
     * - No heartbeats (operations should be short)
     * - Retries managed locally up to `localRetryThreshold` (default 1 minute)
     * - Uses markers for replay (not re-execution)
     *
     * @param activityType The activity type name (e.g., "greet")
     * @param args Serialized arguments to pass to the activity
     * @param options Configuration for the local activity
     * @return A handle to the local activity for awaiting results or cancellation
     * @throws IllegalArgumentException if neither startToCloseTimeout nor scheduleToCloseTimeout is set
     */
    @InternalTemporalApi
    suspend fun startLocalActivityWithPayloads(
        activityType: String,
        args: TemporalPayloads,
        options: LocalActivityOptions = LocalActivityOptions(startToCloseTimeout = Duration.parse("10s")),
    ): LocalActivityHandle

    /**
     * Suspends the workflow for the specified duration.
     *
     * This creates a durable timer that survives workflow replay.
     *
     * @param duration How long to sleep
     */
    suspend fun sleep(duration: Duration)

    /**
     * Suspends the workflow until the specified condition is met.
     *
     * The condition is checked immediately upon calling this method. If it returns true,
     * this method returns immediately.
     *
     * This overload waits indefinitely and supports trailing lambda syntax:
     * ```kotlin
     * awaitCondition { counter >= target }
     * ```
     *
     * @param condition A function that returns true when the workflow should continue
     */
    suspend fun awaitCondition(condition: () -> Boolean)

    /**
     * Suspends the workflow until the specified condition is met or timeout occurs.
     *
     * The condition is checked immediately upon calling this method. If it returns true,
     * this method returns immediately without creating any timers.
     *
     * If a timeout is specified and the condition is not met within the timeout duration,
     * a [com.surrealdev.temporal.common.exceptions.WorkflowConditionTimeoutException] is thrown.
     *
     * @param timeout Maximum duration to wait before timing out
     * @param timeoutSummary Optional description for debugging (included in exception message)
     * @param condition A function that returns true when the workflow should continue
     * @throws com.surrealdev.temporal.common.exceptions.WorkflowConditionTimeoutException if timeout expires before condition is met
     */
    suspend fun awaitCondition(
        timeout: Duration,
        timeoutSummary: String? = null,
        condition: () -> Boolean,
    )

    /**
     * Gets the current workflow time.
     *
     * This is deterministic and safe to use in workflows.
     */
    fun now(): Instant

    /**
     * Generates a deterministic UUID.
     *
     * Safe to use in workflows as it produces the same value on replay.
     */
    fun randomUuid(): String

    /**
     * Checks if a patch (workflow version) has been applied.
     *
     * Use this for safe code evolution while maintaining replay compatibility:
     * ```kotlin
     * if (patched("v2-improved-retry")) {
     *     // New code path
     * } else {
     *     // Legacy code path (for replaying old workflows)
     * }
     * ```
     *
     * **Behavior:**
     * - **First execution:** Returns `true`, records patch marker in history
     * - **Replay with marker:** Returns `true` (deterministic)
     * - **Replay without marker:** Returns `false` (legacy path)
     *
     * **Best Practices:**
     * - Use descriptive patch IDs (e.g., "v2-improved-retry", "fix-123-null-check")
     * - Keep old code paths until all running workflows have completed
     * - Remove old code and patch checks once all workflows use the new path
     *
     * @param patchId Unique identifier for this version change
     * @return `true` if workflow should use new behavior, `false` for legacy path
     */
    fun patched(patchId: String): Boolean

    /**
     * Returns the current history length (number of events).
     *
     * This value is updated with each activation and can be used to make
     * manual decisions about when to use [continueAsNew].
     *
     * @see isContinueAsNewSuggested
     * @see historySizeBytes
     */
    val historyLength: Int

    /**
     * Returns the current history size in bytes.
     *
     * This value is updated with each activation. The server considers both
     * history length and size when deciding whether to suggest continue-as-new.
     *
     * **Temporal limits:**
     * - Warning threshold: 10 MB
     * - Hard limit: 50 MB
     *
     * @see isContinueAsNewSuggested
     * @see historyLength
     */
    val historySizeBytes: Long

    /**
     * Returns `true` if the Temporal server suggests this workflow should continue-as-new.
     *
     * **This is the recommended way to check if continue-as-new should be performed.**
     * The server sets this based on both history length and size limits:
     * - Event count warning at 10,240 events (hard limit: 51,200)
     * - Size warning at 10 MB (hard limit: 50 MB)
     *
     * **Usage:**
     * ```kotlin
     * @WorkflowRun
     * suspend fun WorkflowContext.run(state: MyState): String {
     *     while (true) {
     *         // ... process work ...
     *
     *         if (isContinueAsNewSuggested()) {
     *             continueAsNew(state)
     *         }
     *     }
     * }
     * ```
     *
     * For manual control with custom thresholds, use [historyLength] and
     * [historySizeBytes] directly.
     *
     * @return `true` if the server recommends continue-as-new
     */
    fun isContinueAsNewSuggested(): Boolean

    /**
     * Starts a child workflow and returns a handle to interact with it.
     *
     * This is the low-level method. For easier usage with type inference,
     * use the extension functions [startChildWorkflow].
     *
     * @param workflowType The workflow type name
     * @param args Serialized arguments to pass to the child workflow
     * @param options Configuration for the child workflow
     * @return A handle to the child workflow for awaiting results or cancellation
     */
    @InternalTemporalApi
    suspend fun startChildWorkflowWithPayloads(
        workflowType: String,
        args: TemporalPayloads,
        options: ChildWorkflowOptions = ChildWorkflowOptions(),
    ): ChildWorkflowHandle

    /**
     * Registers or replaces a query handler at runtime.
     *
     * This allows workflows to dynamically register query handlers that weren't
     * defined via @Query annotations. Pass null to unregister an existing handler.
     *
     * The handler receives raw Payload arguments and must return a Payload result.
     * Use the serializer to convert to/from your domain types.
     *
     * Example:
     * ```kotlin
     * @WorkflowRun
     * suspend fun WorkflowContext.run() {
     *     setQueryHandler("customQuery") { payloads ->
     *         // Deserialize args if needed: serializer.deserialize(typeInfo, payloads[0])
     *         // Return serialized result
     *         serializer.serialize(typeInfoOf<String>(), "Custom response")
     *     }
     * }
     * ```
     *
     * @param name The query name to register
     * @param handler The handler function receiving raw payloads and returning a payload, or null to unregister
     */
    @InternalTemporalApi
    fun setQueryHandlerWithPayloads(
        name: String,
        handler: (suspend (TemporalPayloads) -> TemporalPayload)?,
    )

    /**
     * Registers or replaces a dynamic query handler at runtime.
     *
     * A dynamic handler receives all queries that don't have a specific handler.
     * The handler receives the query type name and raw Payload arguments.
     *
     * Example:
     * ```kotlin
     * @WorkflowRun
     * suspend fun WorkflowContext.run() {
     *     setDynamicQueryHandler { queryType, payloads ->
     *         serializer.serialize(typeInfoOf<String>(), "Dynamic response for: $queryType")
     *     }
     * }
     * ```
     *
     * @param handler The handler function, or null to unregister
     */
    fun setDynamicQueryHandlerWithPayloads(
        handler: (
            suspend (
                queryType: String,
                args: TemporalPayloads,
            ) -> TemporalPayload
        )?,
    )

    /**
     * Registers or replaces a signal handler at runtime.
     *
     * This allows workflows to dynamically register signal handlers that weren't
     * defined via @Signal annotations. Pass null to unregister an existing handler.
     *
     * The handler receives raw Payload arguments. Signal handlers return Unit.
     * Unhandled signals are buffered and replayed when a handler is registered.
     *
     * Example:
     * ```kotlin
     * @WorkflowRun
     * suspend fun WorkflowContext.run() {
     *     setSignalHandler("approveOrder") { payloads ->
     *         val approval = serializer.deserialize(typeInfoOf<Approval>(), payloads[0])
     *         // Process the signal
     *     }
     * }
     * ```
     *
     * @param name The signal name to register
     * @param handler The handler function receiving raw payloads, or null to unregister
     */
    @InternalTemporalApi
    fun setSignalHandlerWithPayloads(
        name: String,
        handler: (suspend (TemporalPayloads) -> Unit)?,
    )

    /**
     * Registers or replaces a dynamic signal handler at runtime.
     *
     * A dynamic handler receives all signals that don't have a specific handler.
     * The handler receives the signal name and raw Payload arguments.
     *
     * Example:
     * ```kotlin
     * @WorkflowRun
     * suspend fun WorkflowContext.run() {
     *     setDynamicSignalHandler { signalName, payloads ->
     *         // Handle any signal dynamically
     *     }
     * }
     * ```
     *
     * @param handler The handler function, or null to unregister
     */
    fun setDynamicSignalHandlerWithPayloads(
        handler: (
            suspend (
                signalName: String,
                args: TemporalPayloads,
            ) -> Unit
        )?,
    )

    /**
     * Registers or replaces an update handler at runtime.
     *
     * This allows workflows to dynamically register update handlers that weren't
     * defined via @Update annotations. Pass null to unregister an existing handler.
     *
     * The handler receives raw Payload arguments and must return a Payload result.
     * Unlike signals, updates fail immediately if no handler exists.
     *
     * Example:
     * ```kotlin
     * @WorkflowRun
     * suspend fun WorkflowContext.run() {
     *     setUpdateHandler("addItem") { payloads ->
     *         val item = serializer.deserialize(typeInfoOf<Item>(), payloads[0])
     *         items.add(item)
     *         serializer.serialize(typeInfoOf<Int>(), items.size)
     *     }
     * }
     * ```
     *
     * @param name The update name to register
     * @param handler The handler function receiving raw payloads and returning a payload, or null to unregister
     * @param validator Optional synchronous validator that runs before the handler (in read-only mode)
     */
    @InternalTemporalApi
    fun setUpdateHandlerWithPayloads(
        name: String,
        handler: (suspend (TemporalPayloads) -> TemporalPayload)?,
        validator: ((TemporalPayloads) -> Unit)? = null,
    )

    /**
     * Registers or replaces a dynamic update handler at runtime.
     *
     * A dynamic handler receives all updates that don't have a specific handler.
     * The handler receives the update name and raw Payload arguments.
     *
     * Example:
     * ```kotlin
     * @WorkflowRun
     * suspend fun WorkflowContext.run() {
     *     setDynamicUpdateHandler(
     *         handler = { updateName, payloads ->
     *             serializer.serialize(typeInfoOf<String>(), "Handled: $updateName")
     *         },
     *         validator = { updateName, payloads ->
     *             require(payloads.isNotEmpty()) { "Update must have arguments" }
     *         }
     *     )
     * }
     * ```
     *
     * @param handler The handler function, or null to unregister
     * @param validator Optional synchronous validator that runs before the handler (in read-only mode)
     */
    fun setDynamicUpdateHandlerWithPayloads(
        handler: (
            suspend (
                updateName: String,
                args: TemporalPayloads,
            ) -> TemporalPayload
        )?,
        validator: ((updateName: String, args: TemporalPayloads) -> Unit)? = null,
    )

    /**
     * Updates search attributes for this workflow execution.
     *
     * This merges the provided attributes with existing ones.
     * To remove an attribute, set its value to null.
     *
     * Example:
     * ```kotlin
     * upsertSearchAttributes(searchAttributes {
     *     CUSTOMER_STATUS to "premium"
     *     ORDER_COUNT to currentOrderCount
     *     OLD_ATTRIBUTE to null  // Removes this attribute
     * })
     * ```
     *
     * @param attributes Type-safe search attributes to upsert
     */
    suspend fun upsertSearchAttributes(attributes: TypedSearchAttributes)

    /**
     * Gets a handle to an external workflow for signaling or cancelling.
     *
     * Use this to interact with workflows that are NOT children of the current workflow.
     * For child workflows, use the [ChildWorkflowHandle] returned by [startChildWorkflowWithPayloads] instead.
     *
     * External workflow handles support:
     * - [ExternalWorkflowHandle.signal] - Send signals to the external workflow
     * - [ExternalWorkflowHandle.cancel] - Request cancellation of the external workflow
     *
     * **Note:** The target workflow must be in the same namespace as the calling workflow.
     *
     * Example:
     * ```kotlin
     * @WorkflowRun
     * suspend fun WorkflowContext.run() {
     *     // Get handle to an external workflow
     *     val handle = getExternalWorkflowHandle("other-workflow-id")
     *
     *     // Signal it
     *     handle.signal("mySignal", MyData("hello"))
     *
     *     // Or cancel it
     *     handle.cancel("No longer needed")
     * }
     * ```
     *
     * @param workflowId The workflow ID of the external workflow
     * @param runId Optional run ID to target a specific run (null targets the latest run)
     * @return A handle to interact with the external workflow
     */
    fun getExternalWorkflowHandle(
        workflowId: String,
        runId: String? = null,
    ): ExternalWorkflowHandle

    /**
     * Internal method for continue-as-new that runs through the interceptor chain.
     *
     * Extension functions delegate to this method. Do not call directly.
     *
     * @param options Configuration for the new execution
     * @param typedArgs Arguments with their types for serialization
     * @throws ContinueAsNewException Always
     */
    @InternalTemporalApi
    suspend fun continueAsNewInternal(
        options: ContinueAsNewOptions,
        typedArgs: List<Pair<KType, Any?>>,
    ): Nothing
}

/**
 * Information about the currently executing workflow.
 */
data class WorkflowInfo(
    /** Unique identifier for this workflow execution. */
    val workflowId: String,
    /** Run ID for this specific run of the workflow. */
    val runId: String,
    /** The workflow type name. */
    val workflowType: String,
    /** The task queue this workflow is running on. */
    val taskQueue: String,
    /** The namespace this workflow belongs to. */
    val namespace: String,
    /** Attempt number (1-based). */
    val attempt: Int,
    /** When this workflow run started. */
    val startTime: Instant,
)

/**
 * Options for activity execution within a workflow.
 *
 * At least one of [startToCloseTimeout] or [scheduleToCloseTimeout] must be set.
 *
 * Timeout relationships:
 * - scheduleToCloseTimeout >= startToCloseTimeout (if both set)
 * - scheduleToStartTimeout < scheduleToCloseTimeout (if both set)
 * - heartbeatTimeout is required for cancellation to be detected promptly
 */
data class ActivityOptions(
    /** Maximum time for a single activity execution attempt. */
    val startToCloseTimeout: Duration? = null,
    /** Maximum time from activity scheduling to completion (including retries). */
    val scheduleToCloseTimeout: Duration? = null,
    /**
     * Maximum time from activity scheduling to worker pickup.
     * Non-retryable - exceeding this timeout fails the activity immediately.
     * Must be less than scheduleToCloseTimeout if both are set.
     */
    val scheduleToStartTimeout: Duration? = null,
    /**
     * Maximum time between heartbeats. Required for cancellation detection.
     * If not set, activity won't receive cancellation requests until it completes.
     * Heartbeat timeout should typically be shorter than startToCloseTimeout.
     */
    val heartbeatTimeout: Duration? = null,
    /**
     * Custom activity ID. If null, generated deterministically as the seq number.
     * Custom IDs must be unique within the workflow execution.
     */
    val activityId: String? = null,
    /** Task queue to run the activity on. Defaults to workflow's task queue. */
    val taskQueue: String? = null,
    /**
     * Retry policy for the activity. Uses server defaults if null.
     * Note: This uses the workflow-level RetryPolicy class for consistency.
     */
    val retryPolicy: RetryPolicy? = null,
    /** How to handle cancellation of this activity. */
    val cancellationType: ActivityCancellationType = ActivityCancellationType.TRY_CANCEL,
    /** Whether this activity should run on a versioned worker. */
    val versioningIntent: VersioningIntent = VersioningIntent.UNSPECIFIED,
    /** Headers for context propagation, tracing, and auth. Payloads allow typed serialization. */
    val headers: Map<String, TemporalPayload>? = null,
    /**
     * Priority for this activity task. Higher priority tasks are scheduled first.
     * Note: Server support for priority is not yet available. This is a placeholder.
     * Value range: 0 (lowest) to 100 (highest), default is 0.
     */
    val priority: Int = 0,
    /** If true, worker won't attempt eager execution even if slots available. */
    val disableEagerExecution: Boolean = false,
) {
    init {
        if (startToCloseTimeout == null && scheduleToCloseTimeout == null) {
            throw IllegalArgumentException("At least one of startToCloseTimeout or scheduleToCloseTimeout must be set")
        }
        if (scheduleToStartTimeout != null && scheduleToCloseTimeout != null) {
            require(scheduleToStartTimeout < scheduleToCloseTimeout) {
                "scheduleToStartTimeout must be less than scheduleToCloseTimeout"
            }
        }
    }
}

/**
 * Options for child workflow execution.
 */
data class ChildWorkflowOptions(
    /** Workflow ID for the child. Auto-generated if not specified. */
    val workflowId: String? = null,
    /** Task queue for the child workflow. Defaults to parent's task queue. */
    val taskQueue: String? = null,
    /** Maximum time for the child workflow to complete. */
    val workflowExecutionTimeout: Duration? = null,
    /** Maximum time for a single run of the child workflow. */
    val workflowRunTimeout: Duration? = null,
    /** Retry policy for the child workflow. */
    val retryPolicy: RetryPolicy? = null,
    /** How to handle parent close. */
    val parentClosePolicy: ParentClosePolicy = ParentClosePolicy.TERMINATE,
    /** How to handle cancellation of the child workflow. */
    val cancellationType: ChildWorkflowCancellationType = ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED,
    /** Typed search attributes for the child workflow. */
    val searchAttributes: TypedSearchAttributes? = null,
)

/**
 * Policy for retrying failed operations.
 */
data class RetryPolicy(
    /** Initial backoff duration. */
    val initialInterval: Duration = Duration.parse("1s"),
    /** Maximum backoff duration. */
    val maximumInterval: Duration? = null,
    /** Backoff multiplier. */
    val backoffCoefficient: Double = 2.0,
    /** Maximum number of retry attempts. */
    val maximumAttempts: Int = 0,
    /** Error types that should not be retried. */
    val nonRetryableErrorTypes: List<String> = emptyList(),
)

/**
 * Policy for handling child workflows when the parent closes.
 */
enum class ParentClosePolicy {
    /** Terminate the child workflow. */
    TERMINATE,

    /** Abandon the child workflow (let it continue running). */
    ABANDON,

    /** Cancel the child workflow. */
    REQUEST_CANCEL,
}

/**
 * Determines how child workflow cancellation is handled.
 */
enum class ChildWorkflowCancellationType {
    /**
     * Don't send a cancel request if already scheduled.
     * The child workflow continues running independently.
     */
    ABANDON,

    /**
     * Send a cancel request and immediately report the child as cancelled.
     * Does not wait for the child to acknowledge or complete.
     */
    TRY_CANCEL,

    /**
     * Send a cancel request and wait for the child to fully complete its cancellation.
     * The parent will block until the child finishes (cancelled, completed, or failed).
     */
    WAIT_CANCELLATION_COMPLETED,

    /**
     * Send a cancel request and wait for the cancel request to be acknowledged.
     * The parent will block until the server confirms the cancel was requested.
     */
    WAIT_CANCELLATION_REQUESTED,
}

/**
 * Determines how activity cancellation is handled.
 */
enum class ActivityCancellationType {
    /**
     * Immediately report the activity as cancelled without waiting.
     * The activity may still be running on the worker.
     * This is the default behavior.
     */
    TRY_CANCEL,

    /**
     * Wait for the activity to confirm cancellation before reporting.
     * Requires the activity to heartbeat to receive the cancellation.
     */
    WAIT_CANCELLATION_COMPLETED,

    /**
     * Don't request cancellation, just stop waiting for the result.
     * The activity continues running independently.
     */
    ABANDON,
}

/**
 * Specifies whether an activity should run on a versioned worker.
 */
enum class VersioningIntent {
    /** Use the workflow's current version behavior. */
    UNSPECIFIED,

    /** Run on any available worker regardless of version. */
    DEFAULT,

    /** Run on a worker with a compatible build ID. */
    COMPATIBLE,
}

/**
 * Options for continue-as-new workflow execution.
 *
 * Continue-as-new allows a workflow to complete and immediately start a new execution
 * with the same workflow ID but a new run ID. This is useful for:
 * - Long-running workflows that need to avoid history size limits
 * - Workflows that need to restart with new arguments
 * - Implementing infinite loops with state resets
 *
 * **Inheritance behavior:**
 * - Memo, search attributes, and retry policy are inherited if not explicitly set (null)
 * - To clear inherited values, pass an empty map/object
 * - Headers are NOT inherited - must be explicitly provided if needed
 * - Workflow type and task queue default to current if not specified
 */
data class ContinueAsNewOptions(
    /**
     * Workflow type for the new execution.
     * If null, uses the current workflow's type.
     */
    val workflowType: String? = null,
    /**
     * Task queue for the new execution.
     * If null, uses the current workflow's task queue.
     */
    val taskQueue: String? = null,
    /**
     * Maximum time for a single run of the new workflow.
     * Not inherited from current workflow.
     */
    val workflowRunTimeout: Duration? = null,
    /**
     * Maximum time for a single workflow task.
     * Not inherited from current workflow.
     */
    val workflowTaskTimeout: Duration? = null,
    /**
     * Memo for the new execution.
     * - null: inherit current memo
     * - empty map: clear memo
     * - non-empty map: use specified memo
     */
    val memo: Map<String, TemporalPayload>? = null,
    /**
     * Search attributes for the new execution.
     * - null: inherit current search attributes
     * - empty map: clear search attributes
     * - non-empty map: use specified search attributes
     */
    val searchAttributes: Map<String, TemporalPayload>? = null,
    /**
     * Retry policy for the new execution.
     * - null: inherit current retry policy
     * - non-null: use specified retry policy
     */
    val retryPolicy: RetryPolicy? = null,
    /**
     * Headers for the new execution.
     * NOT inherited - must be explicitly provided if needed.
     */
    val headers: Map<String, TemporalPayload>? = null,
    /**
     * Versioning intent for the new execution.
     */
    val versioningIntent: VersioningIntent = VersioningIntent.UNSPECIFIED,
)

/**
 * Exception thrown to trigger continue-as-new.
 *
 * This is a control-flow mechanism, not an error. When a workflow calls
 * [continueAsNew], this exception is thrown and caught by the workflow
 * executor to generate the appropriate command.
 *
 * This exception extends [Throwable] directly (not [Exception])
 * to prevent accidental swallowing by `catch (e: Exception)` blocks. This
 * exception should NEVER be caught by workflow code - doing so will prevent
 * the continue-as-new from happening.
 */
class ContinueAsNewException(
    /** Configuration for the new execution. */
    val options: ContinueAsNewOptions,
    /** Arguments for the new execution with their types. */
    val typedArgs: List<Pair<kotlin.reflect.KType, Any?>>,
    /**
     * Pre-serialized arguments (set when going through the interceptor chain path).
     * When non-null, these are used directly instead of serializing [typedArgs].
     */
    @InternalTemporalApi
    val serializedArgs: TemporalPayloads? = null,
) : Throwable("Workflow requested continue-as-new")
