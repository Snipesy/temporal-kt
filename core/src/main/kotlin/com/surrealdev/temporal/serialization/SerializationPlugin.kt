package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.plugin.PluginPipeline
import com.surrealdev.temporal.application.plugin.ScopedPlugin
import com.surrealdev.temporal.application.plugin.pluginOrNull
import com.surrealdev.temporal.serialization.converter.ByteArrayPayloadConverter
import com.surrealdev.temporal.serialization.converter.JsonPayloadConverter
import com.surrealdev.temporal.serialization.converter.NullPayloadConverter
import com.surrealdev.temporal.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * Plugin for configuring payload serialization.
 *
 * This plugin provides the [PayloadSerializer] used throughout the application
 * for converting workflow/activity inputs and outputs to Temporal Payloads.
 *
 * By default, a [CompositePayloadSerializer] is used with an ordered converter chain:
 * `[NullPayloadConverter, JsonPayloadConverter]`. This follows the Temporal SDK convention
 * where each converter handles a specific encoding format.
 *
 * This is a [ScopedPlugin] and can be installed at both the application level and the
 * task queue level. A task-queue-level install overrides the application-level serializer
 * for that queue only.
 *
 * Usage:
 * ```kotlin
 * val app = TemporalApplication {
 *     connection { ... }
 * }
 *
 * // Application-level (default for all task queues)
 * app.install(SerializationPlugin) {
 *     json()
 * }
 *
 * // Task-queue-level override
 * app.taskQueue("special-queue") {
 *     install(SerializationPlugin) {
 *         json { prettyPrint = true }
 *     }
 * }
 * ```
 *
 * If not installed, a default [CompositePayloadSerializer] with sensible defaults is used.
 */
class SerializationPluginInstance internal constructor(
    /**
     * The configured [PayloadSerializer] for this pipeline.
     */
    val serializer: PayloadSerializer,
)

/**
 * Configuration DSL for [SerializationPlugin].
 */
@TemporalDsl
class SerializationPluginConfig {
    private var serializer: PayloadSerializer? = null

    /**
     * Configure JSON serialization using kotlinx.serialization.
     *
     * This creates a [CompositePayloadSerializer] with `[NullPayloadConverter, JsonPayloadConverter]`.
     *
     * @param configure Optional configuration block for [Json] builder
     */
    fun json(configure: JsonBuilder.() -> Unit = {}) {
        val json =
            Json {
                // Sensible defaults
                encodeDefaults = true
                ignoreUnknownKeys = true
                // Apply user configuration
                configure()
            }
        serializer = CompositePayloadSerializer.withJson(JsonPayloadConverter(json))
    }

    /**
     * Use a custom [PayloadSerializer] implementation.
     *
     * This bypasses the converter chain entirely and uses the provided serializer directly.
     *
     * @param customSerializer The custom serializer to use
     */
    fun custom(customSerializer: PayloadSerializer) {
        serializer = customSerializer
    }

    /**
     * Build an explicit converter chain.
     *
     * Converters are tried in order for serialization (first non-null result wins).
     * For deserialization, the payload's encoding metadata selects the matching converter.
     *
     * The chain should typically start with `null()` and end with a catch-all like `json()`.
     *
     * ```kotlin
     * converters {
     *     null()
     *     byteArray()
     *     converter(MyProtobufConverter())
     *     json()  // catch-all, should be last
     * }
     * ```
     */
    fun converters(configure: ConverterChainBuilder.() -> Unit) {
        serializer = ConverterChainBuilder().apply(configure).build()
    }

    internal fun build(): SerializationPluginInstance {
        val effectiveSerializer = serializer ?: CompositePayloadSerializer.default()
        return SerializationPluginInstance(effectiveSerializer)
    }
}

/**
 * DSL builder for constructing an ordered [PayloadConverter] chain.
 *
 * Converters are tried in the order they are added. For serialization, the first
 * converter that returns a non-null payload wins. For deserialization, the payload's
 * encoding metadata selects the matching converter.
 */
@TemporalDsl
class ConverterChainBuilder {
    private val converters = mutableListOf<PayloadConverter>()

    /**
     * Adds the [NullPayloadConverter] for handling null values.
     *
     * Should typically be the first converter in the chain.
     */
    fun `null`() {
        converters.add(NullPayloadConverter)
    }

    /**
     * Adds the [ByteArrayPayloadConverter] for handling raw byte arrays.
     */
    fun byteArray() {
        converters.add(ByteArrayPayloadConverter)
    }

    /**
     * Adds a [JsonPayloadConverter] for JSON serialization via kotlinx.serialization.
     *
     * This is a catch-all converter (always produces a payload) and should be placed
     * last in the chain.
     *
     * @param configure Optional configuration block for [Json] builder
     */
    fun json(configure: JsonBuilder.() -> Unit = {}) {
        val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                configure()
            }
        converters.add(JsonPayloadConverter(json))
    }

    /**
     * Adds a custom [PayloadConverter] to the chain.
     */
    fun converter(custom: PayloadConverter) {
        converters.add(custom)
    }

    internal fun build(): CompositePayloadSerializer = CompositePayloadSerializer(converters.toList())
}

/**
 * Scoped plugin for configuring payload serialization.
 *
 * Can be installed at both the application level and the task queue level.
 */
object SerializationPlugin : ScopedPlugin<SerializationPluginConfig, SerializationPluginInstance> {
    override val key: AttributeKey<SerializationPluginInstance> = AttributeKey(name = "PayloadSerialization")

    override fun install(
        pipeline: PluginPipeline,
        configure: SerializationPluginConfig.() -> Unit,
    ): SerializationPluginInstance {
        val config = SerializationPluginConfig().apply(configure)
        return config.build()
    }
}

/**
 * Gets the [PayloadSerializer] from this pipeline's installed plugins.
 *
 * For [com.surrealdev.temporal.application.TaskQueueBuilder], this performs hierarchical lookup
 * (task queue first, then parent application).
 *
 * If [SerializationPlugin] was not explicitly installed, returns a default
 * [CompositePayloadSerializer] with `[NullPayloadConverter, JsonPayloadConverter]`.
 *
 * @return The configured [PayloadSerializer]
 */
fun PluginPipeline.payloadSerializer(): PayloadSerializer =
    pluginOrNull(SerializationPlugin)?.serializer ?: CompositePayloadSerializer.default()

/**
 * Gets the [SerializationPluginInstance] if installed, or null.
 */
fun PluginPipeline.payloadSerializationOrNull(): SerializationPluginInstance? = pluginOrNull(SerializationPlugin)
