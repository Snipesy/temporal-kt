package com.surrealdev.temporal.core.internal

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.metrics.DoubleGauge
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongGauge
import io.opentelemetry.api.metrics.LongHistogram
import io.opentelemetry.api.metrics.Meter
import io.temporal.sdkbridge.TemporalCoreCustomMetricAttribute
import io.temporal.sdkbridge.TemporalCoreCustomMetricAttributeValue
import io.temporal.sdkbridge.TemporalCoreCustomMetricMeterAttributesFreeCallback
import io.temporal.sdkbridge.TemporalCoreCustomMetricMeterAttributesNewCallback
import io.temporal.sdkbridge.TemporalCoreCustomMetricMeterMeterFreeCallback
import io.temporal.sdkbridge.TemporalCoreCustomMetricMeterMetricFreeCallback
import io.temporal.sdkbridge.TemporalCoreCustomMetricMeterMetricNewCallback
import io.temporal.sdkbridge.TemporalCoreCustomMetricMeterMetricRecordDurationCallback
import io.temporal.sdkbridge.TemporalCoreCustomMetricMeterMetricRecordFloatCallback
import io.temporal.sdkbridge.TemporalCoreCustomMetricMeterMetricRecordIntegerCallback
import io.temporal.sdkbridge.TemporalCoreMetricsOptions
import io.temporal.sdkbridge.TemporalCoreTelemetryOptions
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import io.temporal.sdkbridge.`TemporalCoreCustomMetricMeter$0` as CustomMetricMeterStruct

/**
 * Bridges Core SDK (Rust) metrics into the Java OpenTelemetry SDK.
 *
 * Core emits internal metrics (schedule-to-start latency, sticky cache hit rates,
 * worker slot usage, etc.) via a `CustomMetricMeter` callback mechanism. This class
 * implements the 8 synchronous callbacks that Core calls into, translating each
 * operation into the corresponding OTel API call.
 *
 * Every callback is wrapped in try-catch because these are invoked from
 * Rust threads via FFM upcall stubs. An unhandled exception crossing the FFI boundary
 * is undefined behavior and will crash the process.
 *
 * Lifecycle: create before `TemporalRuntime`, close **after** `freeRuntime()` returns.
 * Core may call metric callbacks during its own shutdown; only after `freeRuntime`
 * is it safe to invalidate the upcall stubs.
 */
