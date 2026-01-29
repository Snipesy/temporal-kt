package com.surrealdev.temporal.opentelemetry

import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * Holder for OpenTelemetry metric instruments used by the plugin.
 *
 * Creates and manages counters and histograms for Temporal operations following
 * OpenTelemetry best practices:
 * - Counters for monotonic totals (task counts)
 * - Histograms for distributions (task durations)
 *
 * All metrics use bounded attribute values to control cardinality:
 * - `workflow_type`, `activity_type` - bounded by registered types
 * - `task_queue`, `namespace` - bounded by configuration
 * - `status` - bounded enum (success, failure, cancelled)
 *
 * @param meter The OpenTelemetry Meter to create instruments from
 */
class TemporalMetrics(
    meter: Meter,
) {
    // Counters

    /**
     * Counter for workflow tasks processed.
     *
     * Attributes:
     * - `temporal.workflow.type` - The workflow type name
     * - `temporal.task_queue` - The task queue name
     * - `temporal.namespace` - The namespace
     * - `status` - success, failure, or cancelled
     */
    val workflowTaskCounter: LongCounter =
        meter
            .counterBuilder("temporal.workflow.task.total")
            .setDescription("Total workflow tasks processed")
            .build()

    /**
     * Counter for activity tasks processed.
     *
     * Attributes:
     * - `temporal.activity.type` - The activity type name
     * - `temporal.task_queue` - The task queue name
     * - `temporal.namespace` - The namespace
     * - `status` - success, failure, or cancelled
     */
    val activityTaskCounter: LongCounter =
        meter
            .counterBuilder("temporal.activity.task.total")
            .setDescription("Total activity tasks processed")
            .build()

    /**
     * Counter for workers started.
     *
     * Attributes:
     * - `temporal.task_queue` - The task queue name
     * - `temporal.namespace` - The namespace
     */
    val workerStartedCounter: LongCounter =
        meter
            .counterBuilder("temporal.worker.started.total")
            .setDescription("Total workers started")
            .build()

    // Histograms

    /**
     * Histogram for workflow task processing duration.
     *
     * Records the time taken to process a workflow activation in milliseconds.
     *
     * Attributes:
     * - `temporal.workflow.type` - The workflow type name
     * - `temporal.task_queue` - The task queue name
     * - `temporal.namespace` - The namespace
     * - `status` - success, failure, or cancelled
     */
    val workflowTaskDuration: DoubleHistogram =
        meter
            .histogramBuilder("temporal.workflow.task.duration")
            .setDescription("Workflow task processing duration")
            .setUnit("ms")
            .build()

    /**
     * Histogram for activity task execution duration.
     *
     * Records the time taken to execute an activity in milliseconds.
     *
     * Attributes:
     * - `temporal.activity.type` - The activity type name
     * - `temporal.task_queue` - The task queue name
     * - `temporal.namespace` - The namespace
     * - `status` - success, failure, or cancelled
     */
    val activityTaskDuration: DoubleHistogram =
        meter
            .histogramBuilder("temporal.activity.task.duration")
            .setDescription("Activity task execution duration")
            .setUnit("ms")
            .build()
}
