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

        fun createFromProtoBytes(data: ByteArray): TemporalPayload {
            val proto =
                io.temporal.api.common.v1.Payload
                    .parseFrom(data)
            return TemporalPayload(proto)
        }

        fun createFromProtoStream(input: InputStream): TemporalPayload {
            val proto =
                io.temporal.api.common.v1.Payload
                    .parseFrom(input)
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

    /**
     * Serialize the entire proto payload to a byte array, including metadata. (use with caution)
     */
    val asByteArray: ByteArray
        get() = proto.toByteArray()

    fun writeTo(output: OutputStream) {
        proto.writeTo(output)
    }

    val serializedSize: Int
        get() = proto.serializedSize

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
) : Iterable<TemporalPayload> {
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

        fun createFromProtoBytes(data: ByteArray): TemporalPayloads {
            val proto =
                io.temporal.api.common.v1.Payloads
                    .parseFrom(data)
            return TemporalPayloads(proto)
        }

        fun createFromProtoStream(input: InputStream): TemporalPayloads {
            val proto =
                io.temporal.api.common.v1.Payloads
                    .parseFrom(input)
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

    override fun iterator(): Iterator<TemporalPayload> = payloads.iterator()

    val asByteArray: ByteArray
        get() = proto.toByteArray()

    val serializedSize: Int
        get() = proto.serializedSize

    fun writeTo(output: OutputStream) {
        proto.writeTo(output)
    }
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

/**
 * Represents payloads that have been through [PayloadCodec.encode][com.surrealdev.temporal.serialization.PayloadCodec.encode].
 *
 * This is a distinct type from [TemporalPayloads] to provide **compile-time safety**:
 * if a code path forgets to encode or decode, the type mismatch will cause a compiler error.
 * This prevents accidental bypasses of codec transformations (compression, encryption, etc.).
 *
 * **Usage pattern:**
 * - Outbound (sending to server): serialize to [TemporalPayloads], then `codec.encode(...)` to get [EncodedTemporalPayloads]
 * - Inbound (receiving from server): wrap raw proto payloads with [fromProtoPayloadList], then `codec.decode(...)` to get [TemporalPayloads]
 *
 * This is a zero-cost `@JvmInline value class` wrapper around Temporal's internal protobuf Payloads message.
 * At runtime, no allocation occurs — the wrapper is erased and operations go directly to the underlying proto.
 *
 * @see TemporalPayloads The unencoded counterpart used before encoding / after decoding
 * @see com.surrealdev.temporal.serialization.PayloadCodec The codec interface that produces and consumes this type
 */
@JvmInline
value class EncodedTemporalPayloads(
    @PublishedApi internal val proto: io.temporal.api.common.v1.Payloads,
) : Iterable<TemporalPayload> {
    companion object {
        /** Empty encoded payloads. */
        val EMPTY: EncodedTemporalPayloads =
            EncodedTemporalPayloads(
                io.temporal.api.common.v1.Payloads
                    .getDefaultInstance(),
            )

        /**
         * Creates [EncodedTemporalPayloads] from a list of raw protobuf [Payload][io.temporal.api.common.v1.Payload] objects.
         *
         * Use this when receiving payloads from the wire (e.g., from activation jobs or gRPC responses)
         * that need to be decoded via [PayloadCodec.decode][com.surrealdev.temporal.serialization.PayloadCodec.decode].
         */
        fun fromProtoPayloadList(payloads: List<io.temporal.api.common.v1.Payload>): EncodedTemporalPayloads =
            EncodedTemporalPayloads(
                io.temporal.api.common.v1.Payloads
                    .newBuilder()
                    .addAllPayloads(payloads)
                    .build(),
            )

        /**
         * Creates [EncodedTemporalPayloads] from a list of [TemporalPayload].
         */
        @InternalTemporalApi
        fun of(payloads: List<TemporalPayload>): EncodedTemporalPayloads {
            val proto =
                io.temporal.api.common.v1.Payloads
                    .newBuilder()
                    .addAllPayloads(payloads.map { it.proto })
                    .build()
            return EncodedTemporalPayloads(proto)
        }

        /**
         * Creates [EncodedTemporalPayloads] by parsing a serialized protobuf byte array.
         *
         * Use this when loading encoded payloads from storage or receiving them over a non-gRPC transport.
         */
        fun createFromProtoBytes(data: ByteArray): EncodedTemporalPayloads {
            val proto =
                io.temporal.api.common.v1.Payloads
                    .parseFrom(data)
            return EncodedTemporalPayloads(proto)
        }

        /**
         * Creates [EncodedTemporalPayloads] by parsing a serialized protobuf stream.
         *
         * Use this when loading encoded payloads from an [InputStream] without intermediate copies.
         */
        fun createFromProtoStream(input: InputStream): EncodedTemporalPayloads {
            val proto =
                io.temporal.api.common.v1.Payloads
                    .parseFrom(input)
            return EncodedTemporalPayloads(proto)
        }
    }

    /** Number of encoded payloads. */
    val size: Int get() = proto.payloadsCount

    /** Whether this contains no payloads. */
    val isEmpty: Boolean get() = proto.payloadsCount == 0

    /** Access encoded payloads as a list. */
    val payloads: List<TemporalPayload>
        get() = proto.payloadsList.map { TemporalPayload(it) }

    /** Valid indices for this collection. */
    val indices: IntRange
        get() = 0 until size

    /** Get the encoded payload at [index] as a [TemporalPayload]. */
    operator fun get(index: Int): TemporalPayload = TemporalPayload(proto.getPayloads(index))

    override fun toString(): String = "EncodedTemporalPayloads(size=$size)"

    override fun iterator(): Iterator<TemporalPayload> = payloads.iterator()

    /** Serialized protobuf representation as a byte array. */
    val asByteArray: ByteArray
        get() = proto.toByteArray()

    /** Size in bytes of the serialized protobuf representation. */
    val serializedSize: Int
        get() = proto.serializedSize

    /** Writes the serialized protobuf representation to [output]. */
    fun writeTo(output: OutputStream) {
        proto.writeTo(output)
    }
}

/**
 * Converts [EncodedTemporalPayloads] to the underlying protobuf Payloads.
 *
 * Use this when you need to pass encoded payloads to protobuf builders (e.g., gRPC requests).
 * Zero-cost operation — just unwraps the value class.
 *
 * @suppress Internal API — do not use directly
 */
@InternalTemporalApi
fun EncodedTemporalPayloads.toProto(): io.temporal.api.common.v1.Payloads = this.proto

/**
 * Converts list of TemporalPayload to TemporalPayloads.
 */
fun List<TemporalPayload>.toEncodedTemporalPayloads(): EncodedTemporalPayloads = EncodedTemporalPayloads.of(this)
