package com.surrealdev.temporal.workflow

import com.surrealdev.temporal.serialization.PayloadSerializer
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Context available within a workflow execution.
 *
 * This context provides access to workflow information and operations like
 * scheduling activities, timers, and child workflows.
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
 * As a [CoroutineScope], workflow code can use structured concurrency with
 * deterministic execution. The scope uses a custom dispatcher that ensures
 * all coroutines run synchronously on the workflow task thread.
 */
interface WorkflowContext : CoroutineScope {
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
     * Creates a proxy to invoke activities of the specified type.
     *
     * @param T The activity interface type
     * @param options Configuration for activity execution
     * @return A proxy implementing the activity interface
     */
    suspend fun <T : Any> activity(
        activityType: String,
        options: ActivityOptions = ActivityOptions(),
    ): T

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
     * @param condition A function that returns true when the workflow should continue
     */
    suspend fun awaitCondition(condition: () -> Boolean)

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
     * Starts a child workflow.
     *
     * @param T The child workflow interface type
     * @param workflowType The workflow type name
     * @param options Configuration for the child workflow
     * @return A handle to the child workflow
     */
    suspend fun <T : Any> childWorkflow(
        workflowType: String,
        options: ChildWorkflowOptions = ChildWorkflowOptions(),
    ): T

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
    fun setQueryHandler(
        name: String,
        handler: (suspend (List<io.temporal.api.common.v1.Payload>) -> io.temporal.api.common.v1.Payload)?,
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
    fun setDynamicQueryHandler(
        handler: (
            suspend (
                queryType: String,
                args: List<io.temporal.api.common.v1.Payload>,
            ) -> io.temporal.api.common.v1.Payload
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
    fun setSignalHandler(
        name: String,
        handler: (suspend (List<io.temporal.api.common.v1.Payload>) -> Unit)?,
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
    fun setDynamicSignalHandler(
        handler: (suspend (signalName: String, args: List<io.temporal.api.common.v1.Payload>) -> Unit)?,
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
    fun setUpdateHandler(
        name: String,
        handler: (suspend (List<io.temporal.api.common.v1.Payload>) -> io.temporal.api.common.v1.Payload)?,
        validator: ((List<io.temporal.api.common.v1.Payload>) -> Unit)? = null,
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
    fun setDynamicUpdateHandler(
        handler: (
            suspend (
                updateName: String,
                args: List<io.temporal.api.common.v1.Payload>,
            ) -> io.temporal.api.common.v1.Payload
        )?,
        validator: ((updateName: String, args: List<io.temporal.api.common.v1.Payload>) -> Unit)? = null,
    )
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
 */
data class ActivityOptions(
    /** Maximum time for a single activity execution attempt. */
    val startToCloseTimeout: Duration? = null,
    /** Maximum time from activity scheduling to completion. */
    val scheduleToCloseTimeout: Duration? = null,
    /** Maximum time from activity scheduling to worker pickup. */
    val scheduleToStartTimeout: Duration? = null,
    /** Heartbeat timeout for long-running activities. */
    val heartbeatTimeout: Duration? = null,
    /** Retry policy for the activity. */
    val retryPolicy: RetryPolicy? = null,
    /** Task queue to run the activity on. Defaults to workflow's task queue. */
    val taskQueue: String? = null,
)

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
