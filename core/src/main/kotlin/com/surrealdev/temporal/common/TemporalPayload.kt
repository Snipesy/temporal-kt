package com.surrealdev.temporal.common

import com.google.protobuf.ByteString
import com.surrealdev.temporal.annotation.InternalTemporalApi
import java.io.InputStream
import java.io.OutputStream

/**
 * Represents a serialized value in Temporal.
 *
 * A payload consists of:
 * - Binary data (the serialized value)
 * - Metadata (encoding type, compression, etc.)
 *
 * This is a zero-cost wrapper around Temporal's internal protobuf Payload representation.
 * Most users don't need to work with payloads directly - use typed workflow/activity methods instead.
 *
 * Note: This is a value class wrapping the proto Payload for maximum efficiency.
 * Conversions at boundaries are zero-cost at runtime.
 */
@JvmInline
value class TemporalPayload(
    @PublishedApi internal val proto: io.temporal.api.common.v1.Payload,
) {
    companion object {
        /** Metadata key for encoding type (e.g., "json/plain", "binary/protobuf"). */
        const val METADATA_ENCODING = "encoding"

        /** Metadata key for message type information. */
        const val METADATA_MESSAGE_TYPE = "messageType"

        /** Encoding value for JSON serialization. */
        const val ENCODING_JSON = "json/plain"

        /** Encoding value for binary serialization. */
        const val ENCODING_BINARY = "binary/plain"

        /** Encoding value for null values. */
        const val ENCODING_NULL = "binary/null"

        /** Encoding value for protobuf serialization. */
        const val ENCODING_PROTOBUF = "binary/protobuf"

        /**
         * Creates a TemporalPayload from raw data and metadata.
         */
        fun create(
            data: ByteArray,
            metadata: Map<String, ByteArray>,
        ): TemporalPayload {
            val proto =
                io.temporal.api.common.v1.Payload
                    .newBuilder()
                    .setData(ByteString.copyFrom(data))
                    .putAllMetadata(metadata.mapValues { ByteString.copyFrom(it.value) })
                    .build()
            return TemporalPayload(proto)
        }

        /**
         * Creates a TemporalPayload by writing data directly to a stream.
         *
         * The [writeData] callback receives an [OutputStream] to write payload data into.
         * This avoids intermediate copies - the stream output becomes the payload data directly.
         *
         * ```kotlin
         * val payload = TemporalPayload.create(
         *     metadata = mapOf("encoding" to TemporalByteString.fromUtf8("json/plain")),
         * ) { stream ->
         *     json.encodeToStream(serializer, value, stream)
         * }
         * ```
         */
        inline fun create(
            metadata: Map<String, TemporalByteString>,
            writeData: (OutputStream) -> Unit,
        ): TemporalPayload {
            val output = ByteString.newOutput()
            writeData(output)
            val proto =
                io.temporal.api.common.v1.Payload
                    .newBuilder()
                    .setData(output.toByteString())
                    .putAllMetadata(metadata.mapValues { it.value.inner })
                    .build()
            return TemporalPayload(proto)
        }

        /**
         * Creates a TemporalPayload from a byte array and typed metadata.
         */
        @JvmName("createFromByteStrings")
        fun create(
            data: ByteArray,
            metadata: Map<String, TemporalByteString>,
        ): TemporalPayload {
            val proto =
                io.temporal.api.common.v1.Payload
                    .newBuilder()
                    .setData(ByteString.copyFrom(data))
                    .putAllMetadata(metadata.mapValues { it.value.inner })
                    .build()
            return TemporalPayload(proto)
        }

        /**
         * Creates a TemporalPayload with only metadata and no data.
         *
         * Useful for null-encoding payloads or other metadata-only markers.
         */
        fun create(metadata: Map<String, TemporalByteString>): TemporalPayload {
            val proto =
                io.temporal.api.common.v1.Payload
                    .newBuilder()
                    .putAllMetadata(metadata.mapValues { it.value.inner })
                    .build()
            return TemporalPayload(proto)
        }
    }

    /** Size of the serialized data in bytes. */
    val dataSize: Int
        get() = proto.data.size()

    /** Copy of the serialized binary data. */
    val data: ByteArray
        get() = proto.data.toByteArray()

    /**
     * Get the data as an InputStream. (avoid copy)
     */
    fun dataInputStream(): InputStream = proto.data.newInput()

    /** Metadata describing how to interpret the data (encoding, type, etc.). */
    val metadata: Map<String, ByteArray>
        get() = proto.metadataMap.mapValues { it.value.toByteArray() }

    /** Metadata as [TemporalByteString] values, avoiding byte array copies. */
    val metadataByteStrings: Map<String, TemporalByteString>
        get() = proto.metadataMap.mapValues { TemporalByteString(it.value) }

    /** Get metadata value as UTF-8 string. */
    fun getMetadataString(key: String): String? = proto.metadataMap[key]?.toStringUtf8()

    /** Get the encoding type from metadata. */
    val encoding: String?
        get() = getMetadataString(METADATA_ENCODING)

    override fun toString(): String {
        val encoding = encoding ?: "unknown"
        return "TemporalPayload(encoding=$encoding, size=${proto.data.size()} bytes, " +
            "metadata=${proto.metadataMap.keys})"
    }
}

