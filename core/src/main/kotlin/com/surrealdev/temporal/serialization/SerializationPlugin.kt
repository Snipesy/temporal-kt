package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.ApplicationPlugin
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
 * Usage:
 * ```kotlin
 * val app = TemporalApplication {
 *     connection { ... }
 * }
 *
 * app.install(SerializationPlugin) {
 *     // Use default JSON settings (creates [Null, JSON] chain)
 *     json()
 *
 *     // Or customize JSON
 *     json {
 *         prettyPrint = false
 *         ignoreUnknownKeys = true
 *         encodeDefaults = true
 *     }
 *
 *     // Or build an explicit converter chain
 *     converters {
 *         null()
 *         converter(MyProtobufConverter())
 *         json()  // catch-all, should be last
 *     }
 *
 *     // Or use a custom serializer
 *     custom(MyCustomSerializer())
 * }
 * ```
 *
 * If not installed, a default [CompositePayloadSerializer] with sensible defaults is used.
 */
class SerializationPluginInstance internal constructor(
    /**
     * The configured [PayloadSerializer] for the application.
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
 * Factory for creating the [SerializationPlugin] plugin.
 */
object SerializationPlugin : ApplicationPlugin<SerializationPluginConfig, SerializationPluginInstance> {
    override val key: AttributeKey<SerializationPluginInstance> = AttributeKey(name = "PayloadSerialization")

    override fun install(
        pipeline: TemporalApplication,
        configure: SerializationPluginConfig.() -> Unit,
    ): SerializationPluginInstance {
        val config = SerializationPluginConfig().apply(configure)
        return config.build()
    }
}

/**
 * Gets the [PayloadSerializer] from the application's installed plugins.
 *
 * If [SerializationPlugin] was not explicitly installed, returns a default
 * [CompositePayloadSerializer] with `[NullPayloadConverter, JsonPayloadConverter]`.
 *
 * @return The configured [PayloadSerializer]
 */
fun TemporalApplication.payloadSerializer(): PayloadSerializer =
    pluginOrNull(SerializationPlugin)?.serializer ?: CompositePayloadSerializer.default()

/**
 * Gets the [SerializationPluginInstance] if installed, or null.
 */
fun TemporalApplication.payloadSerializationOrNull(): SerializationPluginInstance? = pluginOrNull(SerializationPlugin)
