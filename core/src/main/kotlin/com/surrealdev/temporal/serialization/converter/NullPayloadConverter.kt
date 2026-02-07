package com.surrealdev.temporal.serialization.converter

import com.surrealdev.temporal.common.TemporalByteString
import com.surrealdev.temporal.common.TemporalPayload
import com.surrealdev.temporal.common.exceptions.PayloadSerializationException
import com.surrealdev.temporal.serialization.PayloadConverter
import kotlin.reflect.KType

private val NULL_METADATA =
    mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_NULL))

/**
 * Converter for null values.
 *
 * Produces payloads with `encoding: binary/null` and no data.
 * This should always be the first converter in the chain so null values
 * are handled before any other converter attempts serialization.
 */
object NullPayloadConverter : PayloadConverter {
    override val encoding: String = TemporalPayload.ENCODING_NULL

    override fun toPayload(
        typeInfo: KType,
        value: Any?,
    ): TemporalPayload? {
        if (value != null) return null
        return TemporalPayload.create(NULL_METADATA)
    }

    override fun fromPayload(
        typeInfo: KType,
        payload: TemporalPayload,
    ): Any? {
        if (!typeInfo.isMarkedNullable) {
            throw PayloadSerializationException(
                "Cannot deserialize null payload to non-nullable type $typeInfo",
            )
        }
        return null
    }
}
