package com.surrealdev.temporal.serialization.codec

import com.surrealdev.temporal.common.TemporalPayloads
import com.surrealdev.temporal.serialization.PayloadCodec

/**
 * Chains multiple codecs together.
 *
 * Encoding applies codecs in order: `codec[0] → codec[1] → ... → codec[n]`
 * Decoding applies codecs in reverse: `codec[n] → ... → codec[1] → codec[0]`
 *
 * Example use case: compress then encrypt
 * ```kotlin
 * ChainedCodec(listOf(CompressionCodec(), EncryptionCodec()))
 * // encode: compress → encrypt (smaller data to encrypt, more efficient)
 * // decode: decrypt → decompress
 * ```
 *
 * @param codecs The codecs to chain, applied in order for encoding
 */
class ChainedCodec(
    internal val codecs: List<PayloadCodec>,
) : PayloadCodec {
    override suspend fun encode(payloads: TemporalPayloads): TemporalPayloads {
        var result = payloads
        for (codec in codecs) {
            result = codec.encode(result)
        }
        return result
    }

    override suspend fun decode(payloads: TemporalPayloads): TemporalPayloads {
        var result = payloads
        for (codec in codecs.reversed()) {
            result = codec.decode(result)
        }
        return result
    }
}
