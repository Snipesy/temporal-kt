package com.surrealdev.temporal.util

/**
 * Marker interface for execution contexts that support plugin attribute lookup.
 *
 * This interface is implemented by WorkflowContextImpl and ActivityContextImpl
 * to support plugins like dependency injection, metrics, tracing, etc.
 *
 * Plugins can store data in the [AttributeScope] hierarchy and retrieve it
 * from any execution context using [getAttributeOrNull] or [getAttribute].
 *
 * The typical hierarchy is:
 * ```
 * Application
 *   └── TaskQueue
 *         ├── WorkflowExecution (this)
 *         └── ActivityExecution (this)
 * ```
 */
interface ExecutionScope : AttributeScope {
    /**
     * Whether this is a workflow execution context (true) or activity execution context (false).
     *
     * Plugins may use this to enforce scope restrictions (e.g., activity-only dependencies).
     */
    val isWorkflowContext: Boolean
}