internal class CoreMetricsBridge(
    private val meter: Meter,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(CoreMetricsBridge::class.java)

    // Arena that owns upcall stub lifetime — must outlive Core runtime
    private val arena: Arena = Arena.ofShared()

    // Handle maps (same pattern as PendingCallbacks)
    private val nextInstrumentId = AtomicLong(1)
    private val instruments = ConcurrentHashMap<Long, OtelInstrument>()

    private val nextAttrsId = AtomicLong(1)
    private val attributesMap = ConcurrentHashMap<Long, Attributes>()

    /**
     * The native struct pointer to pass into `TemporalCoreMetricsOptions.custom_meter`.
     * Allocated in [arena] and populated with upcall stubs for all 8 callbacks.
     */
    val meterStructPtr: MemorySegment

    init {
        val struct = CustomMetricMeterStruct.allocate(arena)

        CustomMetricMeterStruct.metric_new(
            struct,
            TemporalCoreCustomMetricMeterMetricNewCallback.allocate(::metricNew, arena),
        )
        CustomMetricMeterStruct.metric_free(
            struct,
            TemporalCoreCustomMetricMeterMetricFreeCallback.allocate(::metricFree, arena),
        )
        CustomMetricMeterStruct.metric_record_integer(
            struct,
            TemporalCoreCustomMetricMeterMetricRecordIntegerCallback.allocate(::metricRecordInteger, arena),
        )
        CustomMetricMeterStruct.metric_record_float(
            struct,
            TemporalCoreCustomMetricMeterMetricRecordFloatCallback.allocate(::metricRecordFloat, arena),
        )
        CustomMetricMeterStruct.metric_record_duration(
            struct,
            TemporalCoreCustomMetricMeterMetricRecordDurationCallback.allocate(::metricRecordDuration, arena),
        )
        CustomMetricMeterStruct.attributes_new(
            struct,
            TemporalCoreCustomMetricMeterAttributesNewCallback.allocate(::attributesNew, arena),
        )
        CustomMetricMeterStruct.attributes_free(
            struct,
            TemporalCoreCustomMetricMeterAttributesFreeCallback.allocate(::attributesFree, arena),
        )
        CustomMetricMeterStruct.meter_free(
            struct,
            TemporalCoreCustomMetricMeterMeterFreeCallback.allocate(::meterFree, arena),
        )

        meterStructPtr = struct
    }

    /**
     * Builds the telemetry options MemorySegment for passing to runtime creation.
     * The returned pointer is allocated in [arena] and valid for the bridge's lifetime.
     */
    fun buildTelemetryOptions(): MemorySegment {
        val metricsOptions = TemporalCoreMetricsOptions.allocate(arena)
        TemporalCoreMetricsOptions.opentelemetry(metricsOptions, MemorySegment.NULL)
        TemporalCoreMetricsOptions.prometheus(metricsOptions, MemorySegment.NULL)
        TemporalCoreMetricsOptions.custom_meter(metricsOptions, meterStructPtr)
        TemporalCoreMetricsOptions.attach_service_name(metricsOptions, true)
        TemporalCoreMetricsOptions.global_tags(
            metricsOptions,
            TemporalCoreFfmUtil.createEmptyByteArrayRef(arena),
        )
        TemporalCoreMetricsOptions.metric_prefix(
            metricsOptions,
            TemporalCoreFfmUtil.createEmptyByteArrayRef(arena),
        )

        val telemetryOptions = TemporalCoreTelemetryOptions.allocate(arena)
        TemporalCoreTelemetryOptions.logging(telemetryOptions, MemorySegment.NULL)
        TemporalCoreTelemetryOptions.metrics(telemetryOptions, metricsOptions)

        return telemetryOptions
    }

    override fun close() {
        instruments.clear()
        attributesMap.clear()
        arena.close()
    }

    // ============================================================
    // Callback implementations
    //
    // CRITICAL: Every callback MUST be wrapped in try-catch(Throwable).
    // These are invoked from Rust threads via FFM upcall stubs. An
    // unhandled exception crossing the FFI boundary is undefined
    // behavior and will crash the process.
    // ============================================================

    /**
     * Called by Core to create a new metric instrument.
     * name, description, unit are TemporalCoreByteArrayRef structs passed by value.
     */
    private fun metricNew(
        name: MemorySegment,
        description: MemorySegment,
        unit: MemorySegment,
        kind: Int,
    ): MemorySegment =
        try {
            val metricName = TemporalCoreFfmUtil.readByteArrayRef(name) ?: "unknown"
            val metricDesc = TemporalCoreFfmUtil.readByteArrayRef(description) ?: ""
            val metricUnit = TemporalCoreFfmUtil.readByteArrayRef(unit) ?: ""

            val instrument = createInstrument(metricName, metricDesc, metricUnit, kind)
            val id = nextInstrumentId.getAndIncrement()
            instruments[id] = instrument
            MemorySegment.ofAddress(id)
        } catch (t: Throwable) {
            logger.error("metricNew callback failed", t)
            MemorySegment.NULL
        }

    private fun metricFree(metric: MemorySegment) {
        try {
            instruments.remove(metric.address())
        } catch (t: Throwable) {
            logger.error("metricFree callback failed", t)
        }
    }

    private fun metricRecordInteger(
        metric: MemorySegment,
        value: Long,
        attributes: MemorySegment,
    ) {
        try {
            val instrument = instruments[metric.address()] ?: return
            val attrs = resolveAttributes(attributes)

            when (instrument) {
                is OtelInstrument.CounterLong -> {
                    instrument.counter.add(value, attrs)
                }

                is OtelInstrument.HistogramLong -> {
                    instrument.histogram.record(value, attrs)
                }

                is OtelInstrument.GaugeLong -> {
                    instrument.gauge.set(value, attrs)
                }

                is OtelInstrument.HistogramDuration -> {
                    instrument.histogram.record(value, attrs)
                }

                is OtelInstrument.HistogramDouble,
                is OtelInstrument.GaugeDouble,
                -> {
                    logger.warn(
                        "Unexpected integer recording on float instrument (handle={})",
                        metric.address(),
                    )
                }
            }
        } catch (t: Throwable) {
            logger.error("metricRecordInteger callback failed", t)
        }
    }

    private fun metricRecordFloat(
        metric: MemorySegment,
        value: Double,
        attributes: MemorySegment,
    ) {
        try {
            val instrument = instruments[metric.address()] ?: return
            val attrs = resolveAttributes(attributes)

            when (instrument) {
                is OtelInstrument.HistogramDouble -> {
                    instrument.histogram.record(value, attrs)
                }

                is OtelInstrument.GaugeDouble -> {
                    instrument.gauge.set(value, attrs)
                }

                else -> {
                    logger.warn(
                        "Unexpected float recording on integer instrument (handle={})",
                        metric.address(),
                    )
                }
            }
        } catch (t: Throwable) {
            logger.error("metricRecordFloat callback failed", t)
        }
    }

    private fun metricRecordDuration(
        metric: MemorySegment,
        valueMs: Long,
        attributes: MemorySegment,
    ) {
        try {
            val instrument = instruments[metric.address()] ?: return
            val attrs = resolveAttributes(attributes)

            when (instrument) {
                is OtelInstrument.HistogramDuration -> {
                    instrument.histogram.record(valueMs, attrs)
                }

                else -> {
                    logger.warn(
                        "Unexpected duration recording on non-duration instrument (handle={})",
                        metric.address(),
                    )
                }
            }
        } catch (t: Throwable) {
            logger.error("metricRecordDuration callback failed", t)
        }
    }

    /**
     * Called by Core to create attributes, optionally merging with existing ones.
     * @param appendFrom handle to existing attributes (or NULL/0 for fresh)
     * @param attrsPtr pointer to native TemporalCoreCustomMetricAttribute array
     * @param size number of elements in the array
     */
    private fun attributesNew(
        appendFrom: MemorySegment,
        attrsPtr: MemorySegment,
        size: Long,
    ): MemorySegment =
        try {
            val builder: AttributesBuilder =
                if (appendFrom != MemorySegment.NULL && appendFrom.address() != 0L) {
                    val existing = attributesMap[appendFrom.address()]
                    if (existing != null) existing.toBuilder() else Attributes.builder()
                } else {
                    Attributes.builder()
                }

            if (attrsPtr != MemorySegment.NULL && size > 0) {
                val attrSize = TemporalCoreCustomMetricAttribute.sizeof()
                val arraySegment = attrsPtr.reinterpret(attrSize * size)

                for (i in 0 until size) {
                    val attr = TemporalCoreCustomMetricAttribute.asSlice(arraySegment, i)
                    val key =
                        TemporalCoreFfmUtil.readByteArrayRef(
                            TemporalCoreCustomMetricAttribute.key(attr),
                        ) ?: continue
                    val valueType = TemporalCoreCustomMetricAttribute.value_type(attr)
                    val valueUnion = TemporalCoreCustomMetricAttribute.value(attr)

                    when (valueType) {
                        TemporalCoreMetrics.AttributeValueType.STRING.value -> {
                            // string_value has same {data, size} layout as ByteArrayRef
                            val strValue =
                                TemporalCoreFfmUtil.readByteArrayRef(
                                    TemporalCoreCustomMetricAttributeValue.string_value(valueUnion),
                                )
                            builder.put(
                                io.opentelemetry.api.common.AttributeKey
                                    .stringKey(key),
                                strValue ?: "",
                            )
                        }

                        TemporalCoreMetrics.AttributeValueType.INTEGER.value -> {
                            val intValue =
                                TemporalCoreCustomMetricAttributeValue.int_value(valueUnion)
                            builder.put(
                                io.opentelemetry.api.common.AttributeKey
                                    .longKey(key),
                                intValue,
                            )
                        }

                        TemporalCoreMetrics.AttributeValueType.FLOAT.value -> {
                            val floatValue =
                                TemporalCoreCustomMetricAttributeValue.float_value(valueUnion)
                            builder.put(
                                io.opentelemetry.api.common.AttributeKey
                                    .doubleKey(key),
                                floatValue,
                            )
                        }

                        TemporalCoreMetrics.AttributeValueType.BOOL.value -> {
                            val boolValue =
                                TemporalCoreCustomMetricAttributeValue.bool_value(valueUnion)
                            builder.put(
                                io.opentelemetry.api.common.AttributeKey
                                    .booleanKey(key),
                                boolValue,
                            )
                        }
                    }
                }
            }

            val attrs = builder.build()
            val id = nextAttrsId.getAndIncrement()
            attributesMap[id] = attrs
            MemorySegment.ofAddress(id)
        } catch (t: Throwable) {
            logger.error("attributesNew callback failed", t)
            MemorySegment.NULL
        }

    private fun attributesFree(attributes: MemorySegment) {
        try {
            attributesMap.remove(attributes.address())
        } catch (t: Throwable) {
            logger.error("attributesFree callback failed", t)
        }
    }

    private fun meterFree(
        @Suppress("UNUSED_PARAMETER") meterPtr: MemorySegment,
    ) {
        try {
            instruments.clear()
            attributesMap.clear()
        } catch (t: Throwable) {
            logger.error("meterFree callback failed", t)
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun resolveAttributes(handle: MemorySegment): Attributes {
        if (handle == MemorySegment.NULL || handle.address() == 0L) return Attributes.empty()
        return attributesMap[handle.address()] ?: Attributes.empty()
    }

    private fun createInstrument(
        name: String,
        description: String,
        unit: String,
        kind: Int,
    ): OtelInstrument =
        when (kind) {
            TemporalCoreMetrics.MetricKind.COUNTER_INTEGER.value -> {
                OtelInstrument.CounterLong(
                    meter
                        .counterBuilder(name)
                        .setDescription(description)
                        .setUnit(unit)
                        .build(),
                )
            }

            TemporalCoreMetrics.MetricKind.HISTOGRAM_INTEGER.value -> {
                OtelInstrument.HistogramLong(
                    meter
                        .histogramBuilder(name)
                        .ofLongs()
                        .setDescription(description)
                        .setUnit(unit)
                        .build(),
                )
            }

            TemporalCoreMetrics.MetricKind.HISTOGRAM_FLOAT.value -> {
                OtelInstrument.HistogramDouble(
                    meter
                        .histogramBuilder(name)
                        .setDescription(description)
                        .setUnit(unit)
                        .build(),
                )
            }

            TemporalCoreMetrics.MetricKind.HISTOGRAM_DURATION.value -> {
                // Core SDK sends "duration" as the unit string, but the actual values
                // are always in milliseconds (Rust side calls value.as_millis()).
                // Override to the proper OTel unit.
                OtelInstrument.HistogramDuration(
                    meter
                        .histogramBuilder(name)
                        .ofLongs()
                        .setDescription(description)
                        .setUnit("ms")
                        .build(),
                )
            }

            TemporalCoreMetrics.MetricKind.GAUGE_INTEGER.value -> {
                OtelInstrument.GaugeLong(
                    meter
                        .gaugeBuilder(name)
                        .ofLongs()
                        .setDescription(description)
                        .setUnit(unit)
                        .build(),
                )
            }

            TemporalCoreMetrics.MetricKind.GAUGE_FLOAT.value -> {
                OtelInstrument.GaugeDouble(
                    meter
                        .gaugeBuilder(name)
                        .setDescription(description)
                        .setUnit(unit)
                        .build(),
                )
            }

            else -> {
                logger.warn(
                    "Unknown metric kind {}, falling back to LongCounter for '{}'",
                    kind,
                    name,
                )
                OtelInstrument.CounterLong(
                    meter
                        .counterBuilder(name)
                        .setDescription(description)
                        .setUnit(unit)
                        .build(),
                )
            }
        }

    /**
     * Sealed class wrapping the polymorphic OTel instrument types.
     */
    private sealed class OtelInstrument {
        data class CounterLong(
            val counter: LongCounter,
        ) : OtelInstrument()

        data class HistogramLong(
            val histogram: LongHistogram,
        ) : OtelInstrument()

        data class HistogramDouble(
            val histogram: DoubleHistogram,
        ) : OtelInstrument()

        data class HistogramDuration(
            val histogram: LongHistogram,
        ) : OtelInstrument()

        data class GaugeLong(
            val gauge: LongGauge,
        ) : OtelInstrument()

        data class GaugeDouble(
            val gauge: DoubleGauge,
        ) : OtelInstrument()
    }
}
