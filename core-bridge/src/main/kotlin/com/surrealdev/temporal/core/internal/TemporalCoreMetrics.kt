package com.surrealdev.temporal.core.internal

import io.temporal.sdkbridge.TemporalCoreMetricAttribute
import io.temporal.sdkbridge.TemporalCoreMetricAttributeValue
import io.temporal.sdkbridge.TemporalCoreMetricOptions
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

/**
 * FFM bridge for Temporal Core metrics operations.
 *
 * Provides access to custom metrics recording within workflows and activities.
 * Supports counters, gauges, and histograms with custom attributes.
 *
 * Uses jextract-generated bindings for direct function calls.
 */
internal object TemporalCoreMetrics {
    init {
        // Ensure native library is loaded before using generated bindings
        TemporalCoreFfmUtil.ensureLoaded()
    }

    // ============================================================
    // Metric Kind Constants
    // ============================================================

    /**
     * Metric kind types.
     */
    enum class MetricKind(
        val value: Int,
    ) {
        COUNTER_INTEGER(CoreBridge.CounterInteger()),
        HISTOGRAM_INTEGER(CoreBridge.HistogramInteger()),
        HISTOGRAM_FLOAT(CoreBridge.HistogramFloat()),
        HISTOGRAM_DURATION(CoreBridge.HistogramDuration()),
        GAUGE_INTEGER(CoreBridge.GaugeInteger()),
        GAUGE_FLOAT(CoreBridge.GaugeFloat()),
    }

    /**
     * Metric attribute value types.
     */
    enum class AttributeValueType(
        val value: Int,
    ) {
        STRING(CoreBridge.String_()),
        INTEGER(CoreBridge.Int()),
        FLOAT(CoreBridge.Float_()),
        BOOL(CoreBridge.Bool()),
    }

    // ============================================================
    // Metric Meter API
    // ============================================================

    /**
     * Creates a new metric meter.
     *
     * @param runtimePtr Pointer to the runtime
     * @return Pointer to the metric meter
     */
    fun createMeter(runtimePtr: MemorySegment): MemorySegment = CoreBridge.temporal_core_metric_meter_new(runtimePtr)

    /**
     * Frees a metric meter.
     *
     * @param meterPtr Pointer to the meter to free
     */
    fun freeMeter(meterPtr: MemorySegment) {
        CoreBridge.temporal_core_metric_meter_free(meterPtr)
    }

    // ============================================================
    // Metric Attributes API
    // ============================================================

    /**
     * Creates new metric attributes.
     *
     * @param meterPtr Pointer to the meter
     * @param arena Arena for allocations
     * @param attributes Map of attribute names to values (String, Long, Double, or Boolean)
     * @return Pointer to the attributes
     */
    fun createAttributes(
        meterPtr: MemorySegment,
        arena: Arena,
        attributes: Map<String, Any>,
    ): MemorySegment {
        if (attributes.isEmpty()) {
            return CoreBridge.temporal_core_metric_attributes_new(meterPtr, MemorySegment.NULL, 0L)
        }

        val attrsArray = buildAttributesArray(arena, attributes)
        return CoreBridge.temporal_core_metric_attributes_new(meterPtr, attrsArray, attributes.size.toLong())
    }

    /**
     * Creates new metric attributes by appending to existing ones.
     *
     * @param meterPtr Pointer to the meter
     * @param arena Arena for allocations
     * @param origAttrsPtr Pointer to original attributes
     * @param attributes Map of additional attributes
     * @return Pointer to the new attributes
     */
    fun appendAttributes(
        meterPtr: MemorySegment,
        arena: Arena,
        origAttrsPtr: MemorySegment,
        attributes: Map<String, Any>,
    ): MemorySegment {
        if (attributes.isEmpty()) {
            return CoreBridge.temporal_core_metric_attributes_new_append(meterPtr, origAttrsPtr, MemorySegment.NULL, 0L)
        }

        val attrsArray = buildAttributesArray(arena, attributes)
        return CoreBridge.temporal_core_metric_attributes_new_append(
            meterPtr,
            origAttrsPtr,
            attrsArray,
            attributes.size.toLong(),
        )
    }

