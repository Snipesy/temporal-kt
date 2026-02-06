# Serialization and Codecs

Temporal-Kt converts workflow and activity data to Temporal Payloads using a two-stage pipeline:

```
Object → [Serializer] → TemporalPayload → [Codec] → Temporal Server
```

**Serializers** convert objects to payloads. **Codecs** transform payloads (compression, encryption).

## Serialization

By default, Temporal-Kt uses kotlinx.serialization with JSON. No configuration required.

### Configure JSON

```kotlin
install(SerializationPlugin) {
    json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
```


### Custom KSerializer Module

Rather than developing a full PayloadSerializer you can define custom serializers for specific types with
kotlinx.serialization:

```kotlin
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }
}

val module = SerializersModule {
    contextual(UUID::class, UUIDSerializer)
}

install(SerializationPlugin) {
    json {
        serializersModule = module
    }
}
```

### Custom Temporal Serializer

Implement `PayloadSerializer` for other formats. Use `TemporalPayload.create()` to build payloads
without touching protobuf types directly:

```kotlin
class MyProtobufSerializer : PayloadSerializer {
    override fun serialize(typeInfo: KType, value: Any?): TemporalPayload {
        if (value == null) {
            return TemporalPayload.create(
                mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_NULL))
            )
        }
        val metadata = mapOf(
            TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_BINARY),
        )
        // Write directly to the payload's data stream — no intermediate copies
        return TemporalPayload.create(metadata) { stream ->
            myEncode(value, stream)
        }
    }

    override fun deserialize(typeInfo: KType, payload: TemporalPayload): Any? {
        if (payload.encoding == TemporalPayload.ENCODING_NULL) return null
        // Read directly from the payload's data stream
        return myDecode(typeInfo, payload.dataInputStream())
    }
}
```

Install it:

```kotlin
install(SerializationPlugin) {
    custom(MyProtobufSerializer())
}
```

## Codecs

Codecs transform payloads after serialization. Use them for compression or encryption.

### Compression

Compress large payloads with GZIP:

```kotlin
install(CodecPlugin) {
    compression(threshold = 1024)  // Only compress payloads > 1KB
}
```

### Custom Codec

Codecs operate on `TemporalPayloads` and use `TemporalPayload`/`TemporalByteString` for
zero-copy construction:

```kotlin
class EncryptionCodec(private val keyProvider: KeyProvider) : PayloadCodec {
    companion object {
        private const val ENCODING_ENCRYPTED = "binary/encrypted"
        private val ENCODING_ENCRYPTED_BYTES = TemporalByteString.fromUtf8(ENCODING_ENCRYPTED)
    }

    override suspend fun encode(payloads: TemporalPayloads): TemporalPayloads {
        val key = keyProvider.getKey()
        return TemporalPayloads.of(payloads.payloads.map { payload ->
            val encrypted = encrypt(payload.data, key)
            val newMetadata = payload.metadataByteStrings.toMutableMap()
            newMetadata[TemporalPayload.METADATA_ENCODING] = ENCODING_ENCRYPTED_BYTES
            TemporalPayload.create(encrypted, newMetadata)
        })
    }

    override suspend fun decode(payloads: TemporalPayloads): TemporalPayloads {
        return TemporalPayloads.of(payloads.payloads.map { payload ->
            if (payload.encoding != ENCODING_ENCRYPTED) return@map payload

            val key = keyProvider.getKey()
            val decrypted = decrypt(payload.data, key)
            val newMetadata = payload.metadataByteStrings.toMutableMap()
            newMetadata.remove(TemporalPayload.METADATA_ENCODING)
            TemporalPayload.create(decrypted, newMetadata)
        })
    }
}
```

Install it:

```kotlin
install(CodecPlugin) {
    custom(EncryptionCodec(keyProvider))
}
```

### Chaining Codecs

Chain multiple codecs together. Order matters—encode applies left-to-right, decode applies right-to-left:

```kotlin
install(CodecPlugin) {
    chained {
        compression(threshold = 512)  // First: compress
        codec(EncryptionCodec(key))   // Then: encrypt
    }
}
// Encode: compress → encrypt
// Decode: decrypt → decompress
```

## Complete Example

```kotlin
fun main() {
    embeddedTemporal(module = {
        install(SerializationPlugin) {
            json { ignoreUnknownKeys = true }
        }

        install(CodecPlugin) {
            compression(threshold = 1024)
        }

        taskQueue("orders-queue") {
            workflow<OrderWorkflow>()
            activity(OrderActivity())
        }
    }).start(wait = true)
}
```

Or in a config-driven module:

```kotlin
fun TemporalApplication.ordersModule() {
    install(SerializationPlugin) {
        json { ignoreUnknownKeys = true }
    }

    install(CodecPlugin) {
        compression(threshold = 1024)
    }

    taskQueue("orders-queue") {
        workflow<OrderWorkflow>()
        activity(OrderActivity())
    }
}
```

## Data Classes

Mark data classes with `@Serializable`:

```kotlin
@Serializable
data class OrderRequest(
    val orderId: String,
    val items: List<OrderItem>,
)

@Serializable
data class OrderItem(
    val productId: String,
    val quantity: Int,
)
```