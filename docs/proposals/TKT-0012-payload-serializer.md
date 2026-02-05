# TKT-0012: Payload Serializer

Custom payload serialization via `PayloadSerializer` interface.

## PayloadSerializer Interface

```kotlin
interface PayloadSerializer {
    fun serialize(typeInfo: KType, value: Any?): TemporalPayload
    fun deserialize(typeInfo: KType, payload: TemporalPayload): Any?
}
```

Serializers use `KType` (not `TypeInfo`) to preserve full generic type information end-to-end,
avoiding type erasure issues.

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

## Custom Serializer

Implement `PayloadSerializer` for custom formats. Use `TemporalPayload.create()` factories
and `TemporalByteString` to avoid touching protobuf types:

```kotlin
class MessagePackSerializer : PayloadSerializer {
    private val msgpack = MessagePack.newDefaultPacker()
    private val MSGPACK_META = mapOf(
        TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8("binary/msgpack")
    )
    private val NULL_META = mapOf(
        TemporalPayload.METADATA_ENCODING to TemporalByteString.fromUtf8(TemporalPayload.ENCODING_NULL)
    )

    override fun serialize(typeInfo: KType, value: Any?): TemporalPayload {
        if (value == null) return TemporalPayload.create(NULL_META)
        return TemporalPayload.create(MSGPACK_META) { stream ->
            msgpack.pack(value, stream)
        }
    }

    override fun deserialize(typeInfo: KType, payload: TemporalPayload): Any? {
        if (payload.encoding == TemporalPayload.ENCODING_NULL) return null
        return msgpack.unpack(payload.dataInputStream(), typeInfo.javaType)
    }
}

// Usage
install(PayloadSerialization) {
    custom(MessagePackSerializer())
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
Object → [PayloadSerializer] → TemporalPayload → [PayloadCodec] → Encoded Payload → Temporal
```

- **PayloadSerializer**: Object <-> TemporalPayload (serialization format)
- **PayloadCodec**: TemporalPayload <-> TemporalPayload (encryption, compression)