    /**
     * Frees metric attributes.
     *
     * @param attrsPtr Pointer to the attributes to free
     */
    fun freeAttributes(attrsPtr: MemorySegment) {
        CoreBridge.temporal_core_metric_attributes_free(attrsPtr)
    }

    // ============================================================
    // Metric Creation and Recording API
    // ============================================================

    /**
     * Creates a new metric.
     *
     * @param meterPtr Pointer to the meter
     * @param arena Arena for allocations
     * @param name The metric name
     * @param description The metric description
     * @param unit The metric unit
     * @param kind The metric kind
     * @return Pointer to the metric
     */
    fun createMetric(
        meterPtr: MemorySegment,
        arena: Arena,
        name: String,
        description: String,
        unit: String,
        kind: MetricKind,
    ): MemorySegment {
        val options = TemporalCoreMetricOptions.allocate(arena)
        TemporalCoreMetricOptions.name(options, TemporalCoreFfmUtil.createByteArrayRef(arena, name))
        TemporalCoreMetricOptions.description(options, TemporalCoreFfmUtil.createByteArrayRef(arena, description))
        TemporalCoreMetricOptions.unit(options, TemporalCoreFfmUtil.createByteArrayRef(arena, unit))
        TemporalCoreMetricOptions.kind(options, kind.value)

        return CoreBridge.temporal_core_metric_new(meterPtr, options)
    }

    /**
     * Frees a metric.
     *
     * @param metricPtr Pointer to the metric to free
     */
    fun freeMetric(metricPtr: MemorySegment) {
        CoreBridge.temporal_core_metric_free(metricPtr)
    }

    /**
     * Records an integer value for a metric.
     *
     * @param metricPtr Pointer to the metric
     * @param value The value to record
     * @param attrsPtr Pointer to attributes
     */
    fun recordInteger(
        metricPtr: MemorySegment,
        value: Long,
        attrsPtr: MemorySegment,
    ) {
        CoreBridge.temporal_core_metric_record_integer(metricPtr, value, attrsPtr)
    }

    /**
     * Records a float value for a metric.
     *
     * @param metricPtr Pointer to the metric
     * @param value The value to record
     * @param attrsPtr Pointer to attributes
     */
    fun recordFloat(
        metricPtr: MemorySegment,
        value: Double,
        attrsPtr: MemorySegment,
    ) {
        CoreBridge.temporal_core_metric_record_float(metricPtr, value, attrsPtr)
    }

    /**
     * Records a duration value for a metric.
     *
     * @param metricPtr Pointer to the metric
     * @param valueMs The duration in milliseconds
     * @param attrsPtr Pointer to attributes
     */
    fun recordDuration(
        metricPtr: MemorySegment,
        valueMs: Long,
        attrsPtr: MemorySegment,
    ) {
        CoreBridge.temporal_core_metric_record_duration(metricPtr, valueMs, attrsPtr)
    }

    // ============================================================
    // Forwarded Log API
    // ============================================================

    /**
     * Gets the target from a forwarded log.
     *
     * @param arena Arena for allocations
     * @param logPtr Pointer to the forwarded log
     * @return The log target
     */
    fun getLogTarget(
        arena: Arena,
        logPtr: MemorySegment,
    ): String? {
        val ref = CoreBridge.temporal_core_forwarded_log_target(arena, logPtr)
        return TemporalCoreFfmUtil.readByteArrayRef(ref)
    }

    /**
     * Gets the message from a forwarded log.
     *
     * @param arena Arena for allocations
     * @param logPtr Pointer to the forwarded log
     * @return The log message
     */
    fun getLogMessage(
        arena: Arena,
        logPtr: MemorySegment,
    ): String? {
        val ref = CoreBridge.temporal_core_forwarded_log_message(arena, logPtr)
        return TemporalCoreFfmUtil.readByteArrayRef(ref)
    }

