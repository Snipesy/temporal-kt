package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.plugin.ApplicationPlugin
import com.surrealdev.temporal.application.plugin.pluginOrNull
import com.surrealdev.temporal.serialization.codec.ChainedCodec
import com.surrealdev.temporal.serialization.codec.CompressionCodec
import com.surrealdev.temporal.util.AttributeKey

/**
 * Plugin instance containing the configured codec.
 */
class PayloadCodecPluginInstance internal constructor(
    /**
     * The configured [PayloadCodec] for the application.
     */
    val codec: PayloadCodec,
)

/**
 * DSL builder for chaining multiple codecs together.
 *
 * Codecs are applied in order for encoding, reversed for decoding.
 */
@TemporalDsl
class ChainedCodecBuilder {
    private val codecs = mutableListOf<PayloadCodec>()

    /**
     * Adds GZIP compression codec.
     *
     * @param threshold Minimum payload size in bytes to trigger compression (default: 256)
     */
    fun compression(threshold: Int = 256) {
        codecs.add(CompressionCodec(threshold = threshold))
    }

    /**
     * Adds a custom codec to the chain.
     */
    fun codec(customCodec: PayloadCodec) {
        codecs.add(customCodec)
    }

    internal fun build(): PayloadCodec =
        when (codecs.size) {
            0 -> NoOpCodec
            1 -> codecs.single()
            else -> ChainedCodec(codecs.toList())
        }
}

/**
 * Configuration DSL for [PayloadCodecPlugin].
 *
 * Example with single codec:
 * ```kotlin
 * app.install(PayloadCodecPlugin) {
 *     compression(threshold = 1024)
 * }
 * ```
 *
 * Example with chained codecs:
 * ```kotlin
 * app.install(PayloadCodecPlugin) {
 *     chained {
 *         compression()
 *         codec(myEncryptionCodec)
 *     }
 * }
 * ```
 *
 * Example with custom codec:
 * ```kotlin
 * app.install(PayloadCodecPlugin) {
 *     codec = myCustomCodec
 * }
 * ```
 */
@TemporalDsl
class PayloadCodecConfig {
    /**
     * The codec to use. Can be set directly or configured via DSL methods.
     */
    var codec: PayloadCodec? = null

    /**
     * Configures GZIP compression codec.
     *
     * Payloads smaller than [threshold] bytes are not compressed.
     * Compression is only applied if it reduces the payload size.
     *
     * @param threshold Minimum payload size in bytes to trigger compression (default: 256)
     */
    fun compression(threshold: Int = 256) {
        codec = CompressionCodec(threshold = threshold)
    }

    /**
     * Configures a chain of codecs.
     *
     * Codecs are applied in order for encoding, reversed for decoding.
     *
     * Example:
     * ```kotlin
     * chained {
     *     compression()
     *     codec(myEncryptionCodec)
     * }
     * ```
     * Results in: compress → encrypt (encode), decrypt → decompress (decode)
     */
    fun chained(configure: ChainedCodecBuilder.() -> Unit) {
        codec = ChainedCodecBuilder().apply(configure).build()
    }

    internal fun build(): PayloadCodecPluginInstance {
        val effectiveCodec = codec ?: NoOpCodec
        return PayloadCodecPluginInstance(effectiveCodec)
    }
}

/**
 * Plugin for configuring payload codecs.
 *
 * Codecs transform payloads after serialization (encode) and before deserialization (decode).
 * Common use cases include compression and encryption.
 *
 * Pipeline:
 * ```
 * OUTBOUND: Object -> [PayloadSerializer] -> Payload -> [PayloadCodec.encode] -> Temporal Server
 * INBOUND: Temporal Server -> [PayloadCodec.decode] -> Payload -> [PayloadSerializer] -> Object
 * ```
 *
 * Usage:
 * ```kotlin
 * val app = TemporalApplication {
 *     connection { ... }
 * }
 *
 * // Simple compression
 * app.install(PayloadCodecPlugin) {
 *     compression(threshold = 1024)
 * }
 *
 * // Chained codecs (compression then encryption)
 * app.install(PayloadCodecPlugin) {
 *     chained {
 *         compression()
 *         codec(myEncryptionCodec)
 *     }
 * }
 *
 * // Serialization is configured separately
 * app.install(PayloadSerialization) {
 *     json { ignoreUnknownKeys = true }
 * }
 * ```
 */
object PayloadCodecPlugin : ApplicationPlugin<PayloadCodecConfig, PayloadCodecPluginInstance> {
    override val key: AttributeKey<PayloadCodecPluginInstance> = AttributeKey(name = "PayloadCodec")

    override fun install(
        pipeline: TemporalApplication,
        configure: PayloadCodecConfig.() -> Unit,
    ): PayloadCodecPluginInstance {
        val config = PayloadCodecConfig().apply(configure)
        return config.build()
    }
}

/**
 * Gets the configured [PayloadCodec] from the application, or null if not installed.
 */
fun TemporalApplication.payloadCodecOrNull(): PayloadCodec? = pluginOrNull(PayloadCodecPlugin)?.codec