/**
 * Converts protobuf Payload to TemporalPayload.
 * Zero-cost operation - just wraps the proto.
 * @suppress Internal API - do not use directly
 */
@InternalTemporalApi
fun io.temporal.api.common.v1.Payload.toTemporal(): TemporalPayload = TemporalPayload(this)

/**
 * Converts TemporalPayload to protobuf Payload.
 * Zero-cost operation - just unwraps the proto.
 * @suppress Internal API - do not use directly
 */
@InternalTemporalApi
fun TemporalPayload.toProto(): io.temporal.api.common.v1.Payload = this.proto

/**
 * Represents a list of serialized values in Temporal.
 *
 * This is a zero-cost wrapper around Temporal's internal protobuf Payloads message.
 * Provides efficient access to multiple payloads without copying.
 */
@JvmInline
value class TemporalPayloads(
    @PublishedApi internal val proto: io.temporal.api.common.v1.Payloads,
) {
    companion object {
        /** Empty payloads. */
        val EMPTY: TemporalPayloads =
            TemporalPayloads(
                io.temporal.api.common.v1.Payloads
                    .getDefaultInstance(),
            )

        /**
         * Creates TemporalPayloads from a list of TemporalPayload.
         */
        @InternalTemporalApi
        fun of(payloads: List<TemporalPayload>): TemporalPayloads {
            val proto =
                io.temporal.api.common.v1.Payloads
                    .newBuilder()
                    .addAllPayloads(payloads.map { it.proto })
                    .build()
            return TemporalPayloads(proto)
        }
    }

    /** Number of payloads. */
    val size: Int
        get() = proto.payloadsCount

    /** Whether this contains no payloads. */
    val isEmpty: Boolean
        get() = proto.payloadsCount == 0

    /** Access payloads as a list. */
    val payloads: List<TemporalPayload>
        get() = proto.payloadsList.map { TemporalPayload(it) }

    /** Valid indices for this collection. */
    val indices: IntRange
        get() = 0 until size

    /** Get payload at index. */
    operator fun get(index: Int): TemporalPayload = TemporalPayload(proto.getPayloads(index))

    override fun toString(): String = "TemporalPayloads(size=$size)"
}

/**
 * Converts protobuf Payloads to TemporalPayloads.
 * Zero-cost operation - just wraps the proto.
 * @suppress Internal API - do not use directly
 */
@InternalTemporalApi
fun io.temporal.api.common.v1.Payloads.toTemporal(): TemporalPayloads = TemporalPayloads(this)

/**
 * Converts TemporalPayloads to protobuf Payloads.
 * Zero-cost operation - just unwraps the proto.
 * @suppress Internal API - do not use directly
 */
@InternalTemporalApi
fun TemporalPayloads.toProto(): io.temporal.api.common.v1.Payloads = this.proto

/**
 * Converts list of TemporalPayload to TemporalPayloads.
 */
fun List<TemporalPayload>.toTemporalPayloads(): TemporalPayloads = TemporalPayloads.of(this)
