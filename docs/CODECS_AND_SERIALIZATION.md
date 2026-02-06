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

### Converter Chain

Serialization uses an ordered chain of `PayloadConverter`s, following the Temporal SDK convention:
- **Serialization**: first-match-wins (converters tried in order)
- **Deserialization**: encoding-based lookup (payload's `encoding` metadata selects the converter)

The default chain is `[NullPayloadConverter, JsonPayloadConverter]`.

To add custom converters (e.g., protobuf, binary), use the `converters` DSL:

```kotlin
install(SerializationPlugin) {
    converters {
        null()                                   // binary/null
        byteArray()                              // binary/plain
        converter(MyProtobufPayloadConverter())  // binary/protobuf
        json { ignoreUnknownKeys = true }        // json/plain (catch-all, must be last)
    }
}
```

### Custom PayloadConverter

Implement `PayloadConverter` for a specific encoding format. Return `null` from `toPayload()` if
this converter cannot handle the value — the next converter in the chain will be tried:

```kotlin
class ProtobufPayloadConverter : PayloadConverter {
    override val encoding = "binary/protobuf"

    override fun toPayload(typeInfo: KType, value: Any?): TemporalPayload? {
        if (value !is Message) return null  // Can't handle, skip to next
        val metadata = mapOf(
            TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(encoding),
        )
        return TemporalPayload.create(metadata) { stream ->
            value.writeTo(stream)
        }
    }

    override fun fromPayload(typeInfo: KType, payload: TemporalPayload): Any? {
        // Only called when encoding matches "binary/protobuf"
        return parseFrom(typeInfo, payload.dataInputStream())
    }
}
```

### Custom PayloadSerializer

For full control, implement `PayloadSerializer` directly. This bypasses the converter chain:

```kotlin
class MyCustomSerializer : PayloadSerializer {
    override fun serialize(typeInfo: KType, value: Any?): TemporalPayload {
        if (value == null) {
            return TemporalPayload.create(
                mapOf(TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_NULL))
            )
        }
        val metadata = mapOf(
            TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_BINARY),
        )
        return TemporalPayload.create(metadata) { stream ->
            myEncode(value, stream)
        }
    }

    override fun deserialize(typeInfo: KType, payload: TemporalPayload): Any? {
        if (payload.encoding == TemporalPayload.ENCODING_NULL) return null
        return myDecode(typeInfo, payload.dataInputStream())
    }
}
```

Install it:

```kotlin
install(SerializationPlugin) {
    custom(MyCustomSerializer())
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