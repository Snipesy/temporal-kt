package com.surrealdev.temporal.core.internal

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import io.temporal.sdkbridge.TemporalCoreByteArray
import io.temporal.sdkbridge.TemporalCoreByteArrayRef
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout
import io.temporal.sdkbridge.temporal_sdk_core_c_bridge_h as CoreBridge

/**
 * Common FFM utilities for the Temporal Core bridge.
 *
 * Contains helper functions for working with byte arrays and strings
 * shared across all bridge objects.
 *
 * Uses jextract-generated struct classes for memory layout handling.
 */
internal object TemporalCoreFfmUtil {
    /**
     * Cached empty byte array for parsing empty protobuf messages.
     * Avoids allocation for frequent empty responses (e.g., Empty messages).
     */
    private val EMPTY_BYTES = ByteArray(0)

    /**
     * A valid non-null pointer for empty byte arrays.
     * Rust's std::slice::from_raw_parts requires a non-null, properly aligned pointer
     * even when size is 0 (undefined behavior otherwise). This singleton provides
     * a valid pointer for all empty ByteArrayRef structs.
     */
    private val EMPTY_DATA_SEGMENT: MemorySegment =
        java.lang.foreign.Arena
            .global()
            .allocate(1L)

    /**
     * Ensures the native library is loaded.
     * Must be called before using any jextract-generated bindings.
     */
    fun ensureLoaded() {
        NativeLoader.load()
    }

    // ============================================================
    // ByteArray Reading Functions
    // ============================================================

