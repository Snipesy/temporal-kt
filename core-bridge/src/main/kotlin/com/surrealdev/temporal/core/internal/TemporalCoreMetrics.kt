package com.surrealdev.temporal.core.internal

/**
 * FFM bridge for Temporal Core metrics operations.
 *
 * Provides access to custom metrics recording within workflows and activities.
 */
internal object TemporalCoreMetrics {
    // ============================================================
    // Metric Meter
    // ============================================================

    // TODO: temporal_core_metric_meter_new
    // MetricMeter* temporal_core_metric_meter_new(runtime)

    // TODO: temporal_core_metric_meter_free
    // void temporal_core_metric_meter_free(meter)

    // ============================================================
    // Metric Attributes
    // ============================================================

    // TODO: temporal_core_metric_attributes_new
    // MetricAttributes* temporal_core_metric_attributes_new(meter, attrs, size)

    // TODO: temporal_core_metric_attributes_new_append
    // MetricAttributes* temporal_core_metric_attributes_new_append(meter, orig, attrs, size)

    // TODO: temporal_core_metric_attributes_free
    // void temporal_core_metric_attributes_free(attrs)

    // ============================================================
    // Metric Recording
    // ============================================================

    // TODO: temporal_core_metric_new
    // Metric* temporal_core_metric_new(meter, options)

    // TODO: temporal_core_metric_free
    // void temporal_core_metric_free(metric)

    // TODO: temporal_core_metric_record_integer
    // void temporal_core_metric_record_integer(metric, value, attrs)

    // TODO: temporal_core_metric_record_float
    // void temporal_core_metric_record_float(metric, value, attrs)

    // TODO: temporal_core_metric_record_duration
    // void temporal_core_metric_record_duration(metric, value_ms, attrs)

    // ============================================================
    // Forwarded Logs
    // ============================================================

    // TODO: temporal_core_forwarded_log_target
    // ByteArrayRef temporal_core_forwarded_log_target(log)

    // TODO: temporal_core_forwarded_log_message
    // ByteArrayRef temporal_core_forwarded_log_message(log)

    // TODO: temporal_core_forwarded_log_timestamp_millis
    // uint64_t temporal_core_forwarded_log_timestamp_millis(log)

    // TODO: temporal_core_forwarded_log_fields_json
    // ByteArrayRef temporal_core_forwarded_log_fields_json(log)
}
