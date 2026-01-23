# TKT-0012: Payload Serializer

Custom payload serialization via `PayloadSerializer` interface, similar to Ktor's `ContentConverter`.

## PayloadSerializer Interface

```kotlin
interface PayloadSerializer {
    suspend fun serialize(typeInfo: TypeInfo, value: Any?): ByteArray
    suspend fun deserialize(typeInfo: TypeInfo, bytes: ByteArray): Any?
}
```

## Installation

```kotlin
fun TemporalApplication.module() {
    install(PayloadSerialization) {
        json()  // kotlinx.serialization JSON (default)
    }
}
```

## Built-in Serializers

### JSON (Default)

```kotlin
install(PayloadSerialization) {
    json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
```

### Protocol Buffers

```kotlin
install(PayloadSerialization) {
    protobuf {
        encodeDefaults = true
    }
}
```

### CBOR

```kotlin
install(PayloadSerialization) {
    cbor {
        ignoreUnknownKeys = true
    }
}
```

## Custom Serializer (Non Kotlinx)

Implement `PayloadSerializer` for custom formats specific to one class:

```kotlin
class MessagePackSerializer : PayloadSerializer {
    private val msgpack = MessagePack.newDefaultPacker()

    override suspend fun serialize(typeInfo: TypeInfo, value: Any?): ByteArray {
        return msgpack.pack(value)
    }

    override suspend fun deserialize(typeInfo: TypeInfo, bytes: ByteArray): Any? {
        return msgpack.unpack(bytes, typeInfo.type.java)
    }
}

// Usage
install(PayloadSerialization) {
    register(MessagePackSerializer())
}
```

## Multiple Serializers

Register multiple serializers with content type routing:

```kotlin
install(PayloadSerialization) {
    json()  // application/json
    protobuf()  // application/x-protobuf

    // Custom with explicit content type
    register(ContentType.Application.Xml, XmlSerializer())
}
```

## Contextual Serializers (kotlinx)

```kotlin
install(PayloadSerialization) {
    json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer)
            contextual(BigDecimal::class, BigDecimalSerializer)
            polymorphic(Event::class) {
                subclass(OrderCreated::class)
                subclass(OrderShipped::class)
            }
        }
    }
}
```

## Custom KSerializer

For type-specific serialization with kotlinx:

```kotlin
@Serializable(with = InstantAsLongSerializer::class)
data class MyEvent(
    val timestamp: Instant,
    val data: String
)

object InstantAsLongSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilliseconds())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.fromEpochMilliseconds(decoder.decodeLong())
    }
}
```

## Relationship with PayloadCodec (TKT-0011)

```
Object → [PayloadSerializer] → bytes → [PayloadCodec] → transformed bytes → Temporal
```

- **PayloadSerializer**: Object ↔ bytes (serialization format)
- **PayloadCodec**: bytes ↔ bytes (encryption, compression)