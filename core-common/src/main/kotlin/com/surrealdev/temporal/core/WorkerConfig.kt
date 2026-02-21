package com.surrealdev.temporal.core

/**
 * Configuration options for a Temporal worker.
 */
data class WorkerConfig(
    val maxCachedWorkflows: Int = 1000,
    val enableWorkflows: Boolean = true,
    val enableActivities: Boolean = true,
    val enableNexus: Boolean = false,
    val deploymentOptions: WorkerDeploymentOptions? = null,
    /**
     * Maximum number of concurrent workflow task executions.
     * Controls the Core SDK's workflow slot supplier.
     */
    val maxConcurrentWorkflowTasks: Int = 100,
    /**
     * Maximum number of concurrent activity executions.
     * Controls the Core SDK's activity slot supplier.
     */
    val maxConcurrentActivities: Int = 100,
    /**
     * Maximum interval for throttling activity heartbeats in milliseconds.
     * Heartbeats will be throttled to at most this interval.
     */
    val maxHeartbeatThrottleIntervalMs: Long = 60_000L,
    /**
     * Default interval for throttling activity heartbeats in milliseconds.
     * Used when no heartbeat timeout is set. When a heartbeat timeout is configured,
     * throttling uses 80% of that timeout instead.
     */
    val defaultHeartbeatThrottleIntervalMs: Long = 30_000L,
    /**
     * Poller behavior for workflow tasks. Controls how many concurrent gRPC long-polls are
     * issued to the Temporal server for workflow activations.
     */
    val workflowPollerBehavior: CorePollerBehavior = CorePollerBehavior.SimpleMaximum(5),
    /**
     * Poller behavior for activity tasks. Controls how many concurrent gRPC long-polls are
     * issued to the Temporal server for activity tasks.
     */
    val activityPollerBehavior: CorePollerBehavior = CorePollerBehavior.SimpleMaximum(5),
    /**
     * Maximum number of activities per second this worker will execute, regardless of task queue
     * capacity. Use to protect downstream services from burst load. 0.0 means no limit.
     */
    val maxActivitiesPerSecond: Double = 0.0,
    /**
     * Server-enforced rate limit on activities per second across all workers on this task queue.
     * Takes precedence over per-worker limits when set lower. 0.0 means no limit.
     */
    val maxTaskQueueActivitiesPerSecond: Double = 0.0,
    /**
     * Worker identity string sent to the Temporal server, visible in the UI and history.
     * Null means the Core SDK default is used (pid@hostname).
     */
    val workerIdentity: String? = null,
    /**
     * When true, any nondeterminism error in a workflow will be reported as a workflow failure
     * rather than causing the workflow task to fail and retry. This surfaces nondeterminism
     * bugs immediately in workflow history instead of looping indefinitely.
     *
     * Default: false
     */
    val nondeterminismAsWorkflowFail: Boolean = false,
    /**
     * Workflow type names for which nondeterminism errors should be reported as workflow
     * failures (overrides [nondeterminismAsWorkflowFail] for the listed types when that
     * flag is false, and can also be used to opt specific types out when the flag is true).
     *
     * Default: empty (no per-type overrides)
     */
    val nondeterminismAsWorkflowFailForTypes: List<String> = emptyList(),
    /**
     * Fraction of max workflow pollers dedicated to the nonsticky (global) task queue.
     * Only applies when using [CorePollerBehavior.SimpleMaximum].
     *
     * Pollers are split between the sticky queue (tasks for workflows already cached on this
     * worker) and the nonsticky queue (new tasks from any workflow). With the default of 0.2
     * and [CorePollerBehavior.SimpleMaximum] of 5, only 1 poller pulls from the nonsticky
     * queue. For high-fanout workloads with many concurrent new tasks, increasing this to
     * 0.3–0.5 reduces schedule-to-start latency at the cost of slightly more cache 3misses.
     *
     * The minimum for either poller type is always 1.
     *
     * Default: 0.2
     */
    val nonstickyToStickyPollRatio: Float = 0.2f,
    /**
     * How long (ms) a workflow task may sit on a worker's sticky queue before the server
     * moves it to the global nonsticky queue where any worker can pick it up.
     *
     * This is the worst-case schedule-to-start latency during worker failures or rolling
     * deployments: tasks directed to a crashed worker's sticky queue wait up to this timeout
     * before becoming available globally. Lowering this value reduces failover latency.
     *
     * Default: 10,000ms (10 seconds)
     */
    val stickyQueueScheduleToStartTimeoutMs: Long = 10_000L,
)
