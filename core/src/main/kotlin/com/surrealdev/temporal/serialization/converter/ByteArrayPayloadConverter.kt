package com.surrealdev.temporal.serialization.converter

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.serialization.PayloadConverter
import kotlin.reflect.KType

private val BINARY_METADATA =
    mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_BINARY))

/**
 * Converter for raw [ByteArray] values.
 *
 * Produces payloads with `encoding: binary/plain`. Only handles values that are
 * already `ByteArray` — returns `null` for all other types, allowing the next
 * converter in the chain to handle them.
 *
 * Not included in the default converter chain. Add it explicitly:
 * ```kotlin
 * install(SerializationPlugin) {
 *     converters {
 *         null()
 *         byteArray()
 *         json()
 *     }
 * }
 * ```
 */
object ByteArrayPayloadConverter : PayloadConverter {
    override val encoding: String = TemporalPayload.ENCODING_BINARY

    override fun toPayload(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload? {
        if (value !is ByteArray) return null
        return TemporalPayload.create(value, BINARY_METADATA)
    }

    override fun fromPayload(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any? = payload.data
}