    /**
     * Reads a string from a TemporalCoreByteArray pointer.
     *
     * @param byteArrayPtr Pointer to TemporalCoreByteArray struct
     * @return The string content, or null if pointer is NULL or empty
     */
    fun readByteArray(byteArrayPtr: MemorySegment): String? {
        if (byteArrayPtr == MemorySegment.NULL) return null

        val reinterpreted = byteArrayPtr.reinterpret(TemporalCoreByteArray.sizeof())
        val dataPtr = TemporalCoreByteArray.data(reinterpreted)
        val size = TemporalCoreByteArray.size(reinterpreted)

        if (dataPtr == MemorySegment.NULL || size == 0L) return null

        val dataSegment = dataPtr.reinterpret(size)
        val bytes = ByteArray(size.toInt())
        MemorySegment.copy(dataSegment, ValueLayout.JAVA_BYTE, 0, bytes, 0, size.toInt())
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Reads raw bytes from a TemporalCoreByteArray pointer.
     *
     * @param byteArrayPtr Pointer to TemporalCoreByteArray struct
     * @return The byte content, or null if pointer is NULL or empty
     */
    fun readByteArrayAsBytes(byteArrayPtr: MemorySegment): ByteArray? {
        if (byteArrayPtr == MemorySegment.NULL) return null

        val reinterpreted = byteArrayPtr.reinterpret(TemporalCoreByteArray.sizeof())
        val dataPtr = TemporalCoreByteArray.data(reinterpreted)
        val size = TemporalCoreByteArray.size(reinterpreted)

        if (dataPtr == MemorySegment.NULL || size == 0L) return null

        val dataSegment = dataPtr.reinterpret(size)
        val bytes = ByteArray(size.toInt())
        MemorySegment.copy(dataSegment, ValueLayout.JAVA_BYTE, 0, bytes, 0, size.toInt())
        return bytes
    }

    /**
     * Reads a string from an inline TemporalCoreByteArrayRef struct.
     *
     * @param byteArrayRefSegment MemorySegment containing the ByteArrayRef inline
     * @return The string content, or null if empty
     */
    fun readByteArrayRef(byteArrayRefSegment: MemorySegment): String? {
        val dataPtr = TemporalCoreByteArrayRef.data(byteArrayRefSegment)
        val size = TemporalCoreByteArrayRef.size(byteArrayRefSegment)

        if (dataPtr == MemorySegment.NULL || size == 0L) return null

        val dataSegment = dataPtr.reinterpret(size)
        val bytes = ByteArray(size.toInt())
        MemorySegment.copy(dataSegment, ValueLayout.JAVA_BYTE, 0, bytes, 0, size.toInt())
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Reads raw bytes from an inline TemporalCoreByteArrayRef struct.
     *
     * @param byteArrayRefSegment MemorySegment containing the ByteArrayRef inline
     * @return The byte content, or null if empty
     */
    fun readByteArrayRefAsBytes(byteArrayRefSegment: MemorySegment): ByteArray? {
        val dataPtr = TemporalCoreByteArrayRef.data(byteArrayRefSegment)
        val size = TemporalCoreByteArrayRef.size(byteArrayRefSegment)

        if (dataPtr == MemorySegment.NULL || size == 0L) return null

        val dataSegment = dataPtr.reinterpret(size)
        val bytes = ByteArray(size.toInt())
        MemorySegment.copy(dataSegment, ValueLayout.JAVA_BYTE, 0, bytes, 0, size.toInt())
        return bytes
    }

    // ============================================================
    // ByteArray Read-and-Free Functions (for callback results)
    // ============================================================

    /**
     * Frees a TemporalCoreByteArray if not null.
     *
     * @param runtimePtr Pointer to the Temporal runtime
     * @param ptr Pointer to the byte array (may be NULL)
     */
    fun freeByteArrayIfNotNull(
        runtimePtr: MemorySegment,
        ptr: MemorySegment,
    ) {
        if (ptr != MemorySegment.NULL) {
            CoreBridge.temporal_core_byte_array_free(runtimePtr, ptr)
        }
    }

    /**
     * Reads a string from a TemporalCoreByteArray and frees the native memory.
     *
     * @param runtimePtr Pointer to the Temporal runtime (for freeing)
     * @param ptr Pointer to the byte array (may be NULL)
     * @return The string content, or null if pointer is NULL
     */
    fun readAndFreeByteArray(
        runtimePtr: MemorySegment,
        ptr: MemorySegment,
    ): String? {
        if (ptr == MemorySegment.NULL) return null
        return readByteArray(ptr).also {
            CoreBridge.temporal_core_byte_array_free(runtimePtr, ptr)
        }
    }

    /**
     * Reads raw bytes from a TemporalCoreByteArray and frees the native memory.
     *
     * @param runtimePtr Pointer to the Temporal runtime (for freeing)
     * @param ptr Pointer to the byte array (may be NULL)
     * @return The byte content, or null if pointer is NULL
     */
    fun readAndFreeByteArrayAsBytes(
        runtimePtr: MemorySegment,
        ptr: MemorySegment,
    ): ByteArray? {
        if (ptr == MemorySegment.NULL) return null
        return readByteArrayAsBytes(ptr).also {
            CoreBridge.temporal_core_byte_array_free(runtimePtr, ptr)
        }
    }

    // ============================================================
    // Zero-Copy Protobuf Parsing Functions
    // ============================================================

    /**
     * Parses a protobuf message directly from native memory without intermediate ByteArray copy.
     * Uses MemorySegment.asByteBuffer() for zero-copy access to native memory.
     *
     * IMPORTANT: This function does NOT use aliasing because the native memory is freed
     * immediately after parsing. The parsed message is fully materialized before returning.
     *
     * @param runtimePtr The runtime pointer for freeing memory
     * @param ptr The TemporalCoreByteArray pointer from Rust
     * @param parser Function that parses the CodedInputStream into a protobuf message
     * @return Parsed message, or null if ptr is NULL
     */
    inline fun <T : MessageLite> readAndParseProto(
        runtimePtr: MemorySegment,
        ptr: MemorySegment,
        parser: (CodedInputStream) -> T,
    ): T? {
        if (ptr == MemorySegment.NULL) return null

        try {
            val reinterpreted = ptr.reinterpret(TemporalCoreByteArray.sizeof())
            val dataPtr = TemporalCoreByteArray.data(reinterpreted)
            val size = TemporalCoreByteArray.size(reinterpreted)

            // Create CodedInputStream - handles empty data (valid for messages like Empty)
            val codedInput =
                if (dataPtr == MemorySegment.NULL || size == 0L) {
                    // Empty data is valid for some protobuf messages (e.g., Empty)
                    // Parse from empty input to get default instance
                    CodedInputStream.newInstance(EMPTY_BYTES)
                } else {
                    // Reinterpret to get a segment with the actual data size
                    val dataSegment = dataPtr.reinterpret(size)

                    // Zero-copy: get ByteBuffer view of native memory
                    // Note: ByteOrder does not affect protobuf parsing (varint encoding is byte-stream based)
                    val byteBuffer = dataSegment.asByteBuffer()

                    // Create CodedInputStream from ByteBuffer (no copy during parsing)
                    CodedInputStream.newInstance(byteBuffer)
                }

            // DO NOT enable aliasing - memory will be freed after this function
            // The parsed message must be fully materialized

            return parser(codedInput)
        } finally {
            // Free native memory AFTER parsing is complete
            CoreBridge.temporal_core_byte_array_free(runtimePtr, ptr)
        }
    }

    // ============================================================
    // Zero-Copy Protobuf Serialization Functions
    // ============================================================

    /**
     * Serializes a protobuf message directly to native memory without intermediate ByteArray copy.
     * Uses MemorySegment.asByteBuffer() for zero-copy access to native memory.
     *
     * @param allocator The allocator to use (typically an Arena)
     * @param message The protobuf message to serialize
     * @return A MemorySegment containing the ByteArrayRef struct pointing to the serialized data
     */
    fun <T : MessageLite> serializeToByteArrayRef(
        allocator: SegmentAllocator,
        message: T,
    ): MemorySegment {
        val size = message.serializedSize
        if (size == 0) {
            return createEmptyByteArrayRef(allocator)
        }

        // Allocate native memory for the serialized data
        val dataSegment = allocator.allocate(size.toLong())

        // Zero-copy: get ByteBuffer view of native memory and serialize directly into it
        val byteBuffer = dataSegment.asByteBuffer()
        val codedOutput = CodedOutputStream.newInstance(byteBuffer)
        message.writeTo(codedOutput)

        // Create ByteArrayRef pointing to the native memory
        return createByteArrayRef(allocator, dataSegment, size.toLong())
    }

    // ============================================================
    // Typed Callback Infrastructure (Shared)
    // ============================================================

    /**
     * Generic typed callback interface for zero-copy protobuf parsing.
     * Used by both worker poll and client RPC operations.
     */
    fun interface TypedCallback<T> {
        fun onComplete(
            data: T?,
            error: String?,
        )
    }

    /**
     * Wrapper that captures a typed callback and its parser for deferred invocation.
     * Parses protobuf directly from native memory (zero-copy) when invoked.
     */
    class TypedCallbackWrapper<T : MessageLite>(
        private val callback: TypedCallback<T>,
        private val parser: (CodedInputStream) -> T,
    ) {
        fun invoke(
            runtimePtr: MemorySegment,
            successPtr: MemorySegment,
            failPtr: MemorySegment,
        ) {
            val data = readAndParseProto(runtimePtr, successPtr, parser)
            val error = readAndFreeByteArray(runtimePtr, failPtr)
            callback.onComplete(data, error)
        }
    }

    // ============================================================
    // ByteArrayRef Creation Functions
    // ============================================================

    /**
     * Creates a TemporalCoreByteArrayRef from a Kotlin string.
     *
     * @param allocator The allocator to use
     * @param value The string value (or null for empty)
     * @return A MemorySegment containing the ByteArrayRef struct
     */
    fun createByteArrayRef(
        allocator: SegmentAllocator,
        value: String?,
    ): MemorySegment {
        val ref = TemporalCoreByteArrayRef.allocate(allocator)
        if (value.isNullOrEmpty()) {
            TemporalCoreByteArrayRef.data(ref, EMPTY_DATA_SEGMENT)
            TemporalCoreByteArrayRef.size(ref, 0L)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val dataSegment = allocator.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, dataSegment, ValueLayout.JAVA_BYTE, 0, bytes.size)
            TemporalCoreByteArrayRef.data(ref, dataSegment)
            TemporalCoreByteArrayRef.size(ref, bytes.size.toLong())
        }
        return ref
    }

    /**
     * Creates a TemporalCoreByteArrayRef from a byte array.
     *
     * @param allocator The allocator to use
     * @param bytes The byte array (or null for empty)
     * @return A MemorySegment containing the ByteArrayRef struct
     */
    fun createByteArrayRef(
        allocator: SegmentAllocator,
        bytes: ByteArray?,
    ): MemorySegment {
        val ref = TemporalCoreByteArrayRef.allocate(allocator)
        if (bytes == null || bytes.isEmpty()) {
            TemporalCoreByteArrayRef.data(ref, EMPTY_DATA_SEGMENT)
            TemporalCoreByteArrayRef.size(ref, 0L)
        } else {
            val dataSegment = allocator.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, dataSegment, ValueLayout.JAVA_BYTE, 0, bytes.size)
            TemporalCoreByteArrayRef.data(ref, dataSegment)
            TemporalCoreByteArrayRef.size(ref, bytes.size.toLong())
        }
        return ref
    }

