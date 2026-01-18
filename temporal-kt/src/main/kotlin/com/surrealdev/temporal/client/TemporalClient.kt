package com.surrealdev.temporal.client

import kotlin.time.Duration

/**
 * Client for interacting with the Temporal service.
 *
 * Usage:
 * ```kotlin
 * val client = app.client {
 *     // Optional overrides
 * }
 *
 * val stub = client.newWorkflowStub<MyWorkflow>(
 *     workflowType = "MyWorkflow",
 *     taskQueue = "my-task-queue"
 * )
 *
 * val result = stub.execute(MyArg("hello"))
 * ```
 */
class TemporalClient internal constructor(
    private val config: TemporalClientConfig,
) {
    /**
     * Creates a workflow stub for starting and interacting with workflows.
     *
     * @param T The workflow interface type
     * @param workflowType The workflow type name
     * @param taskQueue The task queue to run the workflow on
     * @param options Additional workflow options
     * @return A stub implementing the workflow interface
     */
    inline fun <reified T : Any> newWorkflowStub(
        workflowType: String,
        taskQueue: String,
        options: WorkflowOptions = WorkflowOptions(),
    ): T {
        // TODO: Implement dynamic proxy generation
        TODO("Workflow stub generation not yet implemented")
    }

    /**
     * Creates a workflow stub for an existing workflow execution.
     *
     * @param T The workflow interface type
     * @param workflowId The workflow ID
     * @param runId Optional run ID (uses latest if not specified)
     * @return A stub implementing the workflow interface
     */
    inline fun <reified T : Any> getWorkflowStub(
        workflowId: String,
        runId: String? = null,
    ): T {
        // TODO: Implement dynamic proxy generation for existing workflow
        TODO("Workflow stub generation not yet implemented")
    }

    /**
     * Starts a workflow execution asynchronously.
     *
     * @param T The workflow interface type
     * @param workflowId The workflow ID
     * @param taskQueue The task queue
     * @param options Workflow options
     * @param startFn Function to invoke on the stub to start the workflow
     * @return A handle to the workflow execution
     */
    suspend inline fun <reified T : Any, R> startWorkflow(
        workflowId: String,
        taskQueue: String,
        options: WorkflowOptions = WorkflowOptions(),
        startFn: suspend T.() -> R,
    ): WorkflowHandle<R> {
        // TODO: Implement workflow start
        TODO("Workflow start not yet implemented")
    }

    /**
     * Gets a handle to an existing workflow execution.
     *
     * @param R The workflow result type
     * @param workflowId The workflow ID
     * @param runId Optional run ID
     * @return A handle to the workflow execution
     */
    fun <R> getWorkflowHandle(
        workflowId: String,
        runId: String? = null,
    ): WorkflowHandle<R> {
        // TODO: Implement
        TODO("Get workflow handle not yet implemented")
    }

    /**
     * Closes the client connection.
     */
    suspend fun close() {
        // TODO: Clean up resources
    }
}

/**
 * Configuration for a Temporal client.
 */
class TemporalClientConfig {
    /** Target address of the Temporal service. */
    var target: String = "localhost:7233"

    /** Namespace to connect to. */
    var namespace: String = "default"

    /** Whether to use TLS. */
    var useTls: Boolean = false
}

/**
 * Options for starting a workflow.
 */
data class WorkflowOptions(
    /** Unique ID for the workflow. Auto-generated if not specified. */
    val workflowId: String? = null,
    /** Timeout for the entire workflow execution. */
    val workflowExecutionTimeout: Duration? = null,
    /** Timeout for a single workflow run. */
    val workflowRunTimeout: Duration? = null,
    /** Timeout for a single workflow task. */
    val workflowTaskTimeout: Duration? = null,
    /** How to handle workflow ID conflicts. */
    val workflowIdConflictPolicy: WorkflowIdConflictPolicy = WorkflowIdConflictPolicy.FAIL,
    /** Memo fields. */
    val memo: Map<String, Any>? = null,
    /** Search attributes. */
    val searchAttributes: Map<String, Any>? = null,
)

/**
 * Policy for handling workflow ID conflicts.
 */
enum class WorkflowIdConflictPolicy {
    /** Fail if a workflow with the same ID is already running. */
    FAIL,

    /** Use the existing workflow if one is running. */
    USE_EXISTING,

    /** Terminate the existing workflow and start a new one. */
    TERMINATE_EXISTING,
}

/**
 * Handle to a workflow execution.
 */
interface WorkflowHandle<R> {
    /** The workflow ID. */
    val workflowId: String

    /** The run ID. */
    val runId: String?

    /**
     * Waits for the workflow to complete and returns the result.
     */
    suspend fun result(): R

    /**
     * Sends a signal to the workflow.
     */
    suspend fun signal(
        signalName: String,
        vararg args: Any?,
    )

    /**
     * Queries the workflow.
     */
    suspend fun <T> query(
        queryName: String,
        vararg args: Any?,
    ): T

    /**
     * Requests cancellation of the workflow.
     */
    suspend fun cancel()

    /**
     * Terminates the workflow.
     */
    suspend fun terminate(reason: String? = null)
}
