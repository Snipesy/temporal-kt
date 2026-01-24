package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.ApplicationPlugin
import com.surrealdev.temporal.application.plugin.pluginOrNull
import com.surrealdev.temporal.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * Plugin for configuring payload serialization.
 *
 * This plugin provides the [PayloadSerializer] used throughout the application
 * for converting workflow/activity inputs and outputs to Temporal Payloads.
 *
 * Usage:
 * ```kotlin
 * val app = TemporalApplication {
 *     connection { ... }
 * }
 *
 * app.install(PayloadSerialization) {
 *     // Use default JSON settings
 *     json()
 *
 *     // Or customize JSON
 *     json {
 *         prettyPrint = false
 *         ignoreUnknownKeys = true
 *         encodeDefaults = true
 *     }
 *
 *     // Or use a custom serializer
 *     custom(MyCustomSerializer())
 * }
 * ```
 *
 * If not installed, a default [KotlinxJsonSerializer] with sensible defaults is used.
 */
class PayloadSerializationPlugin internal constructor(
    /**
     * The configured [PayloadSerializer] for the application.
     */
    val serializer: PayloadSerializer,
)

/**
 * Configuration DSL for [PayloadSerialization].
 */
@TemporalDsl
class PayloadSerializationConfig {
    private var serializer: PayloadSerializer? = null

    /**
     * Configure JSON serialization using kotlinx.serialization.
     *
     * This is the default and recommended serialization format.
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
        serializer = KotlinxJsonSerializer(json)
    }

    /**
     * Use a custom [PayloadSerializer] implementation.
     *
     * @param customSerializer The custom serializer to use
     */
    fun custom(customSerializer: PayloadSerializer) {
        serializer = customSerializer
    }

    /**
     * Register multiple serializers that will be tried in order.
     * The first one that can handle the type wins.
     *
     * Note: Currently not implemented. Use [custom] with a composite serializer.
     */
    internal fun composite(vararg serializers: PayloadSerializer) {
        TODO("Composite serializer support")
    }

    internal fun build(): PayloadSerializationPlugin {
        // If no serializer was configured, use defaults
        val effectiveSerializer = serializer ?: KotlinxJsonSerializer.default()
        return PayloadSerializationPlugin(effectiveSerializer)
    }
}

/**
 * Factory for creating the [PayloadSerialization] plugin.
 */
object PayloadSerialization : ApplicationPlugin<PayloadSerializationConfig, PayloadSerializationPlugin> {
    override val key: AttributeKey<PayloadSerializationPlugin> = AttributeKey(name = "PayloadSerialization")

    override fun install(
        pipeline: TemporalApplication,
        configure: PayloadSerializationConfig.() -> Unit,
    ): PayloadSerializationPlugin {
        val config = PayloadSerializationConfig().apply(configure)
        return config.build()
    }
}

/**
 * Gets the [PayloadSerializer] from the application's installed plugins.
 *
 * If [PayloadSerialization] was not explicitly installed, returns a default serializer.
 *
 * @return The configured [PayloadSerializer]
 */
fun TemporalApplication.payloadSerializer(): PayloadSerializer =
    pluginOrNull(PayloadSerialization)?.serializer ?: KotlinxJsonSerializer.default()

/**
 * Gets the [PayloadSerializationPlugin] if installed, or null.
 */
fun TemporalApplication.payloadSerializationOrNull(): PayloadSerializationPlugin? = pluginOrNull(PayloadSerialization)
