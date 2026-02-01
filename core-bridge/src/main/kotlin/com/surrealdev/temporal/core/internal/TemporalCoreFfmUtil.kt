package com.surrealdev.temporal.core.internal

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
            TemporalCoreByteArrayRef.data(ref, MemorySegment.NULL)
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
            TemporalCoreByteArrayRef.data(ref, MemorySegment.NULL)
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
     * Creates an empty TemporalCoreByteArrayRef (null data, zero size).
     *
     * @param allocator The allocator to use
     * @return A MemorySegment containing an empty ByteArrayRef struct
     */
    fun createEmptyByteArrayRef(allocator: SegmentAllocator): MemorySegment {
        val ref = TemporalCoreByteArrayRef.allocate(allocator)
        TemporalCoreByteArrayRef.data(ref, MemorySegment.NULL)
        TemporalCoreByteArrayRef.size(ref, 0L)
        return ref
    }
}