    /**
     * Gets the timestamp from a forwarded log.
     *
     * @param logPtr Pointer to the forwarded log
     * @return The timestamp in milliseconds
     */
    fun getLogTimestampMillis(logPtr: MemorySegment): Long =
        CoreBridge.temporal_core_forwarded_log_timestamp_millis(logPtr)

    /**
     * Gets the fields JSON from a forwarded log.
     *
     * @param arena Arena for allocations
     * @param logPtr Pointer to the forwarded log
     * @return The fields as JSON string
     */
    fun getLogFieldsJson(
        arena: Arena,
        logPtr: MemorySegment,
    ): String? {
        val ref = CoreBridge.temporal_core_forwarded_log_fields_json(arena, logPtr)
        return TemporalCoreFfmUtil.readByteArrayRef(ref)
    }

    // ============================================================
    // Helper Functions
    // ============================================================

    private fun buildAttributesArray(
        arena: Arena,
        attributes: Map<String, Any>,
    ): MemorySegment {
        val attrsArray = TemporalCoreMetricAttribute.allocateArray(attributes.size.toLong(), arena)

        attributes.entries.forEachIndexed { index, (key, value) ->
            val attr = TemporalCoreMetricAttribute.asSlice(attrsArray, index.toLong())
            TemporalCoreMetricAttribute.key(attr, TemporalCoreFfmUtil.createByteArrayRef(arena, key))

            when (value) {
                is String -> {
                    val valueUnion = TemporalCoreMetricAttributeValue.allocate(arena)
                    TemporalCoreMetricAttributeValue.string_value(
                        valueUnion,
                        TemporalCoreFfmUtil.createByteArrayRef(arena, value),
                    )
                    TemporalCoreMetricAttribute.value(attr, valueUnion)
                    TemporalCoreMetricAttribute.value_type(attr, AttributeValueType.STRING.value)
                }

                is Long -> {
                    val valueUnion = TemporalCoreMetricAttributeValue.allocate(arena)
                    TemporalCoreMetricAttributeValue.int_value(valueUnion, value)
                    TemporalCoreMetricAttribute.value(attr, valueUnion)
                    TemporalCoreMetricAttribute.value_type(attr, AttributeValueType.INTEGER.value)
                }

                is Int -> {
                    val valueUnion = TemporalCoreMetricAttributeValue.allocate(arena)
                    TemporalCoreMetricAttributeValue.int_value(valueUnion, value.toLong())
                    TemporalCoreMetricAttribute.value(attr, valueUnion)
                    TemporalCoreMetricAttribute.value_type(attr, AttributeValueType.INTEGER.value)
                }

                is Double -> {
                    val valueUnion = TemporalCoreMetricAttributeValue.allocate(arena)
                    TemporalCoreMetricAttributeValue.float_value(valueUnion, value)
                    TemporalCoreMetricAttribute.value(attr, valueUnion)
                    TemporalCoreMetricAttribute.value_type(attr, AttributeValueType.FLOAT.value)
                }

                is Float -> {
                    val valueUnion = TemporalCoreMetricAttributeValue.allocate(arena)
                    TemporalCoreMetricAttributeValue.float_value(valueUnion, value.toDouble())
                    TemporalCoreMetricAttribute.value(attr, valueUnion)
                    TemporalCoreMetricAttribute.value_type(attr, AttributeValueType.FLOAT.value)
                }

                is Boolean -> {
                    val valueUnion = TemporalCoreMetricAttributeValue.allocate(arena)
                    TemporalCoreMetricAttributeValue.bool_value(valueUnion, value)
                    TemporalCoreMetricAttribute.value(attr, valueUnion)
                    TemporalCoreMetricAttribute.value_type(attr, AttributeValueType.BOOL.value)
                }

                else -> {
                    throw IllegalArgumentException("Unsupported attribute value type: ${value::class}")
                }
            }
        }

        return attrsArray
    }
}
