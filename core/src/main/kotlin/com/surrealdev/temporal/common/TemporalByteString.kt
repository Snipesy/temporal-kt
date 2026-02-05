package com.surrealdev.temporal.common

import com.google.protobuf.ByteString
import java.io.InputStream

/**
 * A wrapper around binary data that avoids exposing protobuf [ByteString] in the public API.
 *
 * Use this for metadata values, payload data references, and anywhere binary data
 * needs to be passed around without copying.
 *
 * This is a zero-cost inline wrapper at runtime.
 */
@JvmInline
value class TemporalByteString
    @PublishedApi
    internal constructor(
        @PublishedApi internal val inner: ByteString,
    ) {
        companion object {
            /** Empty byte string. */
            val EMPTY: TemporalByteString = TemporalByteString(ByteString.EMPTY)

            /** Creates a [TemporalByteString] from a UTF-8 encoded string. */
            fun fromUtf8(value: String): TemporalByteString = TemporalByteString(ByteString.copyFromUtf8(value))

            /** Creates a [TemporalByteString] by copying the given byte array. */
            fun from(data: ByteArray): TemporalByteString = TemporalByteString(ByteString.copyFrom(data))
        }

        /** Size in bytes. */
        val size: Int get() = inner.size()

        /** Whether this contains no data. */
        val isEmpty: Boolean get() = inner.isEmpty

        /** Returns a copy of the data as a byte array. */
        fun toByteArray(): ByteArray = inner.toByteArray()

        /** Decodes the data as a UTF-8 string. */
        fun toStringUtf8(): String = inner.toStringUtf8()

        /** Returns an [InputStream] over the data without copying. */
        fun newInput(): InputStream = inner.newInput()

        override fun toString(): String = "TemporalByteString(size=$size)"
    }
