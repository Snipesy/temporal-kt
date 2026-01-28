# Serialization and Codecs

Temporal-Kt converts workflow and activity data to Temporal Payloads using a two-stage pipeline:

```
Object → [Serializer] → Payload → [Codec] → Temporal Server
```

**Serializers** convert objects to payloads. **Codecs** transform payloads (compression, encryption).

## Serialization

By default, Temporal-Kt uses kotlinx.serialization with JSON. No configuration required.

### Configure JSON

```kotlin
install(PayloadSerialization) {
    json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
```

### Custom Serializer

Implement `PayloadSerializer` for other formats:

```kotlin
class MyProtobufSerializer : PayloadSerializer {
    override fun serialize(typeInfo: KType, value: Any?): Payload {
        // Convert value to Payload
    }

    override fun deserialize(typeInfo: KType, payload: Payload): Any? {
        // Convert Payload back to object
    }
}
```

Install it:

```kotlin
install(PayloadSerialization) {
    custom(MyProtobufSerializer())
}
```

## Codecs

Codecs transform payloads after serialization. Use them for compression or encryption.

### Compression

Compress large payloads with GZIP:

```kotlin
install(PayloadCodecPlugin) {
    compression(threshold = 1024)  // Only compress payloads > 1KB
}
```

### Custom Codec

```kotlin
class EncryptionCodec(private val keyProvider: KeyProvider) : PayloadCodec {
    override suspend fun encode(payloads: List<Payload>): List<Payload> {
        val key = keyProvider.getKey()
        return payloads.map { payload ->
            Payload.newBuilder()
                .putAllMetadata(payload.metadataMap)
                .putMetadata("encoding", ByteString.copyFromUtf8("binary/encrypted"))
                .setData(ByteString.copyFrom(encrypt(payload.data.toByteArray(), key)))
                .build()
        }
    }

    override suspend fun decode(payloads: List<Payload>): List<Payload> {
        return payloads.map { payload ->
            val encoding = payload.metadataMap["encoding"]?.toStringUtf8()
            if (encoding != "binary/encrypted") return@map payload

            val key = keyProvider.getKey()
            Payload.newBuilder()
                .putAllMetadata(payload.metadataMap.filterKeys { it != "encoding" })
                .setData(ByteString.copyFrom(decrypt(payload.data.toByteArray(), key)))
                .build()
        }
    }
}
```

Install it:

```kotlin
install(PayloadCodecPlugin) {
    codec = EncryptionCodec(keyProvider)
}
```

### Chaining Codecs

Chain multiple codecs together. Order matters—encode applies left-to-right, decode applies right-to-left:

```kotlin
install(PayloadCodecPlugin) {
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
        install(PayloadSerialization) {
            json { ignoreUnknownKeys = true }
        }

        install(PayloadCodecPlugin) {
            compression(threshold = 1024)
        }

        taskQueue("orders-queue") {
            workflow(OrderWorkflow())
            activity(OrderActivity())
        }
    }).start(wait = true)
}
```

Or in a config-driven module:

```kotlin
fun TemporalApplication.ordersModule() {
    install(PayloadSerialization) {
        json { ignoreUnknownKeys = true }
    }

    install(PayloadCodecPlugin) {
        compression(threshold = 1024)
    }

    taskQueue("orders-queue") {
        workflow(OrderWorkflow())
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
