package com.surrealdev.temporal.core.internal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.GroupLayout
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * Common FFM utilities for the Temporal Core bridge.
 *
 * Contains memory layouts, linker/lookup, and helper functions shared
 * across all bridge objects.
 */
internal object TemporalCoreFfmUtil {
    val linker: Linker = Linker.nativeLinker()
    val lookup: SymbolLookup by lazy { NativeLoader.load() }

    // ============================================================
    // Memory Layouts for C structures
    // ============================================================

    /**
     * Layout for TemporalCoreByteArrayRef:
     * ```c
     * typedef struct TemporalCoreByteArrayRef {
     *   const uint8_t *data;
     *   size_t size;
     * } TemporalCoreByteArrayRef;
     * ```
     */
    val BYTE_ARRAY_REF_LAYOUT: GroupLayout =
        MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("data"),
            ValueLayout.JAVA_LONG.withName("size"),
        )

    /**
     * Layout for TemporalCoreByteArray (owned):
     * ```c
     * typedef struct TemporalCoreByteArray {
     *   const uint8_t *data;
     *   size_t size;
     *   size_t cap;
     *   bool disable_free;
     * } TemporalCoreByteArray;
     * ```
     */
    val BYTE_ARRAY_LAYOUT: GroupLayout =
        MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("data"),
            ValueLayout.JAVA_LONG.withName("size"),
            ValueLayout.JAVA_LONG.withName("cap"),
            ValueLayout.JAVA_BOOLEAN.withName("disable_free"),
            MemoryLayout.paddingLayout(7), // padding to align struct
        )

    /**
     * Layout for TemporalCoreRuntimeOrFail:
     * ```c
     * typedef struct TemporalCoreRuntimeOrFail {
     *   struct TemporalCoreRuntime *runtime;
     *   const struct TemporalCoreByteArray *fail;
     * } TemporalCoreRuntimeOrFail;
     * ```
     */
    val RUNTIME_OR_FAIL_LAYOUT: GroupLayout =
        MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("runtime"),
            ValueLayout.ADDRESS.withName("fail"),
        )

    /**
     * Layout for TemporalCoreRuntimeOptions:
     * ```c
     * typedef struct TemporalCoreRuntimeOptions {
     *   const struct TemporalCoreTelemetryOptions *telemetry;
     *   uint64_t worker_heartbeat_interval_millis;
     * } TemporalCoreRuntimeOptions;
     * ```
     */
    val RUNTIME_OPTIONS_LAYOUT: GroupLayout =
        MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("telemetry"),
            ValueLayout.JAVA_LONG.withName("worker_heartbeat_interval_millis"),
        )

    /**
     * Layout for TemporalCoreTestServerOptions:
     * ```c
     * typedef struct TemporalCoreTestServerOptions {
     *   struct TemporalCoreByteArrayRef existing_path;
     *   struct TemporalCoreByteArrayRef sdk_name;
     *   struct TemporalCoreByteArrayRef sdk_version;
     *   struct TemporalCoreByteArrayRef download_version;
     *   struct TemporalCoreByteArrayRef download_dest_dir;
     *   uint16_t port;
     *   struct TemporalCoreByteArrayRef extra_args;
     *   uint64_t download_ttl_seconds;
     * } TemporalCoreTestServerOptions;
     * ```
     */
    val TEST_SERVER_OPTIONS_LAYOUT: GroupLayout =
        MemoryLayout.structLayout(
            BYTE_ARRAY_REF_LAYOUT.withName("existing_path"), // 16 bytes
            BYTE_ARRAY_REF_LAYOUT.withName("sdk_name"), // 16 bytes
            BYTE_ARRAY_REF_LAYOUT.withName("sdk_version"), // 16 bytes
            BYTE_ARRAY_REF_LAYOUT.withName("download_version"), // 16 bytes
            BYTE_ARRAY_REF_LAYOUT.withName("download_dest_dir"), // 16 bytes
            ValueLayout.JAVA_SHORT.withName("port"), // 2 bytes
            MemoryLayout.paddingLayout(6), // padding to 8-byte align
            BYTE_ARRAY_REF_LAYOUT.withName("extra_args"), // 16 bytes
            ValueLayout.JAVA_LONG.withName("download_ttl_seconds"), // 8 bytes
        )

    /**
     * Layout for TemporalCoreDevServerOptions:
     * ```c
     * typedef struct TemporalCoreDevServerOptions {
     *   const struct TemporalCoreTestServerOptions *test_server;
     *   struct TemporalCoreByteArrayRef namespace_;
     *   struct TemporalCoreByteArrayRef ip;
     *   struct TemporalCoreByteArrayRef database_filename;
     *   bool ui;
     *   uint16_t ui_port;
     *   struct TemporalCoreByteArrayRef log_format;
     *   struct TemporalCoreByteArrayRef log_level;
     * } TemporalCoreDevServerOptions;
     * ```
     */
    val DEV_SERVER_OPTIONS_LAYOUT: GroupLayout =
        MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("test_server"), // 8 bytes
            BYTE_ARRAY_REF_LAYOUT.withName("namespace_"), // 16 bytes
            BYTE_ARRAY_REF_LAYOUT.withName("ip"), // 16 bytes
            BYTE_ARRAY_REF_LAYOUT.withName("database_filename"), // 16 bytes
            ValueLayout.JAVA_BOOLEAN.withName("ui"), // 1 byte
            MemoryLayout.paddingLayout(1), // padding
            ValueLayout.JAVA_SHORT.withName("ui_port"), // 2 bytes
            MemoryLayout.paddingLayout(4), // padding to 8-byte align
            BYTE_ARRAY_REF_LAYOUT.withName("log_format"), // 16 bytes
            BYTE_ARRAY_REF_LAYOUT.withName("log_level"), // 16 bytes
        )

    // ============================================================
    // Callback Descriptors
    // ============================================================

    /**
     * Callback descriptor for ephemeral server start:
     * void (*callback)(void *user_data, TemporalCoreEphemeralServer *success,
     *                  const TemporalCoreByteArray *success_target, const TemporalCoreByteArray *fail)
     */
    val EPHEMERAL_SERVER_START_CALLBACK_DESC: FunctionDescriptor =
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, // user_data
            ValueLayout.ADDRESS, // success (server pointer)
            ValueLayout.ADDRESS, // success_target (target URL)
            ValueLayout.ADDRESS, // fail (error message)
        )

    /**
     * Callback descriptor for ephemeral server shutdown:
     * void (*callback)(void *user_data, const TemporalCoreByteArray *fail)
     */
    val EPHEMERAL_SERVER_SHUTDOWN_CALLBACK_DESC: FunctionDescriptor =
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, // user_data
            ValueLayout.ADDRESS, // fail (error message)
        )

    // ============================================================
    // Helper Functions
    // ============================================================

    /**
     * Writes a ByteArrayRef to a memory segment at the given offset.
     * Returns the size of the ByteArrayRef struct (16 bytes).
     */
    fun writeByteArrayRef(
        arena: Arena,
        segment: MemorySegment,
        offset: Long,
        value: String?,
    ): Long {
        if (value.isNullOrEmpty()) {
            segment.set(ValueLayout.ADDRESS, offset, MemorySegment.NULL)
            segment.set(ValueLayout.JAVA_LONG, offset + 8, 0L)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val data = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, data, ValueLayout.JAVA_BYTE, 0, bytes.size)
            segment.set(ValueLayout.ADDRESS, offset, data)
            segment.set(ValueLayout.JAVA_LONG, offset + 8, bytes.size.toLong())
        }
        return 16L // Size of ByteArrayRef struct
    }

    /**
     * Reads a string from a TemporalCoreByteArray pointer.
     */
    fun readByteArray(byteArrayPtr: MemorySegment): String? {
        if (byteArrayPtr == MemorySegment.NULL) return null

        val reinterpreted = byteArrayPtr.reinterpret(BYTE_ARRAY_LAYOUT.byteSize())
        val dataPtr = reinterpreted.get(ValueLayout.ADDRESS, 0)
        val size = reinterpreted.get(ValueLayout.JAVA_LONG, 8)

        if (dataPtr == MemorySegment.NULL || size == 0L) return null

        val dataSegment = dataPtr.reinterpret(size)
        val bytes = ByteArray(size.toInt())
        MemorySegment.copy(dataSegment, ValueLayout.JAVA_BYTE, 0, bytes, 0, size.toInt())
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Creates a TemporalCoreByteArrayRef from a Kotlin string.
     *
     * @param arena The arena to allocate in
     * @param value The string value (or null for empty)
     * @return A MemorySegment containing the ByteArrayRef struct
     */
    fun createByteArrayRef(
        arena: Arena,
        value: String?,
    ): MemorySegment {
        val ref = arena.allocate(BYTE_ARRAY_REF_LAYOUT)
        if (value.isNullOrEmpty()) {
            ref.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
            ref.set(ValueLayout.JAVA_LONG, 8, 0L)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val dataSegment = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, dataSegment, ValueLayout.JAVA_BYTE, 0, bytes.size)
            ref.set(ValueLayout.ADDRESS, 0, dataSegment)
            ref.set(ValueLayout.JAVA_LONG, 8, bytes.size.toLong())
        }
        return ref
    }

    /**
     * Creates a TemporalCoreByteArrayRef from a byte array.
     *
     * @param arena The arena to allocate in
     * @param bytes The byte array (or null for empty)
     * @return A MemorySegment containing the ByteArrayRef struct
     */
    fun createByteArrayRef(
        arena: Arena,
        bytes: ByteArray?,
    ): MemorySegment {
        val ref = arena.allocate(BYTE_ARRAY_REF_LAYOUT)
        if (bytes == null || bytes.isEmpty()) {
            ref.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
            ref.set(ValueLayout.JAVA_LONG, 8, 0L)
        } else {
            val dataSegment = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, dataSegment, ValueLayout.JAVA_BYTE, 0, bytes.size)
            ref.set(ValueLayout.ADDRESS, 0, dataSegment)
            ref.set(ValueLayout.JAVA_LONG, 8, bytes.size.toLong())
        }
        return ref
    }

    /**
     * Finds a symbol in the native library or throws UnsatisfiedLinkError.
     */
    fun findSymbol(name: String): MemorySegment =
        lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("Symbol not found: $name")
        }
}