    /**
     * Creates a TemporalCoreByteArrayRef pointing to existing native memory.
     * Use this when you already have native memory allocated.
     *
     * @param allocator The allocator to use for the ref struct
     * @param dataPtr Pointer to the data
     * @param size Size of the data in bytes
     * @return A MemorySegment containing the ByteArrayRef struct
     */
    fun createByteArrayRef(
        allocator: SegmentAllocator,
        dataPtr: MemorySegment,
        size: Long,
    ): MemorySegment {
        val ref = TemporalCoreByteArrayRef.allocate(allocator)
        TemporalCoreByteArrayRef.data(ref, dataPtr)
        TemporalCoreByteArrayRef.size(ref, size)
        return ref
    }

    /**
     * Creates an empty TemporalCoreByteArrayRef (valid pointer, zero size).
     *
     * Uses a valid non-null pointer because Rust's std::slice::from_raw_parts
     * requires a non-null, properly aligned pointer even when size is 0.
     *
     * @param allocator The allocator to use
     * @return A MemorySegment containing an empty ByteArrayRef struct
     */
    fun createEmptyByteArrayRef(allocator: SegmentAllocator): MemorySegment {
        val ref = TemporalCoreByteArrayRef.allocate(allocator)
        TemporalCoreByteArrayRef.data(ref, EMPTY_DATA_SEGMENT)
        TemporalCoreByteArrayRef.size(ref, 0L)
        return ref
    }
}
