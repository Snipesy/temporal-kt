package com.surrealdev.temporal.serialization

import com.surrealdev.temporal.application.PluginKey
import com.surrealdev.temporal.application.TemporalPlugin
import com.surrealdev.temporal.application.TemporalPluginFactory
import kotlinx.serialization.json.Json

/**
 * Plugin for configuring kotlinx.serialization for payload encoding.
 *
 * Usage:
 * ```kotlin
 * TemporalApplication {
 *     install(KotlinxSerialization) {
 *         json = Json {
 *             prettyPrint = true
 *             encodeDefaults = true
 *             ignoreUnknownKeys = true
 *         }
 *     }
 * }
 * ```
 */
class KotlinxSerializationPlugin internal constructor(
    val json: Json,
) : TemporalPlugin {
    override val key: PluginKey<KotlinxSerializationPlugin> = Key

    companion object Key : PluginKey<KotlinxSerializationPlugin>("KotlinxSerialization")
}

/**
 * Configuration for the kotlinx.serialization plugin.
 */
class KotlinxSerializationConfig {
    /**
     * The Json instance to use for serialization.
     */
    var json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
}

/**
 * Factory for creating the kotlinx.serialization plugin.
 */
object KotlinxSerialization : TemporalPluginFactory<KotlinxSerializationConfig, KotlinxSerializationPlugin> {
    override fun create(configure: KotlinxSerializationConfig.() -> Unit): KotlinxSerializationPlugin {
        val config = KotlinxSerializationConfig().apply(configure)
        return KotlinxSerializationPlugin(config.json)
    }
}